package com.metallic.chiaki.tarjim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.Event
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Tarjim on the phone, the whole loop:
 *
 *   PS5 picture (Remote Play, receive only) -> ImageReader -> read the subtitle area
 *   (ML Kit text recognition) -> translate on the device (ML Kit) -> draw the Arabic
 *   on a dark plate -> send it to the TV overlay app.
 *
 * It never sends controller input: [Session.setControllerState] is not called anywhere
 * in Tarjim mode, and the session's sound is muted.
 */
class TarjimEngine(private val context: Context, private val connectInfo: ConnectInfo, private val tvHost: String)
{
	data class Status(
		val console: String = "أتصل بالـPS5…", val consoleOk: Boolean = false,
		val tv: String = "أتصل بالتلفزيون…", val tvOk: Boolean = false,
		val translator: String = "أجهّز المترجم…", val translatorOk: Boolean = false,
		val frames: Int = 0, val fps: Int = 0, val lines: Int = 0, val lastMs: Int = 0,
		val english: String = "", val arabic: String = "", val error: String = "")

	@Volatile var status = Status(); private set
	var onStatus: ((Status) -> Unit)? = null

	private val worker = HandlerThread("tarjim-engine").apply { start() }
	private val handler = Handler(worker.looper)
	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(
		TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.ARABIC).build())
	private val cache = HashMap<String, String>()
	private val typeface: Typeface = Typeface.createFromAsset(context.assets, "Harmattan-Regular.ttf")

	private var reader: ImageReader? = null
	private var session: Session? = null
	private var link: TvLink? = null
	@Volatile private var running = false
	@Volatile private var translatorReady = false

	// what is on the TV and what we are watching
	private var candidate = ""
	private var candidateCount = 0
	private var shown = ""
	private var shownAt = 0L
	private var readMs = 0L
	private var up = false
	private var misses = 0
	private var lastOcr = 0L
	private var lastKeep = 0L
	private var lastTvTry = 0L
	private var frameCount = 0
	private var fpsCount = 0
	private var fpsAt = 0L
	private var nv21: ByteArray? = null

	private fun update(change: (Status) -> Status)
	{
		status = change(status)
		onStatus?.invoke(status)
	}

	fun start()
	{
		running = true
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener { translatorReady = true; update { it.copy(translator = "المترجم جاهز", translatorOk = true) } }
			.addOnFailureListener { e -> update { it.copy(translator = "ما قدرت أنزّل ملف الترجمة (يحتاج إنترنت أول مرة)", error = e.message ?: "") } }
		handler.post { connectTv() }
		handler.post { startSession() }
	}

	fun stop()
	{
		running = false
		handler.post {
			try { session?.stop(); session?.dispose() } catch(e: Exception) { Log.w(TAG, "session stop", e) }
			session = null
			link?.close()
			link = null
			reader?.close()
			reader = null
			recognizer.close()
			translator.close()
			worker.quitSafely()
		}
	}

	// -- the two connections -------------------------------------------------------

	private fun connectTv()
	{
		if(!running) return
		lastTvTry = SystemClock.elapsedRealtime()
		val l = TvLink(tvHost)
		try
		{
			l.connect()
			link = l
			up = false
			update { it.copy(tv = "التلفزيون متصل (${l.width}×${l.height})", tvOk = true) }
		}
		catch(e: Exception)
		{
			link = null
			update { it.copy(tv = "ما قدرت أوصل لتطبيق ترجِم على التلفزيون ($tvHost)", tvOk = false) }
		}
	}

	private fun startSession()
	{
		if(!running) return
		try
		{
			val w = connectInfo.videoProfile.width
			val h = connectInfo.videoProfile.height
			val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
			r.setOnImageAvailableListener({ onImage(it) }, handler)
			reader = r
			Session.setAudioMuted(true)
			val s = Session(connectInfo, null, false)
			s.eventCallback = { event -> handler.post { onSessionEvent(event) } }
			s.start()
			s.setSurface(r.surface)
			session = s
			update { it.copy(console = "أتصل بالـPS5…", consoleOk = false) }
		}
		catch(e: Exception)
		{
			update { it.copy(console = "ما قدرت أبدأ الاتصال بالـPS5: ${e.message}", consoleOk = false) }
			handler.postDelayed({ startSession() }, 4000)
		}
	}

	private fun onSessionEvent(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> update { it.copy(console = "الـPS5 متصل، أنتظر الصورة…", consoleOk = true) }
			is QuitEvent ->
			{
				update { it.copy(console = "انقطع الاتصال بالـPS5 (${event.reason}). أعيد المحاولة…", consoleOk = false) }
				try { session?.dispose() } catch(e: Exception) { }
				session = null
				reader?.close()
				reader = null
				link?.clear()
				up = false
				if(running) handler.postDelayed({ startSession() }, 3000)
			}
			else -> { }
		}
	}

	// -- per frame -------------------------------------------------------------------

	private fun onImage(r: ImageReader)
	{
		val image = try { r.acquireLatestImage() } catch(e: Exception) { null } ?: return
		try
		{
			val now = SystemClock.elapsedRealtime()
			frameCount++
			fpsCount++
			if(now - fpsAt >= 1000)
			{
				val fps = fpsCount
				fpsCount = 0
				fpsAt = now
				update { it.copy(frames = frameCount, fps = fps, console = if(it.consoleOk) "الـPS5 متصل · الصورة توصل" else it.console) }
				if(link?.connected != true && now - lastTvTry > 3000) connectTv()
			}
			if(up && now - lastKeep >= 250)
			{
				link?.keep(listOf(LAYER))
				lastKeep = now
			}
			if(now - lastOcr >= OCR_EVERY_MS)
			{
				lastOcr = now
				read(image, now)
			}
		}
		catch(e: Exception)
		{
			Log.e(TAG, "frame failed", e)
		}
		finally
		{
			image.close()
		}
	}

	/** Read the subtitle area of this frame and act on what is there. */
	private fun read(image: Image, now: Long)
	{
		val w = image.width
		val h = image.height
		val left = (w * ROI_LEFT).toInt() and 1.inv()
		val top = (h * ROI_TOP).toInt() and 1.inv()
		val cw = ((w * ROI_RIGHT).toInt() - left) and 1.inv()
		val ch = (h - top) and 1.inv()

		// A grey NV21 copy of the area: the brightness plane as it is, neutral colour.
		val size = cw * ch * 3 / 2
		val buf = nv21?.takeIf { it.size == size } ?: ByteArray(size).also { nv21 = it; java.util.Arrays.fill(it, cw * ch, size, 128.toByte()) }
		val plane = image.planes[0]
		val src = plane.buffer
		val stride = plane.rowStride
		for(row in 0 until ch)
		{
			src.position((top + row) * stride + left)
			src.get(buf, row * cw, cw)
		}
		val t0 = SystemClock.elapsedRealtime()
		val result = Tasks.await(recognizer.process(InputImage.fromByteArray(buf, cw, ch, 0, InputImage.IMAGE_FORMAT_NV21)), 2, TimeUnit.SECONDS)

		// The subtitle is the block with the most letters that looks like a sentence.
		var bestText = ""
		var bestBox: Rect? = null
		var lineHeight = 0
		for(block in result.textBlocks)
		{
			val text = block.lines.joinToString(" ") { it.text.trim() }.trim()
			val box = block.boundingBox ?: continue
			if(!looksLikeSpeech(text)) continue
			if(text.length > bestText.length)
			{
				bestText = text
				bestBox = box
				lineHeight = block.lines.mapNotNull { it.boundingBox?.height() }.average().toInt()
			}
		}

		if(bestBox == null)
		{
			candidate = ""
			candidateCount = 0
			if(up && ++misses >= 2 && now >= shownAt + readMs) clearTv()
			return
		}
		misses = 0
		val key = normalise(bestText)
		if(similar(key, normalise(shown)))
		{
			return  // still the line that is already translated on the TV
		}
		if(similar(key, normalise(candidate))) candidateCount++ else { candidate = bestText; candidateCount = 1 }
		// Translate at first sight so the Arabic is ready by the time the line is confirmed.
		val arabic = translate(candidate) ?: return
		if(candidateCount < 2) return

		val box = Rect(bestBox.left + left, bestBox.top + top, bestBox.right + left, bestBox.bottom + top)
		val layer = render(arabic, box, lineHeight, w, h) ?: return
		if(link?.show(listOf(layer)) == true)
		{
			up = true
			shown = candidate
			shownAt = now
			lastKeep = now
			readMs = min(6000L, max(1600L, 900L + 65L * arabic.length))
			val took = (SystemClock.elapsedRealtime() - t0).toInt()
			update { it.copy(lines = it.lines + 1, english = shown, arabic = arabic, lastMs = took) }
		}
	}

	private fun clearTv()
	{
		link?.clear()
		up = false
		shown = ""
	}

	// -- text ------------------------------------------------------------------------

	private fun looksLikeSpeech(text: String): Boolean
	{
		val letters = text.count { it in 'a'..'z' || it in 'A'..'Z' }
		if(letters < 6 || letters < text.length * 0.6) return false
		if(text.count { it == ' ' } < 1) return false
		val upper = text.count { it in 'A'..'Z' }
		return upper < letters * 0.8 || text.length > 24  // short all-caps strings are interface labels
	}

	private fun normalise(text: String) = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

	/** The same line read twice rarely matches letter for letter; allow a few differences. */
	private fun similar(a: String, b: String): Boolean
	{
		if(a.isEmpty() || b.isEmpty()) return false
		if(a == b) return true
		if(kotlin.math.abs(a.length - b.length) > max(2, a.length / 10)) return false
		var prev = IntArray(b.length + 1) { it }
		for(i in 1..a.length)
		{
			val cur = IntArray(b.length + 1)
			cur[0] = i
			for(j in 1..b.length)
				cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if(a[i - 1] == b[j - 1]) 0 else 1)
			prev = cur
		}
		return prev[b.length] <= max(1, a.length / 12)
	}

	private fun translate(english: String): String?
	{
		val key = english.trim()
		cache[key]?.let { return it }
		if(!translatorReady) return null
		return try
		{
			val arabic = Tasks.await(translator.translate(key), 3, TimeUnit.SECONDS).trim()
			if(arabic.isEmpty()) null else arabic.also { if(cache.size > 2000) cache.clear(); cache[key] = it }
		}
		catch(e: Exception)
		{
			Log.w(TAG, "translation failed", e)
			null
		}
	}

	// -- drawing -----------------------------------------------------------------------

	/**
	 * The Arabic on a dark rounded plate that covers the English line, in the TV's
	 * overlay pixels. Android shapes and orders Arabic by itself.
	 */
	private fun render(arabic: String, english: Rect, lineHeight: Int, frameW: Int, frameH: Int): TvLink.Layer?
	{
		val l = link ?: return null
		val sx = l.width.toFloat() / frameW
		val sy = l.height.toFloat() / frameH
		val box = RectF(english.left * sx, english.top * sy, english.right * sx, english.bottom * sy)
		val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
			typeface = this@TarjimEngine.typeface
			color = Color.rgb(242, 247, 241)
			textSize = (lineHeight * sy * 1.5f).coerceIn(34f, 64f)
			setShadowLayer(4f, 2f, 2f, Color.BLACK)
		}
		val pad = 22f
		val maxWidth = (l.width * 0.86f).toInt()
		val wanted = max(box.width().toInt(), min(maxWidth, (paint.measureText(arabic) + 2).toInt()))
		val layout = StaticLayout.Builder.obtain(arabic, 0, arabic.length, paint, min(maxWidth, wanted))
			.setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
		val textW = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
		val w = (max(box.width(), textW) + pad * 2).toInt().coerceAtMost(1920)
		val hgt = (max(box.height(), layout.height.toFloat()) + pad * 1.4f).toInt().coerceAtMost(480)
		val cx = box.centerX()
		val x = (cx - w / 2f).toInt().coerceIn(0, l.width - w)
		val y = (box.bottom + pad * 0.7f - hgt).toInt().coerceIn(0, l.height - hgt)

		val bitmap = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 8, 9, 12) }
		canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), hgt.toFloat()), 18f, 18f, plate)
		canvas.save()
		canvas.translate((w - layout.width) / 2f, (hgt - layout.height) / 2f)
		layout.draw(canvas)
		canvas.restore()
		val out = ByteArrayOutputStream(64 * 1024)
		bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
		bitmap.recycle()
		return TvLink.Layer(LAYER, out.toByteArray(), x, y, w, hgt)
	}

	companion object
	{
		private const val TAG = "Tarjim"
		private const val LAYER = "sub"
		private const val OCR_EVERY_MS = 110L
		private const val ROI_TOP = 0.66f
		private const val ROI_LEFT = 0.06f
		private const val ROI_RIGHT = 0.94f
	}
}
