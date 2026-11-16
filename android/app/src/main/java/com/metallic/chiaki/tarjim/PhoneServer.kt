package com.metallic.chiaki.tarjim

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The interface's back end inside the app: a small HTTP server on 127.0.0.1 that serves
 * the same pages as the laptop program (assets/web) and the same JSON API, so the phone
 * shows the very same interface. Nothing outside the phone can reach it.
 */
class PhoneServer private constructor(private val context: Context)
{
	val store = Store(context)
	val xbox = XboxAuth(store)
	private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
	val url get() = "http://127.0.0.1:${server.localPort}/"
	private val streams = java.util.concurrent.CopyOnWriteArrayList<LinkedBlockingQueue<String>>()
	private var lastState = ""

	init
	{
		Thread({
			while(true)
			{
				val client = try { server.accept() } catch(e: Exception) { break }
				Thread({ try { handle(client) } catch(e: Exception) { Log.w(TAG, "request failed", e) } finally { try { client.close() } catch(e: Exception) { } } },
					"tarjim-http-req").apply { isDaemon = true; start() }
			}
		}, "tarjim-http").apply { isDaemon = true; start() }
		TarjimService.listeners.add { what ->
			if(what == "line") TarjimService.lastLine?.let { publish("line", lineJson(it)) }
			else if(what == "xbox") publish("xbox", xboxStart())
			else
			{
				val live = liveJson()
				val state = live.optString("state") + "|" + live.optString("step") + "|" + live.optString("error")
				if(state != lastState) { lastState = state; publish("state", live) }
				publish("stats", live.getJSONObject("stats"))
			}
		}
	}

	private fun publish(kind: String, data: JSONObject)
	{
		val chunk = "event: $kind\ndata: $data\n\n"
		for(q in streams) q.offer(chunk)
	}

	// -- what the pages ask for ---------------------------------------------------------

	private fun lineJson(l: TarjimEngine.Line) = JSONObject().put("en", l.english).put("ar", l.arabic).put("t", l.time / 1000.0)

	private fun liveJson(): JSONObject
	{
		val s = TarjimService.status
		val on = TarjimService.running
		val flowing = s.consoleOk && s.frames > 0
		if(on && flowing && s.translatorOk && TarjimService.startedAt == 0L) TarjimService.startedAt = System.currentTimeMillis() / 1000
		val state = if(!on) "idle" else if(TarjimService.startedAt > 0) "running" else "starting"
		val step = if(!s.translatorOk) "models" else if(!s.tvOk) "tv" else "ps5"
		val lines = JSONArray()
		synchronized(TarjimService.lines) { for(l in TarjimService.lines.takeLast(30)) lines.put(lineJson(l)) }
		// While running, a lost connection is shown as the error line under the button.
		val trouble = if(state == "running" && !s.consoleOk) s.console else if(state == "running" && !s.tvOk) s.tv else s.error
		return JSONObject().put("state", state).put("step", step).put("error", if(on) trouble else "").put("profile", TarjimService.profile)
			.put("lines", lines).put("started_at", TarjimService.startedAt)
			.put("stats", JSONObject().put("fps", s.fps).put("lines", s.lines).put("latency_ms", if(s.lastMs > 0) s.lastMs else JSONObject.NULL)
				.put("on_battery", !charging()))
	}

	private fun charging(): Boolean
	{
		val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
		val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
		return state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
	}

	/** Tells the page to start receiving the Xbox stream, and which part of the picture to send back. */
	private fun xboxStart(): JSONObject
	{
		val look = Look(store.load(TarjimService.profile))
		return JSONObject().put("action", "start").put("id", store.xboxId).put("crop", JSONObject()
			.put("left", look.left.toDouble()).put("top", look.top.toDouble()).put("right", look.right.toDouble()).put("bottom", look.bottom.toDouble()))
	}

	private fun xboxView() = JSONObject().put("kind", store.kind).put("linked", xbox.linked).put("state", xbox.linkState)
		.put("error", xbox.linkError).put("code", if(xbox.linkState == "waiting") xbox.userCode else "").put("url", xbox.verificationUri)
		.put("console", if(store.xboxId.isEmpty()) JSONObject.NULL else JSONObject().put("id", store.xboxId).put("name", store.xboxName))

