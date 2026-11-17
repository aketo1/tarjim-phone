package com.metallic.chiaki.tarjim

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.metallic.chiaki.regist.RegistActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Step one of linking the PS5: the user signs in to the SECOND PSN account on Sony's own
 * page, and Tarjim learns that account's ID (the number Remote Play registration needs).
 *
 * The password is typed into Sony's page only; this code sees just the one-time code Sony
 * puts in the redirect address, exchanges it for the account's ID, and keeps nothing else.
 * On success it opens the form for step two (the PIN from the console) with the ID filled in.
 */
class PsnLoginActivity : Activity()
{
	private lateinit var web: WebView
	private lateinit var note: TextView
	private var done = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window.statusBarColor = INK
		val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(INK); layoutDirection = View.LAYOUT_DIRECTION_RTL }
		note = TextView(this).apply {
			text = "الخطوة 1 من 2: سجّل دخول بالحساب الثاني (مو الحساب اللي تلعب فيه). كلمة السر تنكتب في صفحة Sony نفسها وترجِم ما يشوفها."
			setTextColor(Color.rgb(243, 241, 234))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
			typeface = Typeface.DEFAULT_BOLD
			gravity = Gravity.START
			textDirection = View.TEXT_DIRECTION_ANY_RTL
			val pad = (resources.displayMetrics.density * 16).toInt()
			setPadding(pad, pad, pad, pad)
		}
		root.addView(note, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
		web = WebView(this)
		web.settings.javaScriptEnabled = true
		web.settings.domStorageEnabled = true
		// A fresh sign-in every time: otherwise Sony silently reuses whichever account was used last.
		CookieManager.getInstance().removeAllCookies(null)
		web.webViewClient = object : WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = caught(request.url.toString())
			override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { caught(url) }
		}
		root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
		setContentView(root)
		web.loadUrl(LOGIN_URL)
	}

	/** Sony sends the browser to the redirect address with `?code=...` once the sign-in succeeded. */
	private fun caught(url: String): Boolean
	{
		if(!url.startsWith(REDIRECT_URL)) return false
		if(done) return true
		done = true
		val code = android.net.Uri.parse(url).getQueryParameter("code")
		web.stopLoading()
		web.visibility = View.INVISIBLE
		note.text = "لحظة… أجيب معرّف الحساب."
		Thread({
			val result = try { if(code.isNullOrEmpty()) null else accountId(code) } catch(e: Exception) { Log.w("Tarjim", "PSN account lookup failed", e); null }
			runOnUiThread {
				if(result == null)
				{
					note.text = "ما قدرت أجيب معرّف الحساب. ارجع وجرّب مرة ثانية (الرمز يصلح دقايق بس)."
					done = false
					web.visibility = View.VISIBLE
					web.loadUrl(LOGIN_URL)
					return@runOnUiThread
				}
				val store = Store(this)
				store.account = result.first
				store.accountName = result.second
				startActivity(Intent(this, RegistActivity::class.java)
					.putExtra(RegistActivity.EXTRA_HOST, intent.getStringExtra(RegistActivity.EXTRA_HOST) ?: store.console)
					.putExtra(RegistActivity.EXTRA_BROADCAST, false)
					.putExtra(RegistActivity.EXTRA_ACCOUNT_ID, result.first))
				finish()
			}
		}, "tarjim-psn").start()
		return true
	}

	/** (account ID as Remote Play wants it: the 64-bit user id, little-endian, base64; and the online name). */
	private fun accountId(code: String): Pair<String, String>?
	{
		val auth = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
		val post = URL(TOKEN_URL).openConnection() as HttpURLConnection
		post.requestMethod = "POST"
		post.connectTimeout = 8000
		post.readTimeout = 8000
		post.doOutput = true
		post.setRequestProperty("Authorization", auth)
		post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
		post.outputStream.use { it.write("grant_type=authorization_code&code=$code&redirect_uri=$REDIRECT_URL&".toByteArray(Charsets.US_ASCII)) }
		if(post.responseCode != 200) return null
		val token = JSONObject(post.inputStream.bufferedReader().readText()).optString("access_token")
		if(token.isEmpty()) return null
		val get = URL("$TOKEN_URL/$token").openConnection() as HttpURLConnection
		get.connectTimeout = 8000
		get.readTimeout = 8000
		get.setRequestProperty("Authorization", auth)
		if(get.responseCode != 200) return null
		val info = JSONObject(get.inputStream.bufferedReader().readText())
		val user = info.optString("user_id").toLongOrNull() ?: return null
		val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(user).array()
		return Pair(Base64.encodeToString(bytes, Base64.NO_WRAP), info.optString("online_id"))
	}

	private companion object
	{
		val INK = Color.rgb(11, 11, 13)  // the app's own near-black
		// The public client of Sony's own Remote Play program, as used by every open-source client.
		const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		const val REDIRECT_URL = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
		const val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
			"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URL" +
			"&scope=psn:clientapp&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay" +
			"&prompt=always&PlatformPrivacyWs1=minimal&no_captcha=true&"
	}
}
