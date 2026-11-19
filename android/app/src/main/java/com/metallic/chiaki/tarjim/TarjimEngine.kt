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
	/** The game's series, for its own words (Terms). */
	@Volatile private var series = Terms.seriesOf(profile, store.load(profile).optString("title", ""))

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
			.addOnFailureListener { e ->
				Log.w(TAG, "translator download failed", e)
				update { it.copy(translator = "ما قدرت أنزّل ملف الترجمة (يحتاج إنترنت أول مرة)", error = "ما قدرت أنزّل ملف الترجمة. تأكد من الإنترنت أول مرة.") }
			}
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
		series = Terms.seriesOf(profile, store.load(profile).optString("title", ""))
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
			Log.w(TAG, "startSession failed", e)
			update { it.copy(console = "ما قدرت أبدأ الاتصال بالـPS5. تأكد إنه شغّال وعلى نفس الشبكة.", consoleOk = false) }
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
			is QuitEvent -> if(session != null)
			{
				Log.i(TAG, "session quit: ${event.reason}")
				dropSession("انقطع الاتصال بالـPS5. أعيد المحاولة…")
			}
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

		private fun rawOne(text: String): String = Tasks.await(translator.translate(text), 3, TimeUnit.SECONDS).trim()

		/** Split into sentences, check phrase memory, pre-process idioms/contractions, translate and rejoin. */
		private fun raw(text: String): String
		{
			val sents = splitSentences(text)
			val outs = sents.map { s ->
				phraseLookup(s) ?: run {
					val prep = preprocess(s)
					phraseLookup(prep) ?: rawOne(prep)
				}
			}
			return joinSentences(sents, outs)
		}

		/** Find, once, the stand-ins this translator writes the same way in every sentence. */
		fun learn()
		{
			fun usable(name: String): String?
			{
				val alone = rawOne("$name.").trim('.', ' ', '،', '(', ')')
				if(alone.isEmpty() || alone.any { it in 'a'..'z' || it in 'A'..'Z' }) return null
				val probes = listOf("Hello, $name.", "I told $name to wait.", "$name is thinking about it.", "We got $name.")
				return if(probes.all { rawOne(it).contains(alone) }) alone else null
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

		private val nameRxs = HashMap<String, Regex>()
		private val vocRxs = HashMap<String, Triple<Regex, Regex, Regex>>()

		private fun rx(name: String) = nameRxs.getOrPut(name) {
			Regex("(?<![A-Za-z])${Regex.escape(name)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
		}

		private fun isFemale(en: String, text: String, pattern: Regex, lk: Look): Boolean
		{
			if(en in lk.female || FEMALE_PREFIX.containsMatchIn(en)) return true
			val m = pattern.find(text) ?: return false
			return FEMALE_TITLE.containsMatchIn(text.substring(0, m.range.first))
		}

		private fun isVocative(text: String, en: String): Boolean
		{
			val (r1, r2, r3) = vocRxs.getOrPut(en) {
				val esc = Regex.escape(en)
				Triple(
					Regex("(?:^|[.!?…]\\s+|,\\s*)$esc\\s*[.!?…]+", RegexOption.IGNORE_CASE),
					Regex(",\\s*$esc\\s*[.!?…\"')\\]]*$", RegexOption.IGNORE_CASE),
					Regex("(?:^|[.!?…]\\s+)$esc\\s*,\\s*(?!and\\b)", RegexOption.IGNORE_CASE)
				)
			}
			return r1.containsMatchIn(text) || r2.containsMatchIn(text) || r3.containsMatchIn(text)
		}

		/** "Uncle, wake up" → «يا عم، استيقظ», not «العم، استيقظ»: a name or title written with
		 * «ال», spoken to at the start of the line (not a group: "Wolves, listen"). */
		private fun vocativeTitle(arabic: String, english: String, matched: List<Triple<String, String, Regex>>): String
		{
			for((en, ar, _) in matched)
			{
				if(!ar.startsWith("ال") || ' ' in ar || (en.endsWith("s") && !en.endsWith("ss"))) continue
				if(!Regex("^[\\s\"'-]*${Regex.escape(en)}\\s*,", RegexOption.IGNORE_CASE).containsMatchIn(english)) continue
				// the translator sometimes brackets the name: «(العم)، انهض»
				val m = Regex("^\\s*[(«\"]?${Regex.escape(ar)}[)»\"]?(\\s*[،,])").find(arabic) ?: continue
				return "يا " + ar.substring(2) + m.groupValues[1] + arabic.substring(m.range.last + 1)
			}
			return arabic
		}

		fun translate(line: String, lk: Look): String
		{
			val gameSeries = series
			val english = Terms.rewrite(line, gameSeries)  // the game's own words in plain English
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
			var allFemale = matched.isNotEmpty()
			var anyFemale = false
			for((en, ar, pattern) in matched)
			{
				val fem = isFemale(en, english, pattern, lk)
				if(!fem) allFemale = false else anyFemale = true
				val pool = if(fem && freeFemale.isNotEmpty()) freeFemale else freeMale
				if(pool.isEmpty() || !pattern.containsMatchIn(source)) continue
				val standIn = pool.removeAt(0)
				source = pattern.replace(source, Regex.escapeReplacement(standIn.first))
				swaps.add(Pair(standIn.second, ar))
			}
			var arabic = raw(source)
			if(swaps.isNotEmpty())
			{
				var allFound = true
				var swapped = arabic
				for((proxyAr, ar) in swaps)
				{
					val alt = proxyAr.replace('إ', 'ا').replace('أ', 'ا')
					when
					{
						swapped.contains(proxyAr) -> swapped = swapped.replace(proxyAr, ar)
						swapped.contains(alt) -> swapped = swapped.replace(alt, ar)
						else -> allFound = false
					}
				}
				arabic = if(allFound) swapped else raw(english)
			}
			// names the translator left in Latin letters
			for((_, ar, pattern) in matched) arabic = pattern.replace(arabic, Regex.escapeReplacement(ar))
			arabic = Terms.commandsNotNouns(arabic, english)
			arabic = vocativeTitle(arabic, english, matched)
			if(allFemale)
			{
				if(matched.any { isVocative(english, it.first) })
				{
					arabic = Gender.feminize(arabic)  // every common command said to her
					for((rx, repl) in FEM_VOCATIVE_FIXES) arabic = rx.replace(arabic, repl)
				}
				else
				{
					// "Buy Maria a ring", "Don't you like Maria?": she is named, not spoken to, so the
					// one spoken to is a man by default
					for((rx, repl) in MASC_DEFAULT_FIXES) arabic = rx.replace(arabic, repl)
					arabic = Gender.masculinize(arabic)
				}
				if(matched.size >= 2)
					for((rx, repl) in FEM_PLURAL_FIXES) arabic = rx.replace(arabic, repl)
			}
			else if(!anyFemale && !FEMALE_EN_WORD.containsMatchIn(english))
			{
				// a man by name ("Javier, cover the left side") or nobody named: masculine commands
				for((rx, repl) in MASC_DEFAULT_FIXES) arabic = rx.replace(arabic, repl)
				arabic = Gender.masculinize(arabic)
			}
			arabic = Terms.fixArabic(arabic, english, gameSeries)
			if('"' !in english) arabic = arabic.replace("\"", "")
			return clean(arabic)
		}

		/** Arabic punctuation, orthography and leftover English cleanup. */
		private fun clean(arabic: String): String
		{
			var s = AR_INVISIBLE.replace(arabic, "")
			s = AR_SHORT_VOWELS.replace(s, "")
				.replace("(", "").replace(")", "").replace("[", "").replace("]", "")
				.trim('"', '\'', '«', '»', '“', '”', '‘', '’', ' ')
			s = s.replace("?", "؟").replace(";", "؛")
			if(',' in s) s = AR_COMMA.replace(s, "،")
			if(HAS_LATIN.containsMatchIn(s))
			{
				for((rx, repl) in LEFTOVER_WORDS) s = rx.replace(s, repl)
				s = AR_STRAY_LATIN_END.replace(s, "")
				s = AR_STRAY_LATIN_START.replace(s, "")
			}
			if('…' in s) s = AR_ELLIPSIS_CHAR.replace(s, "...")
			if(".." in s) s = AR_ELLIPSIS_DOTS.replace(s, "...")
			s = AR_DASH_PUNCT.replace(s, "$1")
			s = AR_LEAD_DASH.replace(s, "")
			if(':' in s) s = AR_TIME_COLON.replace(s, "$1:$2")
			s = AR_SPACE_BEFORE_PUNCT.replace(s, "$1")
			s = AR_DUP_PUNCT.replace(s, "$1")
			s = AR_PUNCT_NO_SPACE.replace(s, "$1 ")
			s = AR_WAW_SPACE.replace(s, "و")
			s = AR_YA_AL_TITLE.replace(s, "$1 $2")
			if("الال" in s) s = AR_ALAL.replace(s, "ال")
			for((rx, repl) in AR_ORTHO_FIXES) s = rx.replace(s, repl)
			return WS_RX.replace(s, " ").trim()
		}
	}

	private val faces = HashMap<String, Typeface>()
	private fun typeface(font: String) = faces.getOrPut(font) { loadTypeface(context, font) }

	companion object
	{
		private const val TAG = "Tarjim"
		private const val LAYER = "sub"
		private const val OCR_EVERY_MS = 45L  // in practice: as fast as copy + read allow

		private val WS_RX = Regex("\\s+")
		private val HAS_LATIN = Regex("[A-Za-z]")
		private val PHRASE_SPLIT_RX = Regex("^(.*?)([.!?…\"')\\]\\s]*)$")
		private val EN_LEAD_DASH = Regex("^[-–—]+\\s*")
		private val IM_IVE_RX = Regex("\\bI(m|ve)\\b")
		private val FEMALE_TITLE = Regex("\\b(?:Mrs|Miss|Ms|Lady|Madam|Sister|Aunt|Queen|Princess)\\.?\\s+$", RegexOption.IGNORE_CASE)
		private val FEMALE_PREFIX = Regex("^(?:Mrs|Miss|Ms|Lady|Madam|Sister|Aunt|Queen|Princess)\\b", RegexOption.IGNORE_CASE)
		private val FEMALE_EN_WORD = Regex("\\b(?:Mrs|Miss|Ms|Lady|Madam|Ma'am|Sister|Aunt|Queen|Princess|girl|woman|mother|mom|mama|daughter|wife|bride|niece|grandma|grandmother|she|her|herself|lass|gal|Victoria|Catherine|Elizabeth|Margaret)\\b", RegexOption.IGNORE_CASE)
		private val SENT_RX = Regex("(?<=[.!?…])[\"')\\]]*\\s+(?=[-\\u2013\\u2014\"'(\\[A-Za-z0-9])|\\s+(?=[-\\u2013\\u2014]\\s*[A-Z])|;\\s+(?=[A-Za-z\"'(])|\\s+(?:--|—)\\s+(?=[A-Za-z\"'(])|\\s+(?=[A-Z][A-Za-z.'\\-]{1,15}:\\s+[A-Z])")
		private val ABBREV_RX = Regex("(?:^|\\s)(?:Mr|Mrs|Ms|Dr|Prof|Rev|Fr|St|Jr|Sr|Mt|Lt|Sgt|Cpl|Pvt|Capt|Maj|Col|Gen|Cmdr|Adm|Gov|Sen|Rep|Pres|No|vs|etc|Bros|Co|Inc|Ltd|(?:[A-Z]\\.))+\\.?$", RegexOption.IGNORE_CASE)
		private val INLINE_SPEAKER = Regex("^([A-Z][A-Za-z.'\\-]{1,15}):\\s+(?=[A-Z\"'(])")
		private val STAGE_DIR = Regex("^\\s*[\\(\\[]\\s*(?:sighs?|groans?|laughs?|coughs?|gasps?|chuckles?|grunts?|panting|sniffs?|clears? throat|whistles?|screams?|cries|sobs?|mutters?|whispers?|yells?|shouts?)\\s*[\\)\\]]\\s*", RegexOption.IGNORE_CASE)
		private val STUTTER = Regex("\\b([A-Za-z])-\\1([a-z]{2,})\\b")
		private val ALL_CAPS_WORD = Regex("\\b([A-Z]{2,})\\b")
		private val ROMAN = setOf("II", "III", "IV", "VI", "VII", "VIII", "IX", "XI", "XII", "PS5", "PS4", "USB", "OK", "TV", "ID", "PC")

		private val AR_INVISIBLE = Regex("[\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff\\u2047\\ufffd]")
		private val AR_SHORT_VOWELS = Regex("[\\u064e\\u064f\\u0650\\u0652\\u0670]")
		private val AR_COMMA = Regex("(?<!\\d)\\s*,(?!\\d)")
		private val AR_STRAY_LATIN_END = Regex("(?:^|\\s)[a-zA-Z]{1,2}(?=\\s*$|[.!؟،])")
		private val AR_STRAY_LATIN_START = Regex("^[a-zA-Z]{1,2}\\s+(?=[؀-ۿ])")
		private val AR_ELLIPSIS_CHAR = Regex("…+")
		private val AR_ELLIPSIS_DOTS = Regex("\\.{2,}")
		private val AR_DASH_PUNCT = Regex("[-–—]+\\s*([.!؟،])")
		private val AR_LEAD_DASH = Regex("^[-–—]+\\s*(?=[؀-ۿ])")
		private val AR_TIME_COLON = Regex("(\\d)\\s*:\\s*(\\d)")
		private val AR_SPACE_BEFORE_PUNCT = Regex("\\s+([،؛؟.!:])")
		private val AR_DUP_PUNCT = Regex("([،؛؟!])\\1+")
		private val AR_PUNCT_NO_SPACE = Regex("([،؛؟!])(?=[؀-ۿ])")
		private val AR_WAW_SPACE = Regex("(?:^|(?<=[\\s،؛؟.!]))و\\s+(?=[؀-ۿ])")
		private val AR_YA_AL_TITLE = Regex("\\b(و?ف?يا) ال(عم|سيد|سيدة|آنسة)\\b")
		private val AR_ALAL = Regex("(?:^|(?<=[\\s،؛؟.!]))الال(?=[؀-ۿ])")

		private val WORD_REWRITES = mapOf(
			"dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
			"doesnt" to "doesn't", "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't",
			"werent" to "weren't", "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
			"wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
			"aint" to "ain't", "thats" to "that's", "whats" to "what's", "heres" to "here's",
			"theres" to "there's", "wheres" to "where's", "whos" to "who's", "youre" to "you're",
			"theyre" to "they're", "weve" to "we've", "theyve" to "they've", "youve" to "you've",
			"youd" to "you'd", "theyd" to "they'd", "youll" to "you'll", "theyll" to "they'll",
			"outta" to "out of", "gotta" to "have to", "gonna" to "going to", "wanna" to "want to",
			"dunno" to "do not know", "lemme" to "let me", "gimme" to "give me", "kinda" to "kind of",
			"sorta" to "sort of", "lotta" to "lot of", "c'mere" to "come here", "c'mon" to "come on",
			"y'all" to "all of you", "whatcha" to "what are you", "gotcha" to "got you",
			"betcha" to "bet you", "don'tcha" to "do not you", "cuz" to "because", "till" to "until",
			"dagnabbit" to "damn it", "dagnabbi" to "damn it", "goddammit" to "damn it", "dammit" to "damn it",
			"horsecrap" to "nonsense", "bullcrap" to "nonsense", "horseshit" to "nonsense",
			"stagecoach" to "carriage"
		)
		private val WORD_REWRITE_RX = Regex(
			"\\b(?:" + WORD_REWRITES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b",
			RegexOption.IGNORE_CASE
		)

		private val PHRASES = mapOf(
			"what" to "ماذا", "hm" to "همم", "hmm" to "همم", "hmmm" to "همم",
			"mm-hmm" to "أها", "uh-huh" to "أها", "check" to "تم",
			"whoa" to "تمهّل", "woah" to "تمهّل", "ouch" to "آخ",
			"damn" to "اللعنة", "dammit" to "اللعنة", "damn it" to "اللعنة",
			"goddammit" to "اللعنة", "goddamn it" to "اللعنة", "dagnabbit" to "اللعنة",
			"son of a bitch" to "ابن العاهرة", "bullshit" to "هراء", "horseshit" to "هراء", "horsecrap" to "هراء",
			"no way" to "مستحيل", "on my way" to "أنا في طريقي", "i'm on my way" to "أنا في طريقي", "i am on my way" to "أنا في طريقي",
			"out of the way" to "ابتعد عن الطريق", "get out of the way" to "ابتعد عن الطريق",
			"after you" to "تفضّل قبلي", "count me in" to "أنا معكم", "count me out" to "لا تحسبوني معكم",
			"you bet" to "بالتأكيد", "over my dead body" to "على جثتي",
			"piece of cake" to "أمر سهل جداً", "a piece of cake" to "أمر سهل جداً",
			"fair enough" to "هذا معقول", "suit yourself" to "كما تشاء", "my bad" to "خطئي أنا",
			"so far so good" to "كل شيء تمام حتى الآن", "long time no see" to "لم أرك منذ زمن",
			"never mind" to "لا عليك", "for the most part" to "في الغالب",
			"i'm all ears" to "كلّي آذان صاغية", "i am all ears" to "كلّي آذان صاغية",
			"get a hold of yourself" to "تمالك نفسك", "get ahold of yourself" to "تمالك نفسك",
			"make up your mind" to "اتخذ قرارك", "hold your horses" to "تمهّل قليلاً",
			"take it easy" to "على مهلك", "easy there" to "على مهلك", "easy now" to "على مهلك",
			"hang on" to "انتظر لحظة", "hold on" to "انتظر لحظة", "hang on a second" to "انتظر لحظة", "hold on a second" to "انتظر لحظة",
			"watch out" to "انتبه", "look out" to "انتبه", "heads up" to "انتبه", "stay sharp" to "ابقَ متيقظاً",
			"keep it up" to "استمر هكذا", "keep it coming" to "واصل ولا تتوقف", "handle with care" to "تعامل معه بحذر",
			"don't work too hard" to "لا ترهق نفسك بالعمل", "don't i know it" to "أعرف ذلك جيداً",
			"here goes nothing" to "فلنجرّب ونرَ", "here goes everything" to "توكلنا على الله",
			"second nature" to "أمر اعتدت عليه", "in the clear" to "نحن في أمان الآن",
			"we're in the clear" to "نحن في أمان الآن", "we are in the clear" to "نحن في أمان الآن",
			"call it a day" to "لنكتفِ بهذا اليوم", "let's call it a day" to "لنكتفِ بهذا اليوم",
			"hit the road" to "لننطلق الآن", "let's hit the road" to "لننطلق الآن",
			"give it a rest" to "كفّ عن هذا الكلام", "knock it off" to "توقف عن هذا", "cut it out" to "توقف عن هذا",
			"leave me be" to "اتركني وشأني", "will you leave me be" to "اتركني وشأني",
			"good luck with that" to "بالتوفيق في ذلك", "what a joke" to "يا لها من مهزلة",
			"spit it out" to "قلها وخلّصني", "out with it" to "قلها وخلّصني", "i owe you one" to "أنا مدين لك",
			"thank you, i owe you one" to "شكراً لك، أنا مدين لك", "thanks, i owe you one" to "شكراً، أنا مدين لك",
			"beat it" to "اغرب عن وجهي", "get lost" to "اغرب عن وجهي", "no hard feelings" to "لا ضغينة بيننا",
			"you don't say" to "حقاً؟", "we're even" to "الآن تعادلنا", "we are even" to "الآن تعادلنا",
			"now we're even" to "الآن تعادلنا", "take it or leave it" to "إما أن تقبل أو ترفض", "break a leg" to "بالتوفيق",
			"for crying out loud" to "بحق الله", "give me a break" to "ارحمني", "i've got your back" to "أنا معك وأحميك",
			"i got your back" to "أنا معك وأحميك", "watch your back" to "احذر على نفسك", "my lips are sealed" to "سرّك في بئر",
			"you're on your own" to "أنت وحدك الآن", "you are on your own" to "أنت وحدك الآن",
			"the coast is clear" to "الطريق آمن", "i'm all set" to "أنا جاهز تماماً", "all set" to "كل شيء جاهز"
		)

		private val G_DROP_MAP = mapOf(
			"freezin" to "freezing", "gettin" to "getting", "runnin" to "running", "sittin" to "sitting",
			"puttin" to "putting", "cuttin" to "cutting", "stoppin" to "stopping", "droppin" to "dropping",
			"robbin" to "robbing", "diggin" to "digging", "beggin" to "begging", "havin" to "having",
			"makin" to "making", "takin" to "taking", "givin" to "giving", "livin" to "living",
			"leavin" to "leaving", "comin" to "coming", "movin" to "moving", "ridin" to "riding",
			"lovin" to "loving", "hatin" to "hating", "shakin" to "shaking", "blazin" to "blazing",
			"chargin" to "charging", "starvin" to "starving", "smokin" to "smoking", "jokin" to "joking",
			"hopin" to "hoping", "usin" to "using", "losin" to "losing", "choosin" to "choosing",
			"causin" to "causing", "tramplin" to "trampling", "darlin" to "darling", "nothin" to "nothing",
			"somethin" to "something", "mornin" to "morning", "evenin" to "evening", "happenin" to "happening",
			"talkin" to "talking", "walkin" to "walking", "lookin" to "looking", "thinkin" to "thinking",
			"workin" to "working", "waitin" to "waiting", "tryin" to "trying", "sayin" to "saying",
			"goin" to "going", "doin" to "doing", "shootin" to "shooting", "fightin" to "fighting",
			"killin" to "killing", "huntin" to "hunting", "drinkin" to "drinking", "sleepin" to "sleeping",
			"kiddin" to "kidding", "playin" to "playing", "stayin" to "staying", "standin" to "standing",
			"holdin" to "holding", "keepin" to "keeping", "messin" to "messing", "kickin" to "kicking",
			"mindin" to "minding", "askin" to "asking", "tellin" to "telling", "callin" to "calling",
			"turnin" to "turning", "startin" to "starting", "headin" to "heading", "wonderin" to "wondering",
			"sufferin" to "suffering", "dyin" to "dying", "lyin" to "lying", "cryin" to "crying"
		)
		private val G_DROP_BARE = Regex("\\b(" + G_DROP_MAP.keys.joinToString("|") + ")'?(?![A-Za-z])", RegexOption.IGNORE_CASE)
		private val G_DROP_APOS = Regex("\\b([A-Za-z]{3,}in)'(?![A-Za-z])")

		private val EN_REWRITES = listOf(
			Regex("(?:^|(?<=[.!?]\\s))ust\\s+(?=[a-z])") to "Just ",
			Regex("\\bI'?know\\b") to "I know",
			Regex("\\bIcan'?t\\b") to "I can't",
			Regex("\\bNowwhat\\b", RegexOption.IGNORE_CASE) to "Now what",
			Regex("\\bsee i you\\b", RegexOption.IGNORE_CASE) to "see if you",
			Regex("\\bwait to s?ee\\b", RegexOption.IGNORE_CASE) to "wait to see",
			Regex("\\bgonna tun\\b", RegexOption.IGNORE_CASE) to "going to turn",
			Regex("\\b(I|we) (?:gotta|have to) get on\\b", RegexOption.IGNORE_CASE) to "$1 have to get going",
			Regex("\\b(I|we) should be getting on\\b", RegexOption.IGNORE_CASE) to "$1 should get going",
			Regex("(?:^|(?<=[\\s,]))'em\\b", RegexOption.IGNORE_CASE) to "them",
			Regex("(?:^|(?<=[\\s,]))'im\\b", RegexOption.IGNORE_CASE) to "him",
			Regex("(?:^|(?<=[\\s,]))'cause\\b", RegexOption.IGNORE_CASE) to "because",
			Regex("(?:^|(?<=[\\s,]))'til\\b", RegexOption.IGNORE_CASE) to "until",
			Regex("\\bgood ol'\\b", RegexOption.IGNORE_CASE) to "good old",
			Regex("\\blooky here\\b", RegexOption.IGNORE_CASE) to "look here",
			Regex("\\bya'?(?=\\s|$|[.!?…,])", RegexOption.IGNORE_CASE) to "you",
			Regex("\\b(I|you|we|they) been\\b", RegexOption.IGNORE_CASE) to "$1 have been",
			Regex("\\bwhat (I|you|he|she|we|they) done\\b", RegexOption.IGNORE_CASE) to "what $1 did",
			Regex("\\b(you|I|we|he|she|they)'d better\\b", RegexOption.IGNORE_CASE) to "$1 had better",
			Regex("\\b(you|we|they|all of you) better (?!than\\b)", RegexOption.IGNORE_CASE) to "$1 had better ",
			Regex("'s\\s+(her|his|my|our|their|your)\\s+name\\b", RegexOption.IGNORE_CASE) to " is $1 name",
			Regex("\\bthere ain't no\\b", RegexOption.IGNORE_CASE) to "there is no",
			Regex("\\b(?:ain't|isn't|is not) (?:getting|taking) (us|you|me|them|him|her) (?:nowhere|anywhere)\\b", RegexOption.IGNORE_CASE) to "is not leading $1 anywhere",
			Regex("\\bain't (getting|going|taking|leading) (\\w+) nowhere\\b", RegexOption.IGNORE_CASE) to "is not $1 $2 anywhere",
			Regex("\\bain't got no\\b", RegexOption.IGNORE_CASE) to "do not have any",
			Regex("\\bain't got nothing\\b", RegexOption.IGNORE_CASE) to "do not have anything",
			Regex("\\b(you) old (fox|devil|dog|wolf|rascal|rogue|scoundrel|bastard|crook|bandit|thief|liar|snake|rat)\\b", RegexOption.IGNORE_CASE) to "$1 cunning $2",
			Regex("\\b(you) (crazy|lucky|clever|sneaky|stubborn|greedy|filthy|dirty|miserable|poor|little) son of a (?:gun|bitch)\\b", RegexOption.IGNORE_CASE) to "$1 $2 devil",
			Regex("\\bson of a gun\\b", RegexOption.IGNORE_CASE) to "bastard",
			Regex(",\\s*(stranger|partner|cowboy|friend|kid|pal|buddy)(?=[.!?…]|$)", RegexOption.IGNORE_CASE) to ", oh $1",
			Regex("\\b(the|this|that) (bridge|road|track|tracks|rail|rails|pass|trail|tunnel|path|way) (?:is|are|was|were) out\\b", RegexOption.IGNORE_CASE) to "$1 $2 is blocked",
			Regex("\\b(the|this|that|our|my|your|his|her|their) (fire|light|lights|lantern|lamp|torch|candle|power) (?:is|are|was|were) out\\b", RegexOption.IGNORE_CASE) to "$1 $2 has gone out",
			Regex(",\\s*(we|I|you|they)(?:'ll| will| have to) (?:have to )?go around\\b", RegexOption.IGNORE_CASE) to ", so $1 must go around it",
			Regex("\\b(have to|need to|must|should|going to|will|'ll|let's|lets) go around(?=[.!?…,\\s]|$)", RegexOption.IGNORE_CASE) to "$1 go around it",
			Regex(",\\s*(I|we) (?:don't|do not) think (he|she|it|they|you|we)(?:'ll| will) (?:make it|survive)\\b", RegexOption.IGNORE_CASE) to ", and $1 do not think $2 will survive",
			Regex("\\b(going to|will|'ll|won't|wouldn't|didn't|can't|cannot|could) make it(?=[.!?…,\\s]|$)", RegexOption.IGNORE_CASE) to "$1 survive",
			Regex("\\b(will|'ll|can|could|manage to|going to|try to) get by\\b", RegexOption.IGNORE_CASE) to "$1 manage",
			Regex(",\\s*(I'm|I am|we're|we are) (?:going to )?turn in(?:\\s+for the night)?(?=[.!?…]|$)", RegexOption.IGNORE_CASE) to ", so $1 going to go to sleep",
			Regex("\\b(going to|time to|let's|lets|will|'ll|should|need to|have to) turn in(?:\\s+for the night)?(?=[.!?…]|$)", RegexOption.IGNORE_CASE) to "$1 go to sleep",
			Regex("\\b(horse|legs|heart|strength|breath|lungs|knees|back) gave out\\b", RegexOption.IGNORE_CASE) to "$1 collapsed from exhaustion",
			Regex("\\b(rope|wagon|wheel|wheels|engine|bridge|roof|wall) gave out\\b", RegexOption.IGNORE_CASE) to "$1 broke down",
			Regex("\\bgive (us|me|him|her|them|yourself|yourselves|our position|the plan) away\\b", RegexOption.IGNORE_CASE) to "expose $1",
			Regex("\\bhold out (a little longer|until|till|much longer|for long|against)\\b", RegexOption.IGNORE_CASE) to "endure $1",
			Regex("\\b(?:lay|lie|stay) low\\b", RegexOption.IGNORE_CASE) to "hide",
			Regex("\\buntil the heat dies down\\b", RegexOption.IGNORE_CASE) to "until things calm down",
			Regex("\\b(?:die|dies|died|dying) down\\b", RegexOption.IGNORE_CASE) to "calm down",
			Regex("\\bpulled it off\\b", RegexOption.IGNORE_CASE) to "did it",
			Regex("\\bpull it off\\b", RegexOption.IGNORE_CASE) to "do it",
			Regex(",\\s*make every (shot|bullet) count\\b", RegexOption.IGNORE_CASE) to ", so do not waste a single $1",
			Regex("\\bmake every (shot|bullet) count\\b", RegexOption.IGNORE_CASE) to "do not waste a single $1",
			Regex("\\bbefore the law (?:shows up|arrives|gets here|comes)\\b", RegexOption.IGNORE_CASE) to "before the police arrive",
			Regex("\\b(I'm|I am|we're|we are|he's|he is|she's|she is|they're|they are) riding to (?=[A-Z])", RegexOption.IGNORE_CASE) to "$1 heading to ",
			Regex("\\bget (that|this|those|these|the|your|his|her|my|our) (\\w+) out of here\\b", RegexOption.IGNORE_CASE) to "take $1 $2 out of here",
			Regex("\\b(?:watch|mind|hold|bite|guard) (your|his|her|their|my|our) (?:mouth|tongue)\\b", RegexOption.IGNORE_CASE) to "hold $1 tongue",
			Regex("\\b(?:don't|do not) push your luck\\b", RegexOption.IGNORE_CASE) to "do not test my patience",
			Regex("\\bpush(?:ing)? (?:your|his|her|their) luck\\b", RegexOption.IGNORE_CASE) to "testing my patience",
			Regex("\\b(if you |don't |do not |never )cross (me|us)\\b", RegexOption.IGNORE_CASE) to "${1}betray $2",
			Regex("\\bdouble-?cross(ed|ing)?\\b", RegexOption.IGNORE_CASE) to "betray$1",
			Regex(",\\s*or else(?=[.!?…]|$)", RegexOption.IGNORE_CASE) to ", or you will regret it",
			Regex("\\btake (one more|another) step\\b", RegexOption.IGNORE_CASE) to "move $1 step",
			Regex("\\bput a bullet in (you|him|her|them)\\b", RegexOption.IGNORE_CASE) to "shoot $1",
			Regex("\\bknew (?:it )?all along\\b", RegexOption.IGNORE_CASE) to "knew from the beginning",
			Regex("\\ball along(?=[.!?…]|$)", RegexOption.IGNORE_CASE) to "from the beginning",
			Regex("\\b(?:haven't|have not|hasn't|has not) changed a bit\\b", RegexOption.IGNORE_CASE) to "have not changed at all",
			Regex("\\blook what the cat dragged in\\b", RegexOption.IGNORE_CASE) to "look who just showed up",
			Regex("\\b(?:save|(?:don't|do not) waste) your breath\\b", RegexOption.IGNORE_CASE) to "do not tire yourself talking",
			Regex("\\b(horse|mare|stallion|mule) (?:threw|lost|cast) a shoe\\b", RegexOption.IGNORE_CASE) to "$1 lost its horseshoe",
			Regex("\\bsaddle the horses and pack the wagon\\b", RegexOption.IGNORE_CASE) to "prepare the horses and load the wagon",
			Regex("\\bsaddle the horses\\b", RegexOption.IGNORE_CASE) to "prepare the horses",
			Regex("\\btake the high ground\\b", RegexOption.IGNORE_CASE) to "move to the high ground",
			Regex("\\b(every|the|that|this|an old|a small|his|her|our|my) cabin\\b", RegexOption.IGNORE_CASE) to "$1 hut",
			Regex("\\b(\\d+%)\\s+chance (?!that\\b)", RegexOption.IGNORE_CASE) to "$1 probability that ",
			Regex("\\bhold your horses\\b", RegexOption.IGNORE_CASE) to "wait a moment",
			Regex("\\ba piece of cake\\b", RegexOption.IGNORE_CASE) to "very easy",
			Regex("\\bpiece of cake\\b", RegexOption.IGNORE_CASE) to "very easy",
			Regex("\\bkeep an eye on\\b", RegexOption.IGNORE_CASE) to "watch",
			Regex("\\bgive (me|us|him|her|them) a hand\\b", RegexOption.IGNORE_CASE) to "help $1",
			Regex("\\b(?:kidding|joking|fooling) around\\b", RegexOption.IGNORE_CASE) to "joking",
			Regex("\\bmessing around\\b", RegexOption.IGNORE_CASE) to "playing",
			Regex("\\b(sitting|waiting|standing|hanging|sticking|lying) around\\b", RegexOption.IGNORE_CASE) to "$1",
			Regex("\\b(will|'ll|would) (?:see|have) (us|you|me|them|him|her) right\\b", RegexOption.IGNORE_CASE) to "$1 take care of $2",
			Regex("\\btake it out on (me|you|him|her|us|them)\\b", RegexOption.IGNORE_CASE) to "blame $1",
			Regex("\\bbored out of (?:my|your|his|her|our|their) mind\\b", RegexOption.IGNORE_CASE) to "extremely bored",
			Regex("\\bfor the most part\\b", RegexOption.IGNORE_CASE) to "mostly",
			Regex("\\bbehaving (?:himself|herself|yourself|themselves|myself)\\b", RegexOption.IGNORE_CASE) to "behaving well",
			Regex("\\bbehave (?:yourself|yourselves|himself|herself)\\b", RegexOption.IGNORE_CASE) to "behave politely",
			Regex("\\bhey now\\b", RegexOption.IGNORE_CASE) to "easy now",
			Regex("\\bis (this|that|it) meant to be\\b", RegexOption.IGNORE_CASE) to "is $1 supposed to be",
			Regex("\\bkeep it up\\b", RegexOption.IGNORE_CASE) to "keep doing well",
			Regex("\\bsnap out of it\\b", RegexOption.IGNORE_CASE) to "wake up from this",
			Regex("\\bsad business\\b", RegexOption.IGNORE_CASE) to "sad matter",
			Regex("\\bwhen (\\w+) passed(?=[.!?…,\\s]|$)", RegexOption.IGNORE_CASE) to "when $1 died",
			Regex("\\bwhen anyone passes\\b", RegexOption.IGNORE_CASE) to "when anyone dies",
			Regex("\\bget back to it\\b", RegexOption.IGNORE_CASE) to "get back to work",
			Regex("\\breal (funny|nice|good|smart|clever|cute|sweet|kind|helpful|tough|brave)\\b", RegexOption.IGNORE_CASE) to "very $1",
			Regex("\\b(I'm|I am|we're|we are) (?:just |only )?(?:playing|pulling your leg)(?: with you)?\\b", RegexOption.IGNORE_CASE) to "$1 just joking with you",
			Regex("\\bhow (?:are )?you (?:boys |folks |fellas |all |guys )?keeping\\b", RegexOption.IGNORE_CASE) to "how are you doing",
			Regex("\\bhow (?:did )?you get on out there\\b", RegexOption.IGNORE_CASE) to "how did you do out there",
			Regex("\\byou(?:'re| are) slipping\\b", RegexOption.IGNORE_CASE) to "you are losing your touch",
			Regex("\\bnot (?:going to )?even dignify that with a response\\b", RegexOption.IGNORE_CASE) to "not even going to answer that",
			Regex("\\bif you can't take it, don't give it out\\b", RegexOption.IGNORE_CASE) to "if you cannot handle it, do not do it",
			Regex("\\b(?:I'm|I am) all ears\\b", RegexOption.IGNORE_CASE) to "I am listening carefully",
			Regex("\\bget a ?hold of yourself\\b", RegexOption.IGNORE_CASE) to "control yourself",
			Regex("\\b(?:have|having|take) a look around\\b", RegexOption.IGNORE_CASE) to "look around the place",
			Regex("\\bget the upper hand\\b", RegexOption.IGNORE_CASE) to "gain the advantage",
			Regex("\\bas far as I'm concerned\\b", RegexOption.IGNORE_CASE) to "in my opinion",
			Regex("\\blook a gift horse in the mouth\\b", RegexOption.IGNORE_CASE) to "reject a free gift",
			Regex("\\b(?:into|out of) thin air\\b", RegexOption.IGNORE_CASE) to "without a trace",
			Regex("\\bout of the blue\\b", RegexOption.IGNORE_CASE) to "suddenly",
			Regex("\\bonce in a blue moon\\b", RegexOption.IGNORE_CASE) to "very rarely",
			Regex("\\bunder the weather\\b", RegexOption.IGNORE_CASE) to "feeling sick",
			Regex("\\bspill the beans\\b", RegexOption.IGNORE_CASE) to "tell the secret",
			Regex("\\bbeat around the bush\\b", RegexOption.IGNORE_CASE) to "waste time talking",
			Regex("\\bbite the bullet\\b", RegexOption.IGNORE_CASE) to "face the situation",
			Regex("\\b(?:cut it out|knock it off)\\b", RegexOption.IGNORE_CASE) to "stop it",
			Regex("\\bgive it a rest\\b", RegexOption.IGNORE_CASE) to "stop talking about it",
			Regex("\\bcall it a day\\b", RegexOption.IGNORE_CASE) to "stop for today",
			Regex("\\bhit the road\\b", RegexOption.IGNORE_CASE) to "leave now",
			Regex("\\b(?:I'm|I am) done now\\b", RegexOption.IGNORE_CASE) to "I have stopped now",
			Regex("\\bsleep this off\\b", RegexOption.IGNORE_CASE) to "sleep until I recover",
			// More everyday idioms the translators took word for word (same list as the laptop's)
			Regex("\\bspit it out already\\b", RegexOption.IGNORE_CASE) to "say it now",
			Regex("\\bspit it out\\b", RegexOption.IGNORE_CASE) to "say it",
			Regex("\\bdon't you dare\\b", RegexOption.IGNORE_CASE) to "never",
			Regex("\\b(he|she)(?:'s| has) got (?:some|a lot of) nerve\\b", RegexOption.IGNORE_CASE) to "$1 is very shameless",
			Regex("\\b(they)(?:'ve| have) got (?:some|a lot of) nerve\\b", RegexOption.IGNORE_CASE) to "$1 are very shameless",
			Regex("\\bput your back into it\\b", RegexOption.IGNORE_CASE) to "push harder",
			Regex("^that was close(?:,\\s*too close)?(?=[.!…]|$)", RegexOption.IGNORE_CASE) to "we barely escaped",
			Regex("\\bI owe you one\\b", RegexOption.IGNORE_CASE) to "I owe you a favor",
			Regex("\\bowe (you|me|him|her|us|them) one\\b", RegexOption.IGNORE_CASE) to "owe $1 a favor",
			Regex("\\b(?:you've|you have) got (?:some |a lot of )?nerve\\b", RegexOption.IGNORE_CASE) to "you are very shameless",
			Regex("\\btake it or leave it\\b", RegexOption.IGNORE_CASE) to "accept it or refuse it",
			Regex("\\bcall it a night\\b", RegexOption.IGNORE_CASE) to "stop for tonight",
			Regex("\\b(?:running|run|runs|ran) (?:his|her|your|their|my) mouth\\b", RegexOption.IGNORE_CASE) to "talking too much",
			Regex(",\\s*not (?:one|a single) bit(?=[.!?…]|$)", RegexOption.IGNORE_CASE) to ", not at all",
			Regex("\\bhit the (?:hay|sack)\\b", RegexOption.IGNORE_CASE) to "go to sleep",
			Regex("\\bwhat(?:'s| is) eating (you|him|her)\\b", RegexOption.IGNORE_CASE) to "what is bothering $1",
			Regex("\\b(?:getting|got|get|have|having) cold feet\\b", RegexOption.IGNORE_CASE) to "hesitating",
			Regex("\\bkeep your chin up\\b", RegexOption.IGNORE_CASE) to "do not lose hope",
			Regex("\\bin (?:a|quite a|a real) (?:pickle|jam|bind)\\b", RegexOption.IGNORE_CASE) to "in trouble",
			Regex("\\bcost (you|me|us|him|her|them) an arm and a leg\\b", RegexOption.IGNORE_CASE) to "cost $1 a fortune",
			Regex("\\b(you're|you are|he's|he is|she's|she is) (?:a real |quite a |a )piece of work\\b", RegexOption.IGNORE_CASE) to "$1 an unbelievable person",
			Regex("\\bon thin ice\\b", RegexOption.IGNORE_CASE) to "in great danger",
			Regex("\\blet the cat out of the bag\\b", RegexOption.IGNORE_CASE) to "revealed the secret",
			Regex("\\bfair and square\\b", RegexOption.IGNORE_CASE) to "honestly",
			Regex("\\bturn(?:ed|ing)? a blind eye\\b", RegexOption.IGNORE_CASE) to "ignore it",
			Regex("\\bsleep on it\\b", RegexOption.IGNORE_CASE) to "think about it tonight",
			Regex("\\bmake a run for it\\b", RegexOption.IGNORE_CASE) to "escape quickly",
			Regex("\\bcut to the chase\\b", RegexOption.IGNORE_CASE) to "get to the point",
			Regex("\\bplay(?:ing)? dumb\\b", RegexOption.IGNORE_CASE) to "pretending not to know",
			Regex("\\byou(?:'re| are) dead meat\\b", RegexOption.IGNORE_CASE) to "you are finished",
			Regex("\\bkeep (?:that|it|this) in mind\\b", RegexOption.IGNORE_CASE) to "remember that",
			Regex("\\bget going(?=[.!?…,]|$)", RegexOption.IGNORE_CASE) to "leave now",
			Regex("\\bheads? out at\\b", RegexOption.IGNORE_CASE) to "leave at",
			Regex("\\b(?:I'm|I am) in no mood for\\b", RegexOption.IGNORE_CASE) to "I do not want",
			Regex("\\bgo get some (sleep|rest)\\b", RegexOption.IGNORE_CASE) to "go and get some $1"
		)

		private val MASC_DEFAULT_FIXES = listOf(
			Regex("(?:^|(?<=[\\s،؛؟.!]))[اأإ]بقي\\s+(?=رأسك|يديك|عينيك|صوتك|سلاحك|فمك)") to "أبقِ ",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]تبعيني(?=[\\s،؛؟.!]|$)") to "$1اتبعني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]هد[أئ]ي(?=[\\s،؛؟.!]|$)") to "$1اهدأ",
			Regex("\\bأن تقولي\\b") to "أن تقول",
			Regex("\\b(س?تندم)ين\\b") to "$1",
			Regex("\\b(تتحدث|تتكلم)ين\\b") to "$1",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)ساعديني(?=[\\s،؛؟.!]|$)") to "$1ساعدني"
		)

		private val FEM_VOCATIVE_FIXES = listOf(
			Regex("،\\s*والبقاء هنا") to "، ابقي هنا",
			Regex("\\bومشاهدة الخيول\\b") to "وراقبي الخيول",
			Regex("\\bيجب أن تبقى\\b") to "يجب أن تبقي",
			Regex("\\bتحتاج إلى\\b") to "تحتاجين إلى",
			Regex("\\bتحتاج أن\\b") to "تحتاجين أن",
			Regex("\\bتستطيع أن\\b") to "تستطيعين أن",
			Regex("\\bماذا تفعل\\b") to "ماذا تفعلين",
			Regex("\\bأين تذهب\\b") to "أين تذهبين",
			Regex("\\bهل أنت متأكد\\b") to "هل أنتِ متأكدة",
			Regex("\\bهل أنت مستعد\\b") to "هل أنتِ مستعدة",
			Regex("\\bهل أنت جاهز\\b") to "هل أنتِ جاهزة",
			Regex("\\bأنت مصاب\\b") to "أنتِ مصابة",
			Regex("\\bأنت متعب\\b") to "أنتِ متعبة",
			Regex("\\bكن حذراً\\b") to "كوني حذرة",
			Regex("\\bابقَ? قريباً\\b") to "ابقي قريبة",
			Regex("\\bابقَ? هنا\\b") to "ابقي هنا",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]نبطح(?=[\\s،؛؟.!]|$)") to "$1انبطحي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]نتبه(?=[\\s،؛؟.!]|$)") to "$1انتبهي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]حترس(?=[\\s،؛؟.!]|$)") to "$1احترسي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]حذر(?=[\\s،؛؟.!]|$)") to "$1احذري",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)راقب(?=[\\s،؛؟.!]|$)") to "$1راقبي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]حرس(?=[\\s،؛؟.!]|$)") to "$1احرسي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اأإ]غلق(?=[\\s،؛؟.!]|$)") to "$1أغلقي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]فتح(?=[\\s،؛؟.!]|$)") to "$1افتحي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]نتظر(?=[\\s،؛؟.!]|$)") to "$1انتظري",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]نتظرني(?=[\\s،؛؟.!]|$)") to "$1انتظريني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]سمع(?=[\\s،؛؟.!]|$)") to "$1اسمعي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]سمعني(?=[\\s،؛؟.!]|$)") to "$1اسمعيني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]ستمع(?=[\\s،؛؟.!]|$)") to "$1استمعي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)تعال(?=[\\s،؛؟.!]|$)") to "$1تعالي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]ذهب(?=[\\s،؛؟.!]|$)") to "$1اذهبي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]ركض(?=[\\s،؛؟.!]|$)") to "$1اركضي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]هرب(?=[\\s،؛؟.!]|$)") to "$1اهربي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اأإ]سرع(?=[\\s،؛؟.!]|$)") to "$1أسرعي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)توقف(?=[\\s،؛؟.!]|$)") to "$1توقفي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)تحرك(?=[\\s،؛؟.!]|$)") to "$1تحركي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]هدأ(?=[\\s،؛؟.!]|$)") to "$1اهدئي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]نهض(?=[\\s،؛؟.!]|$)") to "$1انهضي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]جلس(?=[\\s،؛؟.!]|$)") to "$1اجلسي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]دخل(?=[\\s،؛؟.!]|$)") to "$1ادخلي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]خرج(?=[\\s،؛؟.!]|$)") to "$1اخرجي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]بتعد(?=[\\s،؛؟.!]|$)") to "$1ابتعدي",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اأإ]جبني(?=[\\s،؛؟.!]|$)") to "$1أجيبيني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اأإ]خبرني(?=[\\s،؛؟.!]|$)") to "$1أخبريني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اأإ]عطني(?=[\\s،؛؟.!]|$)") to "$1أعطيني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)ساعدني(?=[\\s،؛؟.!]|$)") to "$1ساعديني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)سامحني(?=[\\s،؛؟.!]|$)") to "$1سامحيني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)صدقني(?=[\\s،؛؟.!]|$)") to "$1صدقيني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]تركني(?=[\\s،؛؟.!]|$)") to "$1اتركيني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]تبعني(?=[\\s،؛؟.!]|$)") to "$1اتبعيني",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)[اإ]نظر(?=[\\s،؛؟.!]|$)") to "$1انظري",
			Regex("\\bألقِ? نظرة\\b") to "ألقي نظرة",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)خذ(?=[\\s،؛؟.!]|$)") to "$1خذي",
			Regex("\\bلا تقلق(?=[\\s،؛؟.!]|$)") to "لا تقلقي",
			Regex("\\bلا تخف(?=[\\s،؛؟.!]|$)") to "لا تخافي",
			Regex("\\bلا تتحرك(?=[\\s،؛؟.!]|$)") to "لا تتحركي",
			Regex("\\bلا تذهب(?=[\\s،؛؟.!]|$)") to "لا تذهبي",
			Regex("\\bلا تفعل(?=[\\s،؛؟.!]|$)") to "لا تفعلي",
			Regex("\\bلا تنسَ?(?=[\\s،؛؟.!]|$)") to "لا تنسي"
		)

		private val FEM_PLURAL_FIXES = listOf(
			Regex("\\bغادر(?:وا|ت)?(?=\\s+المخيم|\\s+المعسكر|\\s+البلدة|\\s+المدينة|\\s+المنزل|\\s+المكان|\\b)") to "غادرن",
			Regex("\\bذهبوا\\b") to "ذهبن",
			Regex("\\bوصلوا\\b") to "وصلن",
			Regex("\\bعادوا\\b") to "عدن",
			Regex("\\bكانوا\\b") to "كنّ",
			Regex("\\bهربوا\\b") to "هربن",
			Regex("\\bخرجوا\\b") to "خرجن",
			Regex("\\bدخلوا\\b") to "دخلن",
			Regex("\\bبقوا\\b") to "بقين"
		)

		private val LEFTOVER_WORDS = listOf(
			Regex("\\b(?:OK|Okay)\\b", RegexOption.IGNORE_CASE) to "حسناً",
			Regex("\\b(?:Yeah|Yep)\\b", RegexOption.IGNORE_CASE) to "نعم",
			Regex("\\b(?:Nope|Nah)\\b", RegexOption.IGNORE_CASE) to "لا",
			Regex("\\b(?:Whoa|Woah)\\b", RegexOption.IGNORE_CASE) to "تمهّل",
			Regex("\\bSheriff\\b", RegexOption.IGNORE_CASE) to "الشريف",
			Regex("\\bReverend\\b", RegexOption.IGNORE_CASE) to "القس",
			Regex("\\bCaptain\\b", RegexOption.IGNORE_CASE) to "الكابتن",
			Regex("\\bColonel\\b", RegexOption.IGNORE_CASE) to "الكولونيل",
			Regex("\\bSergeant\\b", RegexOption.IGNORE_CASE) to "الرقيب",
			Regex("\\bLieutenant\\b", RegexOption.IGNORE_CASE) to "الملازم",
			Regex("\\b(?:Ma'am|Madam)\\b", RegexOption.IGNORE_CASE) to "سيدتي",
			Regex("\\bSir\\b", RegexOption.IGNORE_CASE) to "سيدي"
		)

		private val AR_ORTHO_FIXES = listOf(
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?ب?ك?ل?ال)إ(ست[؀-ۿ]{2,}|تّ?هام|هتمام|نتظار|حترام|عتذار|ختيار|ختبار|عتماد|تّ?صال|تّ?فاق|نتباه|نتقام|نتهاء|قتراب|كتشاف|لتزام|متحان|بتسام|عتقاد|حتمال)\\b") to "$1ا$2",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)إ(هد[أئء]|نهض|نتبه|نتظر|بتعد|ستمع|سمع|ذهب|حذر|حترس|تخذ|حسب|سأل|فعل|فتح|حصل|بق[ىيوا]?|حرص|خرس|صمت|ركض|هرب|ترك|عتقد|قترب|كتشف|حتم|ستمر|ستعد|بحث|قفز|صعد|نزل|نبطح|جلس|حمل|رفع|خفض|نظر)([؀-ۿ]*)\\b") to "$1ا$2$3",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)اعط(ني|يني|ه|ها|هم|نا|كم)\\b") to "$1أعط$2",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)اخبر(ني|يني|ه|ها|هم|نا)\\b") to "$1أخبر$2",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)انقذ(ني|يني|ه|ها|هم|نا)\\b") to "$1أنقذ$2",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)اسرع(ي|وا|ن)?\\b") to "$1أسرع$2",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)اغلق(ي|وا|ه|ها)?\\b") to "$1أغلق$2",
			Regex("(?:^|(?<=[\\s،؛؟.!]))(و?ف?)أنظر(وا|ي)?\\b") to "$1انظر$2",
			Regex("(?:^|(?<=[\\s،؛؟.!]))ان(ه|ها|هم|هن|ك|كم|نا|ني)\\b") to "إن$1",
			Regex("\\bاهدأي\\b") to "اهدئي",
			Regex("\\b(?:تحكم بنفسك|سيطر على نفسك)\\b") to "تمالك نفسك",
			Regex("\\bلا تلومني\\b") to "لا تلمني",
			Regex("\\bلم نموت\\b") to "لم نمت",
			Regex("\\bنفذت(?=\\s+(?:منا\\s+)?(?:الذخيرة|ذخير|المؤن|الإمدادات|الطلقات))") to "نفدت",
			Regex("\\b(?:تراقب فمك|تمسك لسانك|تحرس لسانك)\\b") to "تحفظ لسانك",
			Regex("\\b(?:راقب فمك|أمسك لسانك|امسك لسانك|احرس لسانك|انتبه إلى لسانك)\\b") to "احفظ لسانك",
			Regex("،\\s*أنت\\s+(ال[؀-ۿ]+(?:\\s+ال[؀-ۿ]+)?)،") to "، أيها $1،",
			Regex("\\b(الثعلب|الذئب|الكلب|البغل|الحمار|الشيطان|الوغد|الأحمق|الغبي|المجنون|اللص|المحتال|الكاذب|الجبان|الرجل) القديم\\b") to "$1 العجوز",
			Regex("\\bحصانك فقد حدوة حصانه\\b") to "فقد حصانك حدوته",
			Regex("\\bفقد حصانك حدوة حصانه\\b") to "فقد حصانك حدوته",
			Regex("\\bحدوة حصانه\\b") to "حدوته",
			Regex("\\b(يستمع|يصغي|تستمع|تصغي) إلى السبب\\b") to "$1 إلى المنطق",
			Regex("\\bاطفئت\\b") to "أطفئت",
			Regex("(البينكرتون|الأودريسكول|الإخوة سكينر|آل بريثويت|آل غراي|آل مورفري|غزاة ليموين)\\s+(حاصر|أحرق|هاجم|قتل|سرق|نهب|دمّر|دمر|خطف|قطع|أخذ|ترك|هرب|دخل|خرج|وصل|عاد)\\b") to "$1 ${2}وا"
		)

		fun splitSentences(text: String): List<String>
		{
			val parts = ArrayList<String>()
			for(raw in SENT_RX.split(text))
			{
				val piece = raw.trim()
				if(piece.isEmpty()) continue
				if(parts.isNotEmpty() && ABBREV_RX.containsMatchIn(parts.last()))
					parts[parts.lastIndex] = "${parts.last()} $piece"
				else
					parts.add(piece)
			}
			return if(parts.isEmpty()) listOf(text) else parts
		}

		fun joinSentences(sources: List<String>, translations: List<String>): String
		{
			val out = ArrayList<String>()
			for((en, rawAr) in sources.zip(translations))
			{
				var ar = rawAr.trim()
				if(ar.isEmpty()) continue
				val cleanEn = en.trimEnd('"', '\'', ')', ']', ' ')
				val last = cleanEn.lastOrNull()
				val isEllipsis = cleanEn.endsWith("...") || cleanEn.endsWith("..") || cleanEn.endsWith("…")
				val stripped = ar.trimEnd('.', '!', '؟', '?', '…', '،', ' ')
				ar = when
				{
					isEllipsis -> "$stripped..."
					last == '?' -> "$stripped؟"
					last == '!' -> "$stripped!"
					last == '.' && ar.last() !in ".!؟?…،" -> "$ar."
					else -> ar
				}
				out.add(ar)
			}
			return out.joinToString(" ")
		}

		fun phraseLookup(sentence: String): String?
		{
			val s = sentence.trim().trimStart('-', '–', '—').trim()
			val m = PHRASE_SPLIT_RX.find(s) ?: return null
			val core = m.groupValues[1].trim().lowercase()
			val tail = m.groupValues[2].trim()
			val ar = PHRASES[core] ?: return null
			return when
			{
				'?' in tail -> "$ar؟"
				'!' in tail -> "$ar!"
				tail.contains("...") || tail.contains("..") || '…' in tail -> "$ar..."
				'.' in tail -> "$ar."
				else -> ar
			}
		}

		fun preprocess(text: String): String
		{
			var s = INLINE_SPEAKER.replace(text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').trim(), "")
			if(s.startsWith("(") || s.startsWith("["))
			{
				val stripped = STAGE_DIR.replace(s, "").trim()
				if(stripped.isNotEmpty()) s = stripped
			}
			if(s.startsWith("-") || s.startsWith("–") || s.startsWith("—"))
				s = EN_LEAD_DASH.replace(s, "")
			if('-' in s) s = STUTTER.replace(s, "$1$2")
			s = IM_IVE_RX.replace(s, "I'$1")
			s = WORD_REWRITE_RX.replace(s) { m ->
				val w = m.value
				val repl = WORD_REWRITES[w.lowercase()] ?: w
				if(w[0].isUpperCase() && repl[0].isLowerCase()) repl.replaceFirstChar { it.uppercase() } else repl
			}
			s = G_DROP_BARE.replace(s) { m ->
				val w = m.groupValues[1]
				val repl = G_DROP_MAP[w.lowercase()] ?: "${w}g"
				if(w[0].isUpperCase()) repl.replaceFirstChar { it.uppercase() } else repl
			}
			if("in'" in s)
			{
				s = G_DROP_APOS.replace(s) { m ->
					val w = m.groupValues[1]
					val repl = G_DROP_MAP[w.lowercase()] ?: "${w}g"
					if(w[0].isUpperCase()) repl.replaceFirstChar { it.uppercase() } else repl
				}
			}
			for((rx, repl) in EN_REWRITES) s = rx.replace(s, repl)
			s = ALL_CAPS_WORD.replace(s) { m ->
				val w = m.groupValues[1]
				if(w in ROMAN) w
				else
				{
					val pre = s.substring(0, m.range.first).trimEnd()
					if(pre.isEmpty() || pre.last() in ".!?…:\"-–—([") w.lowercase().replaceFirstChar { it.uppercase() }
					else w.lowercase()
				}
			}
			return WS_RX.replace(s, " ").trim()
		}

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