	private fun xboxCheck(): JSONObject
	{
		val s = TarjimService.status
		val on = TarjimService.running
		val tvSet = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(store.tv)
		val chosen = store.xboxId.isNotEmpty()
		fun item(id: String, ok: Boolean, title: String, hint: String) = JSONObject().put("id", id).put("ok", ok).put("title", title).put("hint", hint)
		val items = JSONArray()
			.put(item("power", charging(), "الجوال على الشاحن", "شبّك الشاحن: الترجمة شغل متواصل وتاكل بطارية."))
			.put(item("models", !on || s.translatorOk, "المترجم جاهز", s.translator))
			.put(item("tv", if(on) s.tvOk else tvSet, "التلفزيون", if(!tvSet) "اكتب عنوان التلفزيون في «الإعداد»." else s.tv))
			.put(item("ps5", if(on) s.consoleOk else chosen, "الإكس بوكس", if(!chosen) "اختر الإكس بوكس من «الإعداد»." else s.console))
			.put(item("link", xbox.linked, "الجوال مربوط بحساب الإكس بوكس", "اربط حساب مايكروسوفت من «الإعداد»."))
		val done = tvSet && chosen && xbox.linked
		return JSONObject().put("items", items).put("ready", done).put("kind", "xbox").put("tv_ip", store.tv).put("tv_app", s.tvOk)
			.put("tv_model", JSONObject.NULL).put("ps5_host", JSONObject.NULL).put("ps5_name", if(chosen) store.xboxName else JSONObject.NULL)
			.put("ps5_user", if(done) "xbox" else JSONObject.NULL).put("setup_done", done).put("consoles", JSONArray())
	}

	private fun registered() = runBlocking { getDatabase(context).registeredHostDao().getAll().first() }

	private fun checkJson(): JSONObject
	{
		if(store.kind == "xbox") return xboxCheck()
		val s = TarjimService.status
		val on = TarjimService.running
		val hosts = registered()
		val address = Regex("""\d{1,3}(\.\d{1,3}){3}""")
		val tvSet = address.matches(store.tv)
		val consoleSet = address.matches(store.console)
		fun item(id: String, ok: Boolean, title: String, hint: String) = JSONObject().put("id", id).put("ok", ok).put("title", title).put("hint", hint)
		val items = JSONArray()
			.put(item("power", charging(), "الجوال على الشاحن", "شبّك الشاحن: الترجمة شغل متواصل وتاكل بطارية."))
			.put(item("models", !on || s.translatorOk, "المترجم جاهز", s.translator))
			.put(item("tv", if(on) s.tvOk else tvSet, "التلفزيون", if(!tvSet) "اكتب عنوان التلفزيون في «الإعداد»." else s.tv))
			.put(item("ps5", if(on) s.consoleOk else consoleSet, "الـPS5", if(!consoleSet) "اكتب عنوان الـPS5 في «الإعداد»." else s.console))
			.put(item("link", hosts.isNotEmpty(), "الجوال مربوط بالـPS5", "اربط الـPS5 من «الإعداد»."))
		val name = hosts.firstOrNull()?.serverNickname
		return JSONObject().put("items", items).put("ready", tvSet && consoleSet && hosts.isNotEmpty())
			.put("tv_ip", store.tv).put("tv_app", s.tvOk).put("tv_model", JSONObject.NULL)
			.put("ps5_host", store.console).put("ps5_name", name ?: JSONObject.NULL)
			.put("ps5_user", if(hosts.isNotEmpty()) (name ?: "PS5") else JSONObject.NULL)
			.put("setup_done", tvSet && consoleSet && hosts.isNotEmpty())
			.put("consoles", JSONArray(hosts.map { it.serverNickname ?: "PS5" }))
	}

	private fun profileView(name: String): JSONObject
	{
		val p = store.load(name)
		val look = Look(p)
		val female = look.female
		val names = JSONArray()
		for((en, ar) in look.glossary) names.put(JSONObject().put("en", en).put("ar", ar).put("f", en in female))
		val region = JSONObject().put("left", look.left.toDouble()).put("top", look.top.toDouble()).put("right", look.right.toDouble()).put("bottom", look.bottom.toDouble())
		return JSONObject().put("name", name).put("title", p.optString("title", name)).put("glossary", names)
			.put("font", look.font).put("font_size", if(look.fontSize > 0) look.fontSize else JSONObject.NULL).put("font_auto", look.fontSize == 0)
			.put("background", look.background).put("plate_opacity", look.plateOpacity.toDouble())
			.put("text_color", p.optString("text_color", "auto").ifEmpty { "auto" }).put("effect", look.effect)
			.put("y_offset", look.yOffset).put("region", region).put("region_default", !p.has("region"))
			.put("read_scale", look.readScale.toDouble()).put("settle_ms", look.settleMs).put("style", "msa")
	}

