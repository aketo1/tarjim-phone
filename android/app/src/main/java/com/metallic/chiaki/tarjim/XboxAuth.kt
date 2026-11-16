package com.metallic.chiaki.tarjim

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Xbox sign-in and streaming token (experimental: not yet run against a console).
 *
 * The same chain as the laptop program's `sources/xbox.py`, ported from the open-source
 * `xal-node` library: Microsoft's device-code sign-in (the user types a short code at
 * microsoft.com/link, no password passes through this app), then Xbox Live user token,
 * XSTS token for the streaming service, and the home-streaming token with its region.
 * The refresh token is a secret and is kept in the app's private storage.
 */
class XboxAuth(private val store: Store)
{
	class Answer(val status: Int, val body: ByteArray)

	@Volatile var linkState = "idle"; private set   // idle | waiting | linked | failed
	@Volatile var linkError = ""; private set
	@Volatile var userCode = ""; private set
	@Volatile var verificationUri = "https://www.microsoft.com/link"; private set

	private var gsToken = ""
	private var gsHost = ""
	private var gsUntil = 0L

	val linked get() = store.xboxRefresh.isNotEmpty()

	fun forget()
	{
		store.xboxRefresh = ""
		store.xboxId = ""
		store.xboxName = ""
		synchronized(this) { gsToken = "" }
		linkState = "idle"
	}

