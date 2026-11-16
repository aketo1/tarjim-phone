package com.metallic.chiaki.tarjim

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything Tarjim remembers on the phone: the two addresses, one profile per game
 * (look, timing, names) and the lines the user rewrote. Plain JSON files in the app's
 * private folder; the same keys as the laptop program's profiles.
 */
class Store(private val context: Context)
{
	private val prefs = context.getSharedPreferences("tarjim", Context.MODE_PRIVATE)
	private val profilesDir = File(context.filesDir, "profiles").apply { mkdirs() }
	private val fixesFile = File(context.filesDir, "fixes.json")

	var tv: String
		get() = prefs.getString("tv", "") ?: ""
		set(v) { prefs.edit().putString("tv", v.trim()).apply() }
	var console: String
		get() = prefs.getString("console", "") ?: ""
		set(v) { prefs.edit().putString("console", v.trim()).apply() }
	var lastProfile: String
		get() = prefs.getString("profile", "default") ?: "default"
		set(v) { prefs.edit().putString("profile", v).apply() }

	init
	{
		if(!File(profilesDir, "default.json").exists())
			save("default", JSONObject().put("title", "أي لعبة (عام)"))
		if(!File(profilesDir, "rdr2.json").exists() && !prefs.getBoolean("seeded", false))
		{
			val names = JSONObject()
			for((en, ar) in RDR2) names.put(en, ar)
			save("rdr2", JSONObject().put("title", "Red Dead Redemption 2").put("glossary", names)
				.put("female", JSONArray(listOf("Susan", "Grimshaw", "Mary-Beth", "Mary Beth", "Tilly", "Karen", "Abigail", "Sadie"))).put("settle_ms", 40))
			prefs.edit().putBoolean("seeded", true).apply()
		}
	}

	fun profiles(): List<Pair<String, String>> =
		(profilesDir.listFiles { f -> f.name.endsWith(".json") } ?: arrayOf()).sortedBy { it.name }.map {
			val name = it.name.removeSuffix(".json")
			Pair(name, try { JSONObject(it.readText()).optString("title", name) } catch(e: Exception) { name })
		}

	fun load(name: String): JSONObject
	{
		val file = File(profilesDir, "${safe(name)}.json")
		return try { if(file.exists()) JSONObject(file.readText()) else JSONObject() } catch(e: Exception) { JSONObject() }
	}

	fun save(name: String, profile: JSONObject)
	{
		File(profilesDir, "${safe(name)}.json").writeText(profile.toString(1))
	}

	fun create(title: String): String
	{
		var slug = title.lowercase().map { if(it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("").trim('-').replace(Regex("-+"), "-").take(30)
		if(slug.isEmpty()) slug = "game-${System.currentTimeMillis() % 100000}"
		if(File(profilesDir, "$slug.json").exists()) slug = "$slug-${System.currentTimeMillis() % 1000}"
		save(slug, JSONObject().put("title", title.ifBlank { slug }).put("glossary", JSONObject()))
		return slug
	}

	private fun safe(name: String) = name.filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }.ifEmpty { "default" }

	// -- the user's own wording for a line ---------------------------------------------

	private val fixes: JSONObject by lazy { try { JSONObject(fixesFile.readText()) } catch(e: Exception) { JSONObject() } }

	@Synchronized fun fix(profile: String, english: String): String? =
		fixes.optJSONObject(profile)?.optString(key(english), "")?.takeIf { it.isNotEmpty() }

	@Synchronized fun setFix(profile: String, english: String, arabic: String)
	{
		val map = fixes.optJSONObject(profile) ?: JSONObject().also { fixes.put(profile, it) }
		if(arabic.isBlank()) map.remove(key(english)) else map.put(key(english), arabic.trim())
		fixesFile.writeText(fixes.toString())
	}

	private fun key(english: String) = english.trim().replace(Regex("\\s+"), " ")

	companion object
	{
		private val RDR2 = listOf(
			"Arthur" to "آرثر", "Morgan" to "مورغان", "Dutch" to "دتش", "Micah" to "مايكا", "Hosea" to "هوزيا",
			"Pearson" to "بيرسون", "Susan" to "سوزان", "Grimshaw" to "غريمشو", "Mary-Beth" to "ماري بيث",
			"Mary Beth" to "ماري بيث", "Tilly" to "تيلي", "Karen" to "كارين", "Abigail" to "أبيغيل", "Marston" to "مارستن",
			"Javier" to "خافيير", "Charles" to "تشارلز", "Lenny" to "ليني", "Sadie" to "سيدي", "Strauss" to "شتراوس",
			"Swanson" to "سوانسون", "Trelawny" to "تريلوني", "Valentine" to "فالنتاين", "Blackwater" to "بلاكووتر",
			"van der Linde" to "فان دير ليند", "Bill" to "بيل", "Sean" to "شون", "Uncle" to "العم", "Molly" to "مولي")
	}
}

/** How the Arabic looks and when a line counts as complete; read from a profile. */
class Look(profile: JSONObject)
{
	val font: String = profile.optString("font", "harmattan")
	val fontSize: Int = profile.optInt("font_size", 0)  // 0: follow the game's own subtitle size
	val background: String = profile.optString("background", "plate").let { if(it == "none") "none" else "plate" }
	val plateOpacity: Float = profile.optDouble("plate_opacity", 0.78).toFloat().coerceIn(0.2f, 0.95f)
	val textColor: Int = parseColor(profile.optString("text_color", "auto"))
	val effect: String = profile.optString("effect", "shadow")
	val yOffset: Int = profile.optInt("y_offset", 0).coerceIn(-300, 120)
	val readScale: Float = profile.optDouble("read_scale", 1.0).toFloat().coerceIn(0.5f, 2.5f)
	val settleMs: Long = profile.optDouble("settle_ms", 60.0).toLong().coerceIn(0, 600)
	val left: Float
	val top: Float
	val right: Float
	val bottom: Float
	val glossary: Map<String, String>
	val female: Set<String>

	init
	{
		val r = profile.optJSONObject("region")
		left = (r?.optDouble("left", 0.08) ?: 0.08).toFloat().coerceIn(0f, 0.9f)
		top = (r?.optDouble("top", 0.68) ?: 0.68).toFloat().coerceIn(0f, 0.92f)
		right = (r?.optDouble("right", 0.92) ?: 0.92).toFloat().coerceIn(left + 0.1f, 1f)
		bottom = (r?.optDouble("bottom", 1.0) ?: 1.0).toFloat().coerceIn(top + 0.08f, 1f)
		val g = profile.optJSONObject("glossary") ?: JSONObject()
		glossary = g.keys().asSequence().associateWith { g.optString(it) }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
		val f = profile.optJSONArray("female") ?: JSONArray()
		female = (0 until f.length()).map { f.optString(it) }.toSet()
	}

	companion object
	{
		const val AUTO_COLOR = 0xFFF2F7F1.toInt()

		fun parseColor(value: String): Int = try
		{
			if(value.length == 7 && value.startsWith("#")) (0xFF000000 or value.substring(1).toLong(16)).toInt() else AUTO_COLOR
		}
		catch(e: NumberFormatException) { AUTO_COLOR }

		val FONTS = mapOf("harmattan" to "Harmattan-Regular.ttf", "harmattan-bold" to "Harmattan-Bold.ttf",
			"noto-naskh" to "NotoNaskhArabic-VF.ttf", "noto-sans-arabic" to "NotoSansArabic-VF.ttf")
	}
}
