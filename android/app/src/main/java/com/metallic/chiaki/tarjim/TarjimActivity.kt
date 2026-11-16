package com.metallic.chiaki.tarjim

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.metallic.chiaki.lib.ConnectInfo

/**
 * The Tarjim screen: where the TV is, one start/stop button, the state of the three
 * parts (PS5, translator, TV) and the line being shown. Built in code, in Tarjim's look.
 */
class TarjimActivity : Activity()
{
	private var connectInfo: ConnectInfo? = null
	private lateinit var tvField: EditText
	private lateinit var button: TextView
	private lateinit var rowConsole: Row
	private lateinit var rowTranslator: Row
	private lateinit var rowTv: Row
	private lateinit var stats: TextView
	private lateinit var arabic: TextView
	private lateinit var english: TextView
	private lateinit var arabicFace: Typeface

	private class Row(val dot: View, val text: TextView)

	private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

	private fun box(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
		setColor(color)
		cornerRadius = dp(radius).toFloat()
		if(stroke != 0) setStroke(dp(1f), stroke)
	}

	private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
		this.text = text
		setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
		setTextColor(color)
		if(bold) typeface = Typeface.DEFAULT_BOLD
		textDirection = View.TEXT_DIRECTION_ANY_RTL
		textAlignment = View.TEXT_ALIGNMENT_VIEW_START
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		@Suppress("DEPRECATION")
		connectInfo = intent.getParcelableExtra(EXTRA_CONNECT_INFO)
		arabicFace = Typeface.createFromAsset(assets, "Harmattan-Regular.ttf")
		window.statusBarColor = INK
		window.navigationBarColor = INK
		window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