	private fun form(vararg pairs: Pair<String, String>) =
		pairs.joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }.toByteArray(Charsets.US_ASCII)

	private fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>): Answer
	{
		val c = URL(url).openConnection() as HttpURLConnection
		c.requestMethod = method
		c.connectTimeout = 15000
		c.readTimeout = 20000
		for((k, v) in headers) c.setRequestProperty(k, v)
		if(body != null && method == "POST")
		{
			c.doOutput = true
			c.outputStream.use { it.write(body) }
		}
		val status = c.responseCode
		val stream = if(status in 200..299) c.inputStream else c.errorStream
		return Answer(status, stream?.use { it.readBytes() } ?: ByteArray(0))
	}

	private fun postJson(url: String, body: ByteArray, headers: Map<String, String>): JSONObject
	{
		val a = request("POST", url, body, headers)
		val text = String(a.body, Charsets.UTF_8)
		if(a.status !in 200..299) throw IOException("${URL(url).host} answered ${a.status}: ${text.take(200)}")
		return JSONObject(text.ifEmpty { "{}" })
	}

	private val formHeader = mapOf("Content-Type" to "application/x-www-form-urlencoded")

	/** Ask Microsoft for a short code; a background thread waits for the user to enter it. */
	fun startLink(): JSONObject
	{
		val answer = postJson("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", form("client_id" to CLIENT_ID, "scope" to SCOPE), formHeader)
		val device = answer.optString("device_code")
		userCode = answer.optString("user_code")
		if(device.isEmpty() || userCode.isEmpty()) throw IOException("Microsoft did not return a sign-in code")
		verificationUri = answer.optString("verification_uri", "https://www.microsoft.com/link")
		linkState = "waiting"
		linkError = ""
		val interval = answer.optLong("interval", 5).coerceAtLeast(2) * 1000
		val deadline = System.currentTimeMillis() + answer.optLong("expires_in", 900) * 1000
		Thread({
			val body = form("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to CLIENT_ID, "device_code" to device)
			while(System.currentTimeMillis() < deadline && linkState == "waiting")
			{
				Thread.sleep(interval)
				val a = try { request("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/token", body, formHeader) }
				catch(e: Exception) { continue }  // a network hiccup: keep waiting
				val text = String(a.body, Charsets.UTF_8)
				if(a.status in 200..299)
				{
					val refresh = JSONObject(text).optString("refresh_token")
					if(refresh.isNotEmpty())
					{
						store.xboxRefresh = refresh
						synchronized(this) { gsToken = "" }
						linkState = "linked"
						return@Thread
					}
				}
				else if("authorization_pending" !in text && "slow_down" !in text)
				{
					linkError = text.take(160)
					linkState = "failed"
					return@Thread
				}
			}
			if(linkState == "waiting") { linkError = "the code expired"; linkState = "failed" }
		}, "tarjim-xbox-link").apply { isDaemon = true; start() }
		return JSONObject().put("code", userCode).put("url", verificationUri)
	}

	/** The streaming token and the region's API host, cached until shortly before it expires. */
	@Synchronized
	private fun streaming(): Pair<String, String>
	{
		if(gsToken.isNotEmpty() && gsUntil > System.currentTimeMillis() + 120_000) return Pair(gsToken, gsHost)
		val refresh = store.xboxRefresh
		if(refresh.isEmpty()) throw IOException("not linked to a Microsoft account")
		val token = postJson("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
			form("client_id" to CLIENT_ID, "grant_type" to "refresh_token", "refresh_token" to refresh, "scope" to SCOPE), formHeader)
		token.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.xboxRefresh = it }  // Microsoft rotates it
		val access = token.optString("access_token")
		if(access.isEmpty()) throw IOException("Microsoft did not return an access token")
		val xbl = mapOf("x-xbl-contract-version" to "1", "Cache-Control" to "no-cache", "Content-Type" to "application/json",
			"Origin" to "https://www.xbox.com", "Referer" to "https://www.xbox.com/")
		val user = postJson("https://user.auth.xboxlive.com/user/authenticate", JSONObject()
			.put("Properties", JSONObject().put("AuthMethod", "RPS").put("RpsTicket", "d=$access").put("SiteName", "user.auth.xboxlive.com"))
			.put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT").toString().toByteArray(), xbl)
		val xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", JSONObject()
			.put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(user.getString("Token"))))
			.put("RelyingParty", "http://gssv.xboxlive.com/").put("TokenType", "JWT").toString().toByteArray(),
			xbl + mapOf("Accept" to "*/*", "ms-cv" to "0", "User-Agent" to BROWSER))
		val home = postJson("https://xhome.gssv-play-prod.xboxlive.com/v2/login/user",
			JSONObject().put("token", xsts.getString("Token")).put("offeringId", "xhome").toString().toByteArray(),
			mapOf("Content-Type" to "application/json", "Cache-Control" to "no-store, must-revalidate, no-cache", "x-gssv-client" to "XboxComBrowser"))
		val regions = home.optJSONObject("offeringSettings")?.optJSONArray("regions") ?: JSONArray()
		var region: JSONObject? = null
		for(i in 0 until regions.length()) { val r = regions.getJSONObject(i); if(region == null || r.optBoolean("isDefault")) region = r }
		val gs = home.optString("gsToken")
		val base = region?.optString("baseUri").orEmpty()
		if(gs.isEmpty() || base.isEmpty()) throw IOException("Xbox did not return a streaming token")
		gsToken = gs
		gsHost = URL(base).host
		gsUntil = System.currentTimeMillis() + home.optLong("durationInSeconds", 3600) * 1000
		return Pair(gsToken, gsHost)
	}

	/** Forward one of the page's streaming API calls (/v5/..., /v6/...) to Xbox with the token added. */
	fun proxy(method: String, pathAndQuery: String, body: ByteArray?, deviceInfo: String?): Answer
	{
		val (token, host) = streaming()
		val headers = HashMap<String, String>()
		headers["Authorization"] = "Bearer $token"
		headers["Content-Type"] = "application/json; charset=utf-8"
		headers["Accept"] = "application/json"
		if(deviceInfo != null) headers["X-MS-Device-Info"] = deviceInfo
		return request(method, "https://$host$pathAndQuery", body, headers)
	}

	fun consoles(): JSONArray
	{
		val a = proxy("GET", "/v6/servers/home", null, null)
		if(a.status != 200) throw IOException("listing consoles answered ${a.status}")
		val results = JSONObject(String(a.body, Charsets.UTF_8)).optJSONArray("results") ?: JSONArray()
		val out = JSONArray()
		for(i in 0 until results.length())
		{
			val r = results.getJSONObject(i)
			out.put(JSONObject().put("id", r.optString("serverId")).put("name", r.optString("deviceName", "Xbox"))
				.put("type", r.optString("consoleType")).put("power", r.optString("powerState")))
		}
		return out
	}

	private companion object
	{
		const val CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"  // the public client xal-node uses
		const val SCOPE = "xboxlive.signin openid profile offline_access"
		const val BROWSER = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
	}
}
