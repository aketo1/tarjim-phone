package com.metallic.chiaki.tarjim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.PixelCopy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.Event
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tarjim on the phone, the whole loop:
 *
 *   PS5 picture (Remote Play, receive only) -> ImageReader -> copy the subtitle area
 *   -> read it (ML Kit text recognition) -> translate on the device (ML Kit), with the
 *   game's names protected -> draw the Arabic -> send it to the TV overlay app.
 *
 * It never sends controller input: [Session.setControllerState] is not called anywhere
 * in Tarjim mode, and the session's sound is muted.
 */
class TarjimEngine(private val context: Context, private val connectInfo: ConnectInfo?, private val tvHost: String,
	private val store: Store, private val profile: String)
{
	data class Status(
		val console: String = "أتصل بالـPS5…", val consoleOk: Boolean = false,
		val tv: String = "أتصل بالتلفزيون…", val tvOk: Boolean = false,
		val translator: String = "أجهّز المترجم…", val translatorOk: Boolean = false,
		val frames: Int = 0, val fps: Int = 0, val lines: Int = 0, val lastMs: Int = 0, val error: String = "")

	class Line(val english: String, val arabic: String, val time: Long)

	@Volatile var status = Status(); private set
	var onStatus: ((Status) -> Unit)? = null
	var onLine: ((Line) -> Unit)? = null
	@Volatile var look = Look(store.load(profile))

	private val worker = HandlerThread("tarjim-engine").apply { start() }
	private val handler = Handler(worker.looper)
	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(
		TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.ARABIC).build())
	private val translating = Executors.newSingleThreadExecutor()
	private val wanted = AtomicReference("")
	private val cache = HashMap<String, String>()
	private val names = Names()

	private var reader: ImageReader? = null
	private var session: Session? = null
	private var link: TvLink? = null
	@Volatile private var running = false
	@Volatile private var translatorReady = false

	private var frameW = 1920
	private var frameH = 1080
	private var area: Bitmap? = null
	private var copying = false
	private var copyFailures = 0

	// the line being watched, and the line on the TV
	private var candidate = ""
	private var candidateCount = 0
	private var candidateAt = 0L
	private var candidateBox = Rect()
	private var candidateLine = 0
	private var shown = ""
	private var shownAt = 0L
	private var readMs = 0L
	private var up = false
	private var misses = 0

	private var lastOcr = 0L
	private var lastKeep = 0L
	private var lastTvTry = 0L
	private var lastFrameAt = 0L
	private var frameCount = 0
	private var fpsCount = 0
	private var fpsAt = 0L
	private var ocrTotal = 0L
	private var ocrCount = 0
	private var lastMtMs = 0L

	private fun update(change: (Status) -> Status)
	{
		status = change(status)
		onStatus?.invoke(status)
	}

	/** Xbox: the picture arrives from the page (WebRTC in the WebView) instead of a Remote Play session. */
	val xbox get() = connectInfo == null
	var onRestartXbox: (() -> Unit)? = null

	/**
	 * One picture of the Xbox stream's reading area from the page, as JPEG. `x`/`y`: where the
	 * area sits in the full frame, `fw`/`fh`: the full frame's size (already scaled to 1080 lines).
	 */
	fun feedJpeg(data: ByteArray, x: Int, y: Int, fw: Int, fh: Int): Boolean
	{
		if(!running || !xbox) return false
		val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return false
		handler.post {
			try
			{
				val now = SystemClock.elapsedRealtime()
				frameW = fw
				frameH = fh
				lastFrameAt = now
				frameCount++
				fpsCount++
				if(now - fpsAt >= 1000)
				{
					val fps = fpsCount
					fpsCount = 0
					fpsAt = now
					update { it.copy(frames = frameCount, fps = fps, consoleOk = true, console = "متصل · الصورة توصل") }
				}
				if(up && now - lastKeep >= 250)
				{
					link?.keep(listOf(LAYER))
					lastKeep = now
				}
				read(bitmap, x, y, now)
			}
			catch(e: Exception)
			{
				Log.e(TAG, "xbox frame failed", e)
			}
			finally
			{
				bitmap.recycle()
			}
		}
		return true
	}

	/** What the page says about the Xbox stream ("connected", "failed", "closed"). */
	fun xboxState(state: String, error: String)
	{
		if(!xbox) return
		when(state)
		{
			"connected" -> update { it.copy(console = "متصل، أنتظر الصورة…") }
			"failed", "closed" -> update { it.copy(console = error.ifEmpty { "انقطع اتصال الإكس بوكس. أعيد المحاولة…" }, consoleOk = false, fps = 0) }
		}
	}

	fun start()
	{
		running = true
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener {
				translating.execute {
					names.learn()
					translatorReady = true
					update { it.copy(translator = "المترجم جاهز", translatorOk = true) }
				}
			}
			.addOnFailureListener { e -> update { it.copy(translator = "ما قدرت أنزّل ملف الترجمة (يحتاج إنترنت أول مرة)", error = e.message ?: "") } }
		handler.post { connectTv() }
		if(xbox)
		{
			lastFrameAt = SystemClock.elapsedRealtime()
			update { it.copy(console = "أتصل بالإكس بوكس…") }
		}
		else handler.post { startSession() }
		handler.postDelayed(watchdog, 1000)
	}

	fun stop()
	{
		running = false
		handler.removeCallbacks(watchdog)
		handler.post {
			try { session?.stop(); session?.dispose() } catch(e: Exception) { Log.w(TAG, "session stop", e) }
			session = null
			link?.close()
			link = null
			reader?.close()
			reader = null
			translating.shutdownNow()
			recognizer.close()
			translator.close()
			worker.quitSafely()
		}
	}

	/** New look or names from the settings screen: use them from the next line on. */
	fun reload()
	{
		look = Look(store.load(profile))
		handler.post { cache.clear() }
	}

	/** Show one sample line on the TV for a few seconds with the current look (the settings' "try on TV"). */
	fun tryOnTv(arabic: String)
	{
		handler.post {
			val l = link ?: return@post
			val box = Rect((frameW * 0.3f).toInt(), (frameH * 0.86f).toInt(), (frameW * 0.7f).toInt(), (frameH * 0.9f).toInt())
			if(l.show(listOf(render(arabic, box, 34, frameW, frameH, l.width, l.height, look, typeface(look.font)))))
			{
				up = true
				shown = ""
				shownAt = SystemClock.elapsedRealtime()
				readMs = 6000
				lastKeep = shownAt
				handler.postDelayed({ if(shown.isEmpty() && up) clearTv() }, 6000)
			}
		}
	}

	/** The newest PS5 picture, small, for the settings' reading-area editor. Null when no picture is flowing. */
	fun frameJpeg(): ByteArray?
	{
		val r = reader ?: return null
		if(!status.consoleOk) return null
		val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
		val wait = java.util.concurrent.CountDownLatch(1)
		var ok = false
		try
		{
			PixelCopy.request(r.surface, bitmap, { result -> ok = result == PixelCopy.SUCCESS; wait.countDown() }, handler)
			if(!wait.await(1500, TimeUnit.MILLISECONDS) || !ok) return null
			val out = ByteArrayOutputStream()
			bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
			return out.toByteArray()
		}
		catch(e: Exception)
		{
			return null
		}
		finally
		{
			bitmap.recycle()
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
			update { it.copy(tv = "متصل · جاهز", tvOk = true) }
		}
		catch(e: Exception)
		{
			link = null
			update { it.copy(tv = "ما قدرت أوصل لتطبيق ترجِم على التلفزيون ($tvHost)", tvOk = false) }
		}
	}

	private fun startSession()
	{
		val connectInfo = connectInfo ?: return
		if(!running || session != null) return
		try
		{
			val w = connectInfo.videoProfile.width
			val h = connectInfo.videoProfile.height
			// The decoder's own buffer format: on some phones its pixels cannot be mapped for reading
			// (getPlanes aborts), so frames are only counted here and the subtitle area is copied out
			// through the graphics processor with PixelCopy, which also converts it to RGB.
			val r = ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 3)
			frameW = w
			frameH = h
			r.setOnImageAvailableListener({ onImage(it) }, handler)
			reader = r
			Session.setAudioMuted(true)
			val s = Session(connectInfo, null, false)
			s.eventCallback = { event -> handler.post { onSessionEvent(event) } }
			s.start()
			s.setSurface(r.surface)
			session = s
			lastFrameAt = SystemClock.elapsedRealtime()
			update { it.copy(console = "أتصل بالـPS5…", consoleOk = false, fps = 0) }
		}
		catch(e: Exception)
		{
			update { it.copy(console = "ما قدرت أبدأ الاتصال بالـPS5: ${e.message}", consoleOk = false) }
			handler.postDelayed({ startSession() }, 4000)
		}
	}

	private fun dropSession(reason: String)
	{
		update { it.copy(console = reason, consoleOk = false, fps = 0) }
		val old = session
		session = null
		reader?.close()
		reader = null
		link?.clear()
		up = false
		shown = ""
		// stop() waits for the native threads; do it off this thread so the watchdog keeps ticking
		Thread({ try { old?.stop(); old?.dispose() } catch(e: Exception) { Log.w(TAG, "dispose", e) } }, "tarjim-dispose").start()
		if(running) handler.postDelayed({ startSession() }, 3000)
	}

	private fun onSessionEvent(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> update { it.copy(console = "متصل، أنتظر الصورة…") }
			is QuitEvent -> if(session != null) dropSession("انقطع الاتصال بالـPS5 (${event.reason}). أعيد المحاولة…")
			else -> { }
		}
	}

	/** The truth about the picture: no frame for a few seconds means the copy is gone, whatever the session says. */
	private val watchdog = object : Runnable
	{
		override fun run()
		{
			if(!running) return
			val now = SystemClock.elapsedRealtime()
			if(now - fpsAt > 1500 && status.fps != 0) update { it.copy(fps = 0) }
			if(session != null && now - lastFrameAt > 8000)
				dropSession("الصورة وقفت. أعيد الاتصال بالـPS5…")
			if(xbox && now - lastFrameAt > 25000)
			{
				// no picture from the page for a while: ask it to start the stream again
				lastFrameAt = now
				link?.clear()
				up = false
				shown = ""
				update { it.copy(console = "الصورة وقفت. أعيد الاتصال بالإكس بوكس…", consoleOk = false, fps = 0) }
				onRestartXbox?.invoke()
			}
			if(link?.connected != true)
			{
				if(status.tvOk) update { it.copy(tv = "انقطع الاتصال بالتلفزيون. أعيد المحاولة…", tvOk = false) }
				if(now - lastTvTry > 3000) connectTv()
			}
			handler.postDelayed(this, 1000)
		}
	}

	// -- per frame -------------------------------------------------------------------

	private fun onImage(r: ImageReader)
	{
		val image = try { r.acquireLatestImage() } catch(e: Exception) { null } ?: return
		image.close()  // only its arrival matters; the pixels are fetched with PixelCopy
		try
		{
			val now = SystemClock.elapsedRealtime()
			lastFrameAt = now
			frameCount++
			fpsCount++
			if(now - fpsAt >= 1000)
			{
				val fps = fpsCount
				fpsCount = 0
				fpsAt = now
				update { it.copy(frames = frameCount, fps = fps, consoleOk = true, console = "متصل · الصورة توصل") }
			}
			if(up && now - lastKeep >= 250)
			{
				link?.keep(listOf(LAYER))
				lastKeep = now
			}
			if(now - lastOcr >= OCR_EVERY_MS && !copying)
			{
				lastOcr = now
				copyArea(r, now)
			}
		}
		catch(e: Exception)
		{
			Log.e(TAG, "frame failed", e)
		}
	}

	/** Copy the subtitle area of the newest frame into a bitmap, then read it. */
	private fun copyArea(r: ImageReader, now: Long)
	{
		val lk = look
		val left = (frameW * lk.left).toInt()
		val top = (frameH * lk.top).toInt()
		val cw = (frameW * lk.right).toInt() - left
		val ch = (frameH * lk.bottom).toInt() - top
		if(cw < 64 || ch < 32) return
		val bitmap = area?.takeIf { it.width == cw && it.height == ch }
			?: Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888).also { area = it }
		copying = true
		try
		{
			PixelCopy.request(r.surface, Rect(left, top, left + cw, top + ch), bitmap, { result ->
				try
				{
					if(result == PixelCopy.SUCCESS && running) read(bitmap, left, top, now)
					else if(result != PixelCopy.SUCCESS) copyFailures++
				}
				catch(e: Exception)
				{
					Log.e(TAG, "reading failed", e)
				}
				finally
				{
					copying = false
				}
			}, handler)
		}
		catch(e: Exception)
		{
			copying = false
			copyFailures++
			Log.w(TAG, "copy request failed", e)
		}
		if(copyFailures == 20)
			update { it.copy(error = "ما قدرت أقرأ الصورة من هذا الجوال") }
	}

	private class Found(val text: String, val box: Rect, val lineHeight: Int)

	/**
	 * The subtitle among everything the recogniser saw. Game dialogue sits in the middle of
	 * the screen; counters, button hints and map labels sit at the sides. So: keep the lines
	 * centred on the screen, join the ones stacked on each other, take the longest group.
	 */
	private fun pick(result: Text, areaWidth: Int): Found?
	{
		val centre = areaWidth / 2
		val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
			val box = line.boundingBox ?: return@mapNotNull null
			val text = line.text.trim()
			val letters = text.count { it in 'a'..'z' || it in 'A'..'Z' }
			val centred = abs(box.centerX() - centre) < areaWidth * 0.16f
			if(letters < 2 || letters < text.length * 0.55 || !centred) null else Pair(text, box)
		}.sortedBy { it.second.top }
		if(lines.isEmpty()) return null
		val groups = ArrayList<ArrayList<Pair<String, Rect>>>()
		for(line in lines)
		{
			val last = groups.lastOrNull()?.last()
			if(last != null && line.second.top - last.second.bottom < last.second.height() * 0.9f) groups.last().add(line)
			else groups.add(arrayListOf(line))
		}
		val best = groups.maxByOrNull { g -> g.sumOf { it.first.length } } ?: return null
		val text = best.joinToString(" ") { it.first }
		if(text.count { it in 'a'..'z' || it in 'A'..'Z' } < 3) return null
		val box = Rect(best.minOf { it.second.left }, best.minOf { it.second.top }, best.maxOf { it.second.right }, best.maxOf { it.second.bottom })
		return Found(text, box, best.map { it.second.height() }.average().toInt())
	}

	/** Read the subtitle area and act on what is there. `left`/`top`: where the area sits in the frame. */
	private fun read(bitmap: Bitmap, left: Int, top: Int, now: Long)
	{
		val t0 = SystemClock.elapsedRealtime()
		val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 2, TimeUnit.SECONDS)
		ocrTotal += SystemClock.elapsedRealtime() - t0
		ocrCount++
		val found = pick(result, bitmap.width)

		if(found == null)
		{
			candidate = ""
			candidateCount = 0
			if(up && ++misses >= 2 && now >= shownAt + readMs) clearTv()
			return
		}
		misses = 0
		val key = normalise(found.text)
		if(similar(key, normalise(shown))) return  // still the line that is already translated on the TV
		if(similar(key, normalise(candidate))) candidateCount++
		else
		{
			candidate = found.text
			candidateCount = 1
			candidateAt = t0
			requestTranslation(found.text)  // starts now, on its own thread, while we keep watching the line
		}
		candidateBox = Rect(found.box.left + left, found.box.top + top, found.box.right + left, found.box.bottom + top)
		candidateLine = found.lineHeight
		if(confirmed()) showIfReady()
	}

	/** Read the same twice, and for games that type their text letter by letter also unchanged for the settle time. */
	private fun confirmed() = candidateCount >= 2 && SystemClock.elapsedRealtime() - candidateAt >= look.settleMs

	private fun requestTranslation(english: String)
	{
		val key = english.trim()
		if(cache.containsKey(key) || !translatorReady) return
		val fixed = store.fix(profile, key)
		if(fixed != null) { cache[key] = fixed; return }
		wanted.set(key)
		val lk = look
		translating.execute {
			if(wanted.get() != key) return@execute  // the line changed while this waited its turn
			val t = SystemClock.elapsedRealtime()
			val arabic = try { names.translate(key, lk) } catch(e: Exception) { Log.w(TAG, "translation failed", e); "" }
			val took = SystemClock.elapsedRealtime() - t
			if(arabic.isEmpty()) return@execute
			handler.post {
				if(cache.size > 2000) cache.clear()
				cache[key] = arabic
				lastMtMs = took
				if(confirmed()) showIfReady()  // the line was confirmed before its Arabic arrived
			}
		}
	}

	private fun showIfReady()
	{
		val english = candidate.trim()
		if(english.isEmpty() || similar(normalise(english), normalise(shown))) return
		val arabic = cache[english] ?: return
		val l = link ?: return
		val t = SystemClock.elapsedRealtime()
		val layer = render(arabic, candidateBox, candidateLine, frameW, frameH, l.width, l.height, look, typeface(look.font))
		val renderMs = SystemClock.elapsedRealtime() - t
		if(!l.show(listOf(layer))) return
		val now = SystemClock.elapsedRealtime()
		up = true
		shown = english
		shownAt = now
		lastKeep = now
		readMs = (min(6000L, max(1600L, 900L + 65L * arabic.length)) * look.readScale).toLong()
		val total = (now - candidateAt).toInt()
		val ocrAvg = if(ocrCount > 0) ocrTotal / ocrCount else 0
		Log.i(TAG, "line in $total ms (ocr avg $ocrAvg, mt $lastMtMs, render $renderMs, png ${layer.png.size / 1024} KB): $english")
		update { it.copy(lines = it.lines + 1, lastMs = total) }
		onLine?.invoke(Line(english, arabic, System.currentTimeMillis()))
	}

	private fun clearTv()
	{
		link?.clear()
		up = false
		shown = ""
	}

	// -- text ------------------------------------------------------------------------

	private fun normalise(text: String) = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

	/** The same line read twice rarely matches letter for letter; allow a few differences. */
	private fun similar(a: String, b: String): Boolean
	{
		if(a.isEmpty() || b.isEmpty()) return false
		if(a == b) return true
		if(abs(a.length - b.length) > max(2, a.length / 10)) return false
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

	/**
	 * Translation with the game's names kept right. The on-device translator treats some
	 * names as words ("Dutch" becomes "the Dutch woman"). A name from the glossary is
	 * replaced in the English by a stand-in name the translator writes reliably, and the
	 * agreed Arabic spelling is put back afterwards. Women's names get a female stand-in
	 * so the verbs agree.
	 */
	private inner class Names
	{
		private val male = ArrayList<Pair<String, String>>()
		private val female = ArrayList<Pair<String, String>>()

		private fun raw(text: String): String = Tasks.await(translator.translate(text), 3, TimeUnit.SECONDS).trim()

		/** Find, once, the stand-ins this translator writes the same way in every sentence. */
		fun learn()
		{
			fun usable(name: String): String?
			{
				val alone = raw("$name.").trim('.', ' ', '،', '(', ')')
				if(alone.isEmpty() || alone.any { it in 'a'..'z' || it in 'A'..'Z' }) return null
				val probes = listOf("Hello, $name.", "I told $name to wait.", "$name is thinking about it.", "We got $name.")
				return if(probes.all { raw(it).contains(alone) }) alone else null
			}
			try
			{
				for(n in listOf("Edward", "Harold", "Leonard", "Jonathan", "Walter", "Raymond")) usable(n)?.let { male.add(Pair(n, it)) }
				for(n in listOf("Victoria", "Catherine", "Elizabeth", "Margaret")) usable(n)?.let { female.add(Pair(n, it)) }
				Log.i(TAG, "stand-in names: $male $female")
			}
			catch(e: Exception)
			{
				Log.w(TAG, "could not learn stand-in names", e)
			}
		}

		private fun rx(name: String) = Regex("(?<![A-Za-z])${Regex.escape(name)}(?![A-Za-z])", RegexOption.IGNORE_CASE)

		fun translate(english: String, lk: Look): String
		{
			// The game's names in this line, longest first. A name found only inside a longer one
			// ("Arthur" in "Arthur Morgan") is part of that one and does not count.
			val matched = ArrayList<Triple<String, String, Regex>>()
			val masked = StringBuilder(english)
			for(name in lk.names)
			{
				val hits = name.third.findAll(masked).toList()
				if(hits.isEmpty()) continue
				matched.add(name)
				for(hit in hits) for(i in hit.range) masked.setCharAt(i, '\u0000')
			}
			var source = english
			val swaps = ArrayList<Pair<String, String>>()
			val freeMale = male.filter { !rx(it.first).containsMatchIn(english) }.toMutableList()
			val freeFemale = female.filter { !rx(it.first).containsMatchIn(english) }.toMutableList()
			for((en, ar, pattern) in matched)
			{
				val pool = if(en in lk.female && freeFemale.isNotEmpty()) freeFemale else freeMale
				if(pool.isEmpty() || !pattern.containsMatchIn(source)) continue
				val standIn = pool.removeAt(0)
				source = pattern.replace(source, Regex.escapeReplacement(standIn.first))
				swaps.add(Pair(standIn.second, ar))
			}
			var arabic = raw(source)
			if(swaps.isNotEmpty() && swaps.all { arabic.contains(it.first) })
				for(s in swaps) arabic = arabic.replace(s.first, s.second)
			else if(swaps.isNotEmpty())
				arabic = raw(english)  // a stand-in got lost: plain translation
			// names the translator left in Latin letters
			for((_, ar, pattern) in matched) arabic = pattern.replace(arabic, Regex.escapeReplacement(ar))
			return clean(arabic)
		}

		/** Arabic punctuation, and none of the brackets the translator likes to add. */
		private fun clean(arabic: String) = arabic.replace("(", "").replace(")", "").replace(",", "،").replace("?", "؟").replace(";", "؛")
			.replace(Regex("\\s+([،؛؟.!])"), "$1").trim()
	}

	private val faces = HashMap<String, Typeface>()
	private fun typeface(font: String) = faces.getOrPut(font) { loadTypeface(context, font) }

	companion object
	{
		private const val TAG = "Tarjim"
		private const val LAYER = "sub"
		private const val OCR_EVERY_MS = 45L  // in practice: as fast as copy + read allow

		fun loadTypeface(context: Context, font: String): Typeface =
			try { Typeface.createFromAsset(context.assets, "fonts/" + (Look.FONTS[font] ?: Look.FONTS.getValue("harmattan"))) }
			catch(e: Exception) { Typeface.DEFAULT }

		/**
		 * The Arabic as one picture in the TV's overlay pixels: an optional dark rounded plate
		 * that covers the English line, and the text with its effect. Android shapes Arabic itself.
		 */
		fun render(arabic: String, english: Rect, lineHeight: Int, frameW: Int, frameH: Int, tvW: Int, tvH: Int, look: Look, face: Typeface): TvLink.Layer
		{
			val sx = tvW.toFloat() / frameW
			val sy = tvH.toFloat() / frameH
			val box = RectF(english.left * sx, english.top * sy, english.right * sx, english.bottom * sy)
			val size = if(look.fontSize > 0) look.fontSize * sy * 1.2f else (lineHeight * sy * 1.5f).coerceIn(34f, 64f)
			val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
				typeface = face
				color = look.textColor
				textSize = size
				if(look.effect == "shadow") setShadowLayer(4f, 2f, 2f, Color.BLACK)
			}
			val pad = 22f
			val maxWidth = (tvW * 0.86f).toInt()
			val wanted = max(box.width().toInt(), min(maxWidth, (paint.measureText(arabic) + 4).toInt()))
			val layout = StaticLayout.Builder.obtain(arabic, 0, arabic.length, paint, min(maxWidth, wanted))
				.setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
			val textW = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
			val w = (max(box.width(), textW) + pad * 2).toInt().coerceIn(8, min(1920, tvW))
			val h = (max(box.height(), layout.height.toFloat()) + pad * 1.4f).toInt().coerceIn(8, 480)
			val x = (box.centerX() - w / 2f).toInt().coerceIn(0, tvW - w)
			val y = (box.bottom + pad * 0.7f - h + look.yOffset * sy).toInt().coerceIn(0, tvH - h)

			val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
			val canvas = Canvas(bitmap)
			if(look.background == "plate")
			{
				val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((look.plateOpacity * 255).toInt(), 8, 9, 12) }
				canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 18f, 18f, plate)
			}
			canvas.save()
			canvas.translate((w - layout.width) / 2f, (h - layout.height) / 2f)
			if(look.effect == "outline")
			{
				val stroke = TextPaint(paint).apply { style = Paint.Style.STROKE; strokeWidth = size * 0.13f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND }
				StaticLayout.Builder.obtain(arabic, 0, arabic.length, stroke, layout.width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build().draw(canvas)
			}
			layout.draw(canvas)
			canvas.restore()
			val out = ByteArrayOutputStream(64 * 1024)
			bitmap.compress(Bitmap.CompressFormat.PNG, 0, out)
			bitmap.recycle()
			return TvLink.Layer(LAYER, out.toByteArray(), x, y, w, h)
		}

		/** The settings screen's preview: a plain slate scene with one English line and the Arabic drawn over it. */
		fun preview(context: Context, look: Look, arabic: String): ByteArray
		{
			val w = 1080
			val h = 290
			val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
			val canvas = Canvas(bitmap)
			val sky = Paint().apply {
				shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.rgb(46, 70, 80), Color.rgb(36, 54, 64), Color.rgb(26, 36, 44), Color.rgb(18, 22, 28)),
					floatArrayOf(0f, 0.35f, 0.62f, 1f), Shader.TileMode.CLAMP)
			}
			canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sky)
			val latin = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.AUTO_COLOR; textSize = 30f; setShadowLayer(4f, 2f, 2f, Color.BLACK) }
			val line = "We should leave before the storm gets here."
			val tw = latin.measureText(line)
			val box = Rect(((w - tw) / 2).toInt(), 196, ((w + tw) / 2).toInt(), 232)
			canvas.drawText(line, box.left.toFloat(), 224f, latin)
			val layer = render(arabic.ifBlank { "يجب أن نغادر قبل وصول العاصفة." }, box, 28, w, h, w, h, look, loadTypeface(context, look.font))
			val overlay = BitmapFactory.decodeByteArray(layer.png, 0, layer.png.size)
			canvas.drawBitmap(overlay, layer.x.toFloat(), layer.y.toFloat(), null)
			val out = ByteArrayOutputStream()
			bitmap.compress(Bitmap.CompressFormat.PNG, 0, out)
			return out.toByteArray()
		}
	}
}