	private fun profileSave(body: JSONObject): JSONObject
	{
		val name = body.optString("name", "default")
		val p = store.load(name)
		for(key in listOf("font", "effect", "text_color")) if(body.has(key)) p.put(key, body.optString(key))
		if(body.has("background")) p.put("background", if(body.optString("background") == "none") "none" else "plate")
		if(body.has("title")) p.put("title", body.optString("title").take(60))
		if(body.has("font_size")) { if(body.isNull("font_size") || body.optInt("font_size") == 0) p.remove("font_size") else p.put("font_size", body.optInt("font_size").coerceIn(18, 90)) }
		for(key in listOf("plate_opacity", "read_scale", "settle_ms")) if(body.has(key)) p.put(key, body.optDouble(key))
		if(body.has("y_offset")) p.put("y_offset", body.optInt("y_offset"))
		if(body.has("region")) { if(body.isNull("region")) p.remove("region") else p.put("region", body.getJSONObject("region")) }
		if(body.has("glossary"))
		{
			val names = JSONObject()
			val female = JSONArray()
			val rows = body.getJSONArray("glossary")
			for(i in 0 until rows.length())
			{
				val row = rows.getJSONObject(i)
				val en = row.optString("en").trim().take(60)
				val ar = row.optString("ar").trim().take(60)
				if(en.isEmpty() || ar.isEmpty()) continue
				names.put(en, ar)
				if(row.optBoolean("f")) female.put(en)
			}
			p.put("glossary", names).put("female", female)
		}
		store.save(name, p)
		if(TarjimService.profile == name) TarjimService.engine?.reload()  // a running session picks it up at once
		return profileView(name)
	}

	private fun start(profile: String): JSONObject
	{
		val address = Regex("""\d{1,3}(\.\d{1,3}){3}""")
		if(!address.matches(store.tv)) return error("اكتب عنوان التلفزيون في «الإعداد» أول.")
		if(store.kind == "xbox")
		{
			if(!xbox.linked) return error("الجوال مو مربوط بحساب الإكس بوكس. افتح «الإعداد» واربط حساب مايكروسوفت.")
			if(store.xboxId.isEmpty()) return error("اختر الإكس بوكس من «الإعداد» أول.")
			store.lastProfile = profile
			TarjimService.profile = profile
			TarjimService.start(context, null, store.tv, profile)
			publish("xbox", xboxStart())  // the page (this app's WebView) receives the stream
			return JSONObject().put("started", true)
		}
		if(!address.matches(store.console)) return error("اكتب عنوان الـPS5 في «الإعداد» أول.")
		val host = registered().firstOrNull() ?: return error("الجوال مو مربوط بالـPS5. افتح «الإعداد» واضغط «اربط الـPS5».")
		// Tarjim reads a fixed, light picture: 1080p, 30 frames, H.264.
		val video = ConnectVideoProfile.preset(VideoResolutionPreset.RES_1080P, VideoFPSPreset.FPS_30, Codec.CODEC_H264)
		store.lastProfile = profile
		TarjimService.start(context, ConnectInfo(host.target.isPS5, store.console, host.rpRegistKey, host.rpKey, video), store.tv, profile)
		return JSONObject().put("started", true)
	}

	private fun error(message: String) = JSONObject().put("error", message)

