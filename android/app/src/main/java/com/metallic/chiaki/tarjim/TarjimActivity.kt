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
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import com.metallic.chiaki.common.fileProviderAuthority
import com.metallic.chiaki.regist.RegistActivity
import org.json.JSONObject
import java.io.File

/**
 * Tarjim's screen on the phone: the same interface as the laptop program, served by
 * [PhoneServer] and shown in a WebView. This class adds what a page cannot do itself:
 * keeping the screen awake, the black "resting" screen, opening the PS5 linking form, picking
 * and sharing game files, and taking a game file opened from WhatsApp or Files.
 */
class TarjimActivity : Activity()
{
	private lateinit var web: WebView
	private var normalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
	private var fileCallback: ValueCallback<Array<Uri>>? = null
	private var pageReady = false
	private var pendingPack: String? = null

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		// the page is a night sky over dunes: the status bar takes the top of the sky, the navigation bar the dunes
		window.statusBarColor = Color.rgb(4, 5, 15)
		window.navigationBarColor = Color.rgb(7, 5, 15)
		val server = PhoneServer.get(this)
		web = WebView(this)
		web.setBackgroundColor(Color.rgb(7, 10, 28))
		web.settings.javaScriptEnabled = true
		web.settings.domStorageEnabled = true
		web.settings.mediaPlaybackRequiresUserGesture = false  // the Xbox stream's video must start by itself
		web.overScrollMode = View.OVER_SCROLL_NEVER
		web.addJavascriptInterface(Bridge(), "TarjimApp")
		web.webViewClient = object : WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != "127.0.0.1"

			override fun onPageFinished(view: WebView, url: String)
			{
				pageReady = true
				deliverPack()
			}
		}
		// <input type="file"> in the page ("import a game file") needs Android's own file picker.
		web.webChromeClient = object : WebChromeClient()
		{
			override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean
			{
				fileCallback?.onReceiveValue(null)
				fileCallback = callback
				val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
				try { startActivityForResult(Intent.createChooser(pick, "ملف لعبة من ترجِم"), PICK_FILE) }
				catch(e: Exception) { fileCallback = null; callback.onReceiveValue(null) }
				return true
			}
		}
		setContentView(web)
		web.loadUrl(server.url)
		if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
		takeFile(intent)
	}

	override fun onNewIntent(intent: Intent)
	{
		super.onNewIntent(intent)
		setIntent(intent)
		takeFile(intent)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		super.onActivityResult(requestCode, resultCode, data)
		if(requestCode != PICK_FILE) return
		fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
		fileCallback = null
	}

	/** A game file opened with this app ("Open with ترجِم", or shared to it): the page asks where it goes. */
	private fun takeFile(intent: Intent?)
	{
		val uri: Uri = when(intent?.action)
		{
			Intent.ACTION_VIEW -> intent.data
			Intent.ACTION_SEND ->
				if(Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
				else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
			else -> null
		} ?: return
		intent?.action = Intent.ACTION_MAIN  // taken: coming back to the app must not import it again
		val text = try
		{
			contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 8_000_000 }?.toString(Charsets.UTF_8) }
		}
		catch(e: Exception) { Log.w("Tarjim", "could not read the opened file", e); null } ?: return
		pendingPack = text
		deliverPack()
	}

	private fun deliverPack()
	{
		val text = pendingPack ?: return
		if(!pageReady) return
		pendingPack = null
		web.evaluateJavascript("window.onPackFile && window.onPackFile(${JSONObject.quote(text)})", null)
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

		/** A game file the page made: Android's share sheet (WhatsApp, Files, Drive, ...). */
		@JavascriptInterface
		fun shareFile(name: String, text: String) = runOnUiThread {
			try
			{
				val dir = File(cacheDir, "packs").apply { mkdirs() }
				dir.listFiles()?.forEach { it.delete() }  // only the newest is kept
				val safe = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]+"), " ").trim().ifEmpty { "game - tarjim.json" }
				val file = File(dir, safe)
				file.writeText(text)
				val uri = FileProvider.getUriForFile(this@TarjimActivity, fileProviderAuthority, file)
				val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
					.putExtra(Intent.EXTRA_SUBJECT, safe.removeSuffix(".json")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				startActivity(Intent.createChooser(send, "أرسل ملف اللعبة"))
			}
			catch(e: Exception)
			{
				Log.w("Tarjim", "could not share the game file", e)
				web.evaluateJavascript("toast('ما قدرت أجهّز الملف للمشاركة.')", null)
			}
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

	companion object
	{
		private const val PICK_FILE = 41
	}
}
