package com.metallic.chiaki.tarjim

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

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
	/** The second PSN account's ID as Remote Play wants it (base64), learned from Sony's sign-in. */
	var account: String
		get() = prefs.getString("account", "") ?: ""
		set(v) { prefs.edit().putString("account", v).apply() }
	var accountName: String
		get() = prefs.getString("account_name", "") ?: ""
		set(v) { prefs.edit().putString("account_name", v).apply() }
	/** "ps5" or "xbox": which console the picture comes from. */
	var kind: String
		get() = prefs.getString("kind", "ps5") ?: "ps5"
		set(v) { prefs.edit().putString("kind", if(v == "xbox") "xbox" else "ps5").apply() }
	var xboxId: String
		get() = prefs.getString("xbox_id", "") ?: ""
		set(v) { prefs.edit().putString("xbox_id", v).apply() }
	var xboxName: String
		get() = prefs.getString("xbox_name", "") ?: ""
		set(v) { prefs.edit().putString("xbox_name", v).apply() }
	/** Microsoft refresh token: a secret, private to the app. */
	var xboxRefresh: String
		get() = prefs.getString("xbox_refresh", "") ?: ""
		set(v) { prefs.edit().putString("xbox_refresh", v).apply() }
	var lastProfile: String
		get() = prefs.getString("profile", "default") ?: "default"
		set(v) { prefs.edit().putString("profile", v).apply() }

	init
	{
		if(!File(profilesDir, "default.json").exists())
			save("default", JSONObject().put("title", "أي لعبة (عام)"))
		if(!File(profilesDir, "rdr2.json").exists() && !prefs.getBoolean("seeded", false))
		{
			// A first start comes with the game the app was made for, from the bundled library.
			try { val packs = Packs(context, this); packs.merge(packs.libraryPack("rdr2"), null) }
			catch(e: Exception) { save("rdr2", JSONObject().put("title", "Red Dead Redemption 2").put("glossary", JSONObject())) }
			prefs.edit().putBoolean("seeded", true).apply()
		}
	}

	fun exists(name: String) = File(profilesDir, "${safe(name)}.json").exists()

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
	// One copy for the whole app: the running session has its own Store, and a line the
	// user rewrote must reach it at once (each Store used to keep its own copy).

	private fun fixes(): JSONObject = shared ?: synchronized(lock) {
		shared ?: load().also { shared = it }
	}

	/** The file as written, with every line's key in today's form (older versions kept curly quotes). */
	private fun load(): JSONObject
	{
		val raw = try { JSONObject(fixesFile.readText()) } catch(e: Exception) { JSONObject() }
		val clean = JSONObject()
		for(profile in raw.keys())
		{
			val src = raw.optJSONObject(profile) ?: continue
			val dst = JSONObject()
			for(k in src.keys()) dst.put(key(k), src.optString(k))
			clean.put(profile, dst)
		}
		return clean
	}

	fun fix(profile: String, english: String): String? = synchronized(lock) {
		fixes().optJSONObject(profile)?.optString(key(english), "")?.takeIf { it.isNotEmpty() }
	}

	fun setFix(profile: String, english: String, arabic: String) = synchronized(lock) {
		val all = fixes()
		val map = all.optJSONObject(profile) ?: JSONObject().also { all.put(profile, it) }
		if(arabic.isBlank()) map.remove(key(english)) else map.put(key(english), arabic.trim())
		fixesFile.writeText(all.toString())
	}

	/** Every line the user rewrote for this game. */
	fun fixesOf(profile: String): List<Pair<String, String>> = synchronized(lock) {
		val map = fixes().optJSONObject(profile) ?: return@synchronized emptyList()
		map.keys().asSequence().map { Pair(it, map.optString(it)) }.filter { it.second.isNotEmpty() }.toList()
	}

	/** Many lines at once, one write; a line that already has the user's wording keeps it. Returns how many were added. */
	fun addFixes(profile: String, rows: List<Pair<String, String>>): Int = synchronized(lock) {
		val all = fixes()
		val map = all.optJSONObject(profile) ?: JSONObject().also { all.put(profile, it) }
		var added = 0
		for((en, ar) in rows)
		{
			val k = key(en)
			if(k.isEmpty() || ar.isBlank() || map.has(k)) continue
			map.put(k, ar.trim())
			added++
		}
		if(added > 0) fixesFile.writeText(all.toString())
		added
	}

	companion object
	{
		private val lock = Any()
		@Volatile private var shared: JSONObject? = null

		/** The same key as the laptop program's: one spacing, straight quotes; case is kept. */
		fun key(english: String) = english.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
			.trim().replace(Regex("\\s+"), " ")
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

	/** The glossary as compiled patterns, longest name first ("Mary Beth" before "Mary"). Built once per list. */
	val names: List<Triple<String, String, Regex>> by lazy {
		glossary.entries.sortedByDescending { it.key.length }.map { Triple(it.key, it.value, namePattern(it.key)) }
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

		/** A regex for this text where straight and curly apostrophes count as the same. */
		private fun literal(text: String) = buildString {
			for(c in text) when
			{
				c == '\'' || c == '’' -> append("['’]")
				c.isLetterOrDigit() || c == ' ' -> append(c)
				else -> append('\\').append(c)
			}
		}

		/**
		 * How a glossary name is found in a line, the same rule as the laptop program's: a name
		 * that starts with a capital ("Chop", "Families") must appear with that capital or all in
		 * capitals, so the same word in small letters ("chop the wood") is left to the translator;
		 * a name written in small letters ("van der Linde", "clicker") is found in any case.
		 */
		fun namePattern(en: String): Regex
		{
			val first = en.firstOrNull { it.isLetter() }
			if(first == null || !first.isUpperCase())
				return Regex("(?<![A-Za-z])${literal(en)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
			val exact = literal(en)
			val upper = literal(en.uppercase(Locale.ROOT))
			return Regex("(?<![A-Za-z])" + (if(exact == upper) exact else "(?:$exact|$upper)") + "(?![A-Za-z])")
		}
	}
}
