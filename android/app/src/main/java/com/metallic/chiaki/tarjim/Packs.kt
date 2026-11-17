package com.metallic.chiaki.tarjim

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Game files: one game's names, the player's own line fixes and where its subtitles sit, in a
 * JSON file anyone can pass on. The same format and rules as the laptop program's
 * (tarjim/packs.py), so a file made on one opens on the other. The library that comes with
 * the app is assets/packs/<id>.json, built in the laptop repository (launcher/build_packs.py).
 * Bundled files carry names only: never lines from a game.
 */
class Packs(private val context: Context, private val store: Store)
{
	class PackError(message: String) : Exception(message)

	class Name(val en: String, val ar: String, val female: Boolean, val was: List<String>)

	class Pack(val id: String, val title: String, val info: JSONObject, val names: List<Name>,
		val fixes: List<Pair<String, String>>, val settings: JSONObject)

	/** Check a game file and return a clean copy. Throws [PackError] with a message for the user. */
	fun parse(obj: JSONObject?): Pack
	{
		if(obj == null || obj.optString("tarjim") != KIND) throw PackError("هذا مو ملف لعبة من ترجِم.")
		if(obj.optInt("version", 1) > VERSION) throw PackError("الملف من نسخة أحدث من ترجِم. حدّث التطبيق وجرّب مرة ثانية.")
		val title = text(obj.optString("title"), 60)
		val id = slug(obj.optString("id")).ifEmpty { slug(title) }
		if(id.isEmpty()) throw PackError("الملف ما فيه اسم لعبة واضح.")

		val names = ArrayList<Name>()
		val seen = HashSet<String>()
		val rows = obj.optJSONArray("names") ?: JSONArray()
		for(i in 0 until rows.length())
		{
			val row = rows.optJSONObject(i) ?: continue
			val en = text(row.optString("en"), NAME_LEN)
			val ar = text(row.optString("ar"), NAME_LEN)
			if(en.isEmpty() || ar.isEmpty() || !seen.add(en)) continue
			val wasRaw = row.optJSONArray("was") ?: JSONArray()
			val was = (0 until minOf(wasRaw.length(), 5)).map { text(wasRaw.optString(it), NAME_LEN) }.filter { it.isNotEmpty() && it != ar }
			names.add(Name(en, ar, row.optBoolean("f"), was))
			if(names.size >= MAX_NAMES) break
		}

		val fixes = ArrayList<Pair<String, String>>()
		seen.clear()
		val fixRows = obj.optJSONArray("fixes") ?: JSONArray()
		for(i in 0 until fixRows.length())
		{
			val row = fixRows.optJSONObject(i) ?: continue
			val en = text(row.optString("en"), LINE_LEN)
			val ar = text(row.optString("ar"), LINE_LEN)
			if(en.isEmpty() || ar.isEmpty() || !seen.add(en)) continue
			fixes.add(Pair(en, ar))
			if(fixes.size >= MAX_FIXES) break
		}

		val settings = JSONObject()
		val raw = obj.optJSONObject("settings") ?: JSONObject()
		if(raw.has("settle_ms")) settings.put("settle_ms", raw.optDouble("settle_ms", 60.0).coerceIn(0.0, 600.0))
		if(raw.has("read_scale")) settings.put("read_scale", raw.optDouble("read_scale", 1.0).coerceIn(0.5, 2.5))
		raw.optJSONObject("region")?.let { r ->
			val look = Look(JSONObject().put("region", r))  // the same limits as everywhere else
			settings.put("region", JSONObject().put("left", look.left.toDouble()).put("top", look.top.toDouble())
				.put("right", look.right.toDouble()).put("bottom", look.bottom.toDouble()))
		}

		val info = JSONObject()
		for((key, limit) in listOf("title_ar" to 60, "series" to 40, "note" to 300, "made" to 20, "year" to 8))
			if(obj.has(key)) text(obj.optString(key), limit).takeIf { it.isNotEmpty() }?.let { info.put(key, it) }
		if(obj.has("order")) info.put("order", obj.optInt("order", 10_000))
		return Pack(id, title.ifEmpty { id }, info, names, fixes, settings)
	}

	fun load(text: String): Pack = try { parse(JSONObject(text)) }
		catch(e: PackError) { throw e }
		catch(e: Exception) { throw PackError("الملف تالف أو مو ملف لعبة من ترجِم.") }

	// -- the library ---------------------------------------------------------------------