		val page = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutDirection = View.LAYOUT_DIRECTION_RTL
			setPadding(dp(22f), dp(26f), dp(22f), dp(30f))
		}
		page.addView(label("ترجِم", 30f, PAPER, true))
		page.addView(label("ترجمة عربية فوق لعبتك، من الجوال مباشرة.", 14.5f, MUTED))

		// the line on the TV right now
		val screen = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER
			background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
				intArrayOf(Color.rgb(16, 18, 44), Color.rgb(125, 65, 105), Color.rgb(21, 18, 31))).apply { cornerRadius = dp(18f).toFloat() }
			setPadding(dp(16f), dp(34f), dp(16f), dp(20f))
			minimumHeight = dp(170f)
		}
		arabic = TextView(this).apply {
			typeface = arabicFace
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
			setTextColor(Color.rgb(242, 247, 241))
			gravity = Gravity.CENTER
			setShadowLayer(6f, 2f, 2f, Color.BLACK)
			text = "هنا يطلع السطر اللي على التلفزيون"
		}
		english = TextView(this).apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
			setTextColor(Color.argb(190, 255, 255, 255))
			gravity = Gravity.CENTER
			textDirection = View.TEXT_DIRECTION_LTR
		}
		screen.addView(arabic, LinearLayout.LayoutParams(MATCH, WRAP))
		screen.addView(english, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })
		page.addView(screen, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20f) })

		button = label("", 19f, AMBER_INK, true).apply {
			gravity = Gravity.CENTER
			isClickable = true
			isFocusable = true
			setOnClickListener { toggle() }
		}
		page.addView(button, LinearLayout.LayoutParams(MATCH, dp(62f)).apply { topMargin = dp(16f) })

		val card = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			background = box(CARD, 18f, LINE)
			setPadding(dp(18f), dp(10f), dp(18f), dp(12f))
		}
		rowConsole = row(card, "الـPS5")
		rowTranslator = row(card, "المترجم")
		rowTv = row(card, "التلفزيون")
		stats = label("", 12.5f, MUTED)
		card.addView(stats, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6f) })
		page.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16f) })

		page.addView(label("عنوان التلفزيون", 13f, MUTED), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20f) })
		tvField = EditText(this).apply {
			setText(prefs().getString("tv", ""))
			hint = "192.168.1.50"
			inputType = InputType.TYPE_CLASS_PHONE
			setTextColor(PAPER)
			setHintTextColor(Color.rgb(93, 96, 112))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			textDirection = View.TEXT_DIRECTION_LTR
			background = box(CARD, 14f, LINE)
			setPadding(dp(16f), dp(13f), dp(16f), dp(13f))
		}
		page.addView(tvField, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6f) })
		page.addView(label("تلقاه في تطبيق ترجِم على التلفزيون. لازم التطبيق يكون مثبّت على التلفزيون (يتثبّت مرة وحدة من نسخة اللابتوب).", 12.5f, MUTED),
			LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6f) })

		val scroll = ScrollView(this).apply { setBackgroundColor(INK); isFillViewport = true }
		scroll.addView(page)
		setContentView(scroll)

		if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
	}

	private fun row(parent: LinearLayout, name: String): Row
	{
		val line = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(0, dp(9f), 0, dp(9f))
		}
		val dot = View(this)
		line.addView(dot, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginEnd = dp(12f) })
		val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		column.addView(label(name, 15f, PAPER, true))
		val text = label("—", 13f, MUTED)
		column.addView(text)
		line.addView(column, LinearLayout.LayoutParams(0, WRAP, 1f))
		parent.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
		return Row(dot, text)
	}

	private fun prefs() = getSharedPreferences("tarjim", Context.MODE_PRIVATE)

	private fun toggle()
	{
		if(TarjimService.running)
		{
			stopService(Intent(this, TarjimService::class.java))
			return
		}
		val info = connectInfo
		val tv = tvField.text.toString().trim()
		if(!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(tv))
		{
			tvField.error = "اكتب عنوان التلفزيون، مثل 192.168.1.50"
			return
		}
		if(info == null)
		{
			finish()  // opened from the notification after the app was closed: pick the console again
			return
		}
		prefs().edit().putString("tv", tv).apply()
		val intent = Intent(this, TarjimService::class.java).putExtra(TarjimService.EXTRA_CONNECT_INFO, info).putExtra(TarjimService.EXTRA_TV, tv)
		if(Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
		render(TarjimEngine.Status())
	}

	override fun onResume()
	{
		super.onResume()
		TarjimService.listener = { render(it) }
		render(TarjimService.status)
	}

	override fun onPause()
	{
		TarjimService.listener = null
		super.onPause()
	}

	private fun render(s: TarjimEngine.Status)
	{
		val on = TarjimService.running
		button.text = if(on) "إيقاف الترجمة" else "ابدأ الترجمة"
		button.background = if(on) box(CARD, 18f, LINE) else box(AMBER, 18f)
		button.setTextColor(if(on) PAPER else AMBER_INK)
		tvField.isEnabled = !on
		fun set(row: Row, ok: Boolean, text: String)
		{
			row.dot.background = box(if(!on) Color.rgb(93, 96, 112) else if(ok) LIVE else AMBER, 5f)
			row.text.text = if(on) text else "—"
		}
		set(rowConsole, s.consoleOk && s.fps > 0, s.console)
		set(rowTranslator, s.translatorOk, s.translator)
		set(rowTv, s.tvOk, s.tv)
		stats.text = if(on) "${s.fps} صورة بالثانية · ${s.lines} سطر" + (if(s.lastMs > 0) " · آخر سطر ${s.lastMs} ملّي ثانية" else "") else ""
		if(s.arabic.isNotEmpty())
		{
			arabic.text = s.arabic
			english.text = s.english
		}
	}

	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
		private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
		private val INK = Color.rgb(10, 11, 15)
		private val CARD = Color.rgb(17, 19, 25)
		private val LINE = Color.rgb(38, 40, 50)
		private val PAPER = Color.rgb(243, 241, 234)
		private val MUTED = Color.rgb(142, 145, 159)
		private val AMBER = Color.rgb(255, 194, 51)
		private val AMBER_INK = Color.rgb(28, 20, 0)
		private val LIVE = Color.rgb(55, 211, 154)
	}
}