	private fun route(method: String, path: String, query: Map<String, String>, body: JSONObject): Any?
	{
		if(method == "GET") return when(path)
		{
			"/api/hello" -> JSONObject().put("tarjim", 1).put("remote", false).put("paired", true).put("apk", false).put("app", "phone")
			"/api/state" ->
			{
				val profiles = JSONArray()
				for((name, title) in store.profiles()) profiles.put(JSONObject().put("name", name).put("title", title))
				JSONObject().put("live", liveJson()).put("task", JSONObject().put("name", "").put("running", false).put("log", JSONArray()))
					.put("profiles", profiles).put("last_profile", store.lastProfile).put("guide_seen", true)
					.put("setup_done", checkJson().optBoolean("setup_done")).put("remote", false).put("now", System.currentTimeMillis() / 1000.0)
					.put("console", store.kind)
			}
			"/api/check" -> checkJson()
			"/api/xbox" -> xboxView()
			"/api/xbox/consoles" -> JSONObject().put("consoles", xbox.consoles())
			"/api/profile" -> profileView(query["name"] ?: "default")
			"/api/setup" -> JSONObject().put("tv", store.tv).put("console", store.console).put("consoles", checkJson().getJSONArray("consoles"))
				.put("account", store.accountName.ifEmpty { if(store.account.isNotEmpty()) "محفوظ" else "" })
			"/api/frame" -> TarjimService.engine?.frameJpeg() ?: return null
			"/api/preview" ->
			{
				val p = store.load(query["name"] ?: "default")
				val o = try { JSONObject(query["o"] ?: "{}") } catch(e: Exception) { JSONObject() }
				for(key in o.keys()) { if(o.isNull(key)) p.remove(key) else p.put(key, o.get(key)) }
				if(o.optString("text_color") == "auto") p.remove("text_color")
				TarjimEngine.preview(context, Look(p), query["text"] ?: "")
			}
			else -> null
		}
		return when(path)
		{
			"/api/start" -> if(TarjimService.running) JSONObject().put("started", false) else start(body.optString("profile", "default"))
			"/api/stop" -> { TarjimService.stop(context); publish("xbox", JSONObject().put("action", "stop")); JSONObject().put("ok", true) }
			"/api/console" -> { store.kind = body.optString("kind"); xboxView() }
			"/api/xbox/link" -> xbox.startLink()
			"/api/xbox/open" ->
			{
				context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(xbox.verificationUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
				JSONObject().put("ok", true)
			}
			"/api/xbox/select" ->
			{
				store.xboxId = body.optString("id").take(80)
				store.xboxName = body.optString("name", "Xbox").take(60)
				store.kind = "xbox"
				xboxView()
			}
			"/api/xbox/forget" -> { xbox.forget(); xboxView() }
			"/api/xbox/state" -> { TarjimService.engine?.xboxState(body.optString("state"), body.optString("error")); JSONObject().put("ok", true) }
			"/api/profile" -> profileSave(body)
			"/api/profile/new" -> profileView(store.create(body.optString("title").trim().take(60)))
			"/api/setup" ->
			{
				if(body.has("tv")) store.tv = body.optString("tv")
				if(body.has("console")) store.console = body.optString("console")
				JSONObject().put("ok", true)
			}
			"/api/line/fix" ->
			{
				val profile = body.optString("profile").ifEmpty { TarjimService.profile }
				store.setFix(profile, body.optString("en"), body.optString("ar"))
				TarjimService.engine?.reload()
				JSONObject().put("ok", true)
			}
			"/api/tv/try" ->
			{
				val engine = TarjimService.engine ?: return error("شغّل الترجمة أول، وبعدها جرّب الشكل على التلفزيون.")
				engine.reload()
				engine.tryOnTv(body.optString("text").ifBlank { "يجب أن نغادر قبل وصول العاصفة." })
				JSONObject().put("started", true)
			}
			"/api/guide-seen" -> JSONObject().put("ok", true)
			else -> null
		}
	}

	// -- HTTP ----------------------------------------------------------------------------

	private fun handle(client: Socket)
	{
		val input = BufferedInputStream(client.getInputStream())
		val head = StringBuilder()
		var last4 = 0
		while(true)
		{
			val b = input.read()
			if(b < 0) return
			head.append(b.toChar())
			last4 = (last4 shl 8) or b
			if(last4 == 0x0D0A0D0A) break
			if(head.length > 16384) return
		}
		val lines = head.toString().split("\r\n")
		val request = lines[0].split(" ")
		if(request.size < 2) return
		val method = request[0]
		val target = request[1]
		var length = 0
		for(line in lines.drop(1)) if(line.startsWith("content-length:", true)) length = line.substringAfter(":").trim().toIntOrNull() ?: 0
		val raw = ByteArray(length.coerceIn(0, 3_000_000))
		var read = 0
		while(read < raw.size) { val n = input.read(raw, read, raw.size - read); if(n < 0) break; read += n }
		val path = target.substringBefore("?")
		val query = HashMap<String, String>()
		if("?" in target) for(pair in target.substringAfter("?").split("&"))
		{
			val k = pair.substringBefore("=")
			query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
		}
		val out = client.getOutputStream()

		if(path == "/api/events") return events(out)
		if(path == "/api/xbox/frame")  // a picture of the Xbox stream's reading area from the page: raw JPEG
		{
			val ok = TarjimService.engine?.feedJpeg(raw, query["x"]?.toIntOrNull() ?: 0, query["y"]?.toIntOrNull() ?: 0,
				query["fw"]?.toIntOrNull() ?: 1920, query["fh"]?.toIntOrNull() ?: 1080) ?: false
			return send(out, 200, "application/json", "{\"ok\":$ok}".toByteArray())
		}
		if(path.startsWith("/v5/") || path.startsWith("/v6/"))  // the page's Xbox streaming calls
		{
			val info = lines.firstOrNull { it.startsWith("x-ms-device-info:", true) }?.substringAfter(":")?.trim()
			val answer = try { xbox.proxy(method, target, if(method == "POST") raw else null, info) }
			catch(e: Exception) { return send(out, 502, "application/json", JSONObject().put("error", e.message ?: "xbox").toString().toByteArray()) }
			return send(out, answer.status, "application/json; charset=utf-8", answer.body)
		}
		if(path.startsWith("/api/"))
		{
			val body = try { if(raw.isNotEmpty()) JSONObject(String(raw, Charsets.UTF_8)) else JSONObject() } catch(e: Exception) { JSONObject() }
			val result = try { route(method, path, query, body) } catch(e: Exception) { Log.w(TAG, "route $path", e); error(e.message ?: "صار خطأ غير متوقع.") }
			when(result)
			{
				null -> send(out, 404, "application/json", "{\"error\":\"unknown\"}".toByteArray())
				is ByteArray -> send(out, 200, if(path == "/api/frame") "image/jpeg" else "image/png", result)
				is JSONObject -> send(out, if(result.has("error")) 400 else 200, "application/json; charset=utf-8", result.toString().toByteArray(Charsets.UTF_8))
				else -> send(out, 500, "text/plain", ByteArray(0))
			}
			return
		}
		val asset = when
		{
			path == "/" -> "web/index.html"
			path.startsWith("/fonts/") -> "fonts/" + path.substringAfterLast("/")
			else -> "web/" + path.trimStart('/')
		}
		if(".." in asset) return send(out, 404, "text/plain", ByteArray(0))
		val bytes = try { context.assets.open(asset).use { it.readBytes() } } catch(e: Exception) { null }
			?: return send(out, 404, "text/plain", ByteArray(0))
		val type = when(asset.substringAfterLast('.'))
		{
			"html" -> "text/html; charset=utf-8"; "css" -> "text/css; charset=utf-8"; "js" -> "text/javascript; charset=utf-8"
			"png" -> "image/png"; "ttf" -> "font/ttf"; else -> "application/octet-stream"
		}
		send(out, 200, type, bytes, cache = asset.startsWith("fonts/"))
	}

	private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = false)
	{
		val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: ${if(cache) "max-age=86400" else "no-store"}\r\nConnection: close\r\n\r\n"
		out.write(head.toByteArray(Charsets.US_ASCII))
		out.write(body)
		out.flush()
	}

	private fun events(out: OutputStream)
	{
		val queue = LinkedBlockingQueue<String>(400)
		streams.add(queue)
		try
		{
			out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\nConnection: keep-alive\r\n\r\nretry: 1500\n\n".toByteArray())
			out.flush()
			while(true)
			{
				val chunk = queue.poll(8, TimeUnit.SECONDS) ?: ": ping\n\n"
				out.write(chunk.toByteArray(Charsets.UTF_8))
				out.flush()
			}
		}
		catch(e: Exception)
		{
			// the page went away
		}
		finally
		{
			streams.remove(queue)
		}
	}

	companion object
	{
		private const val TAG = "Tarjim"
		@Volatile private var instance: PhoneServer? = null

		fun get(context: Context): PhoneServer = instance ?: synchronized(this) {
			instance ?: PhoneServer(context.applicationContext).also { instance = it }
		}
	}
}
