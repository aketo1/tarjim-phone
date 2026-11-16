package com.metallic.chiaki.tarjim

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metallic.chiaki.regist.RegistActivity

/**
 * Tarjim's screen on the phone: the same interface as the laptop program, served by
 * [PhoneServer] and shown in a WebView. This class adds what a page cannot do itself:
 * keeping the screen awake, the black "resting" screen, opening the PS5 linking form.
 */
class TarjimActivity : Activity()
{
	private lateinit var web: WebView
	private var normalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window.statusBarColor = Color.rgb(10, 11, 15)
		window.navigationBarColor = Color.rgb(17, 19, 25)
		val server = PhoneServer.get(this)
		web = WebView(this)
		web.setBackgroundColor(Color.rgb(10, 11, 15))
		web.settings.javaScriptEnabled = true
		web.settings.domStorageEnabled = true
		web.overScrollMode = View.OVER_SCROLL_NEVER
		web.addJavascriptInterface(Bridge(), "TarjimApp")
		web.webViewClient = object : WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != "127.0.0.1"
		}
		setContentView(web)
		web.loadUrl(server.url)
		if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
	}

	override fun onResume()
	{
		super.onResume()
		web.evaluateJavascript("window.onAppResume && window.onAppResume()", null)  // e.g. back from linking the PS5
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed()
	{
		// Back leaves the black screen or returns to the home view before it leaves the app.
		web.evaluateJavascript("(window.onAppBack && window.onAppBack()) ? 1 : 0") { handled ->
			if(handled != "1") moveTaskToBack(true)
		}
	}

	/** What the pages may ask the app to do. */
	inner class Bridge
	{
		/** Keep the screen on. Android slows a hidden app down so much that lines arrive seconds late. */
		@JavascriptInterface
		fun keepAwake(on: Boolean) = runOnUiThread {
			if(on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		}

		/** The black resting screen: lowest brightness and no system bars; an OLED panel then draws almost no power. */
		@JavascriptInterface
		fun dim(on: Boolean) = runOnUiThread {
			val params = window.attributes
			params.screenBrightness = if(on) 0.01f else normalBrightness
			window.attributes = params
			@Suppress("DEPRECATION")
			window.decorView.systemUiVisibility = if(on)
				View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
			else View.SYSTEM_UI_FLAG_VISIBLE
		}

		/**
		 * Link this phone to the PS5. First time: Sony's sign-in for the second account (which gives
		 * its Account ID), then the PIN form. `fresh` forces the sign-in again (another account).
		 */
		@JavascriptInterface
		fun register(host: String, fresh: Boolean) = runOnUiThread {
			val account = Store(this@TarjimActivity).account
			if(account.isEmpty() || fresh)
				startActivity(Intent(this@TarjimActivity, PsnLoginActivity::class.java).putExtra(RegistActivity.EXTRA_HOST, host))
			else
				startActivity(Intent(this@TarjimActivity, RegistActivity::class.java).putExtra(RegistActivity.EXTRA_HOST, host)
					.putExtra(RegistActivity.EXTRA_BROADCAST, false).putExtra(RegistActivity.EXTRA_ACCOUNT_ID, account))
		}

		@JavascriptInterface
		fun batteryExempt(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

		/** Ask Android not to restrict this app in the background. */
		@SuppressLint("BatteryLife")
		@JavascriptInterface
		fun askBattery() = runOnUiThread {
			try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
			catch(e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
		}
	}
}