	fun library(): List<Pack> =
		(context.assets.list("packs") ?: arrayOf()).filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
			try { load(context.assets.open("packs/$file").use { it.readBytes().toString(Charsets.UTF_8) }) } catch(e: Exception) { null }
		}

	fun libraryPack(id: String): Pack = library().firstOrNull { it.id == id } ?: throw PackError("هذي اللعبة مو موجودة في المكتبة.")

	/** How many names in the file would change this profile. */
	private fun pending(pack: Pack, have: JSONObject?): Int
	{
		if(have == null) return pack.names.size
		return pack.names.count { !have.has(it.en) || have.optString(it.en) in it.was }
	}

	/** The library as the page shows it: the player's own games first, then the library's order. */
	fun libraryView(): JSONArray
	{
		val items = library().sortedWith(compareBy({ it.info.optInt("order", 10_000) }, { it.title })).map { pack ->
			val installed = store.exists(pack.id)
			val have = if(installed) store.load(pack.id).optJSONObject("glossary") ?: JSONObject() else null
			JSONObject().put("id", pack.id).put("title", pack.title).put("title_ar", pack.info.optString("title_ar"))
				.put("series", pack.info.optString("series")).put("year", pack.info.optString("year")).put("note", pack.info.optString("note"))
				.put("names", pack.names.size).put("women", pack.names.count { it.female })
				.put("installed", installed).put("new", pending(pack, have))
		}
		return JSONArray(items.sortedBy { !it.optBoolean("installed") })
	}

	// -- into and out of a profile ---------------------------------------------------------

	/**
	 * Add a game file to a profile (made if it does not exist). New names are added; a name the
	 * player already has keeps the player's spelling, unless that spelling is one an older version
	 * of the file used (`was`). The player's own line fixes are kept too.
	 */
	fun merge(pack: Pack, into: String?): JSONObject
	{
		val name = slug(into ?: "").ifEmpty { pack.id }
		val created = !store.exists(name)
		val prof = if(created) JSONObject().put("title", pack.title).put("glossary", JSONObject()) else store.load(name)
		if(created) for(key in listOf("settle_ms", "read_scale", "region")) if(pack.settings.has(key)) prof.put(key, pack.settings.get(key))
		val glossary = prof.optJSONObject("glossary") ?: JSONObject()
		val femaleRaw = prof.optJSONArray("female") ?: JSONArray()
		val female = LinkedHashSet((0 until femaleRaw.length()).map { femaleRaw.optString(it) })
		var added = 0
		var updated = 0
		var kept = 0
		for(n in pack.names)
		{
			if(!glossary.has(n.en)) { glossary.put(n.en, n.ar); added++ }
			else if(glossary.optString(n.en) != n.ar)
			{
				if(glossary.optString(n.en) !in n.was) { kept++; continue }
				glossary.put(n.en, n.ar)
				updated++
			}
			if(n.female) female.add(n.en)
		}
		prof.put("glossary", glossary).put("female", JSONArray(female.toList()))
		store.save(name, prof)
		val fixes = if(pack.fixes.isEmpty()) 0 else store.addFixes(name, pack.fixes)
		return JSONObject().put("name", name).put("title", prof.optString("title", name)).put("created", created)
			.put("added", added).put("updated", updated).put("kept", kept).put("fixes", fixes)
	}

	/** A profile as a game file: its names, the player's line fixes, and its reading area and timing. */
	fun export(name: String): JSONObject
	{
		val prof = store.load(name)
		val look = Look(prof)
		val names = JSONArray()
		for((en, ar) in look.glossary) names.put(JSONObject().put("en", en).put("ar", ar).also { if(en in look.female) it.put("f", true) })
		val fixes = JSONArray()
		for((en, ar) in store.fixesOf(name)) fixes.put(JSONObject().put("en", en).put("ar", ar))
		val settings = JSONObject().put("settle_ms", look.settleMs).put("read_scale", look.readScale.toDouble())
		if(prof.has("region")) settings.put("region", JSONObject().put("left", look.left.toDouble()).put("top", look.top.toDouble())
			.put("right", look.right.toDouble()).put("bottom", look.bottom.toDouble()))
		val title = prof.optString("title", name).ifEmpty { name }
		val id = if(name == "default") slug(title).ifEmpty { "game" } else name
		return JSONObject().put("tarjim", KIND).put("version", VERSION).put("id", id).put("title", title)
			.put("made", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
			.put("names", names).put("fixes", fixes).put("settings", settings)
	}

	companion object
	{
		const val KIND = "tarjim-game"
		const val VERSION = 1
		private const val MAX_NAMES = 3000
		private const val MAX_FIXES = 20000
		private const val NAME_LEN = 60
		private const val LINE_LEN = 400

		/** The same profile names as the laptop program: small English letters, digits and dashes. */
		fun slug(text: String): String
		{
			val s = text.lowercase(Locale.US).map { if(it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
				.split('-').filter { it.isNotEmpty() }.joinToString("-").take(30)
			return if(Regex("[a-z0-9][a-z0-9_-]{0,40}").matches(s)) s else ""
		}

		private fun text(value: String, limit: Int) = value.replace(Regex("\\s+"), " ").trim().take(limit)

		/** A file name that works on Windows and Android: the game's title, then the program's name. */
		fun fileName(pack: JSONObject): String
		{
			val base = pack.optString("title").replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]+"), " ").trim(' ', '.').take(50)
				.ifEmpty { pack.optString("id", "game") }
			return "$base - tarjim.json"
		}
	}
}
