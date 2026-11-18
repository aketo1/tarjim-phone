package com.metallic.chiaki.tarjim

/**
 * Game words that are not names.
 *
 * A game says "the fence" for a dealer in stolen goods (not «السياج»), "case the bank" for
 * scouting it, "lose the law" for escaping the police, "hogtie", "parley", "sworn brother",
 * "Kiryu-chan"... The translator knows plain English, so these are put into plain English
 * before it reads the line, and the few Arabic words it still gets wrong are put right after.
 *
 * Every game gets GENERAL; a series (Red Dead, GTA, The Last of Us, Yakuza / Like a Dragon)
 * gets its own words as well. The same rules as the laptop program's laptop/tarjim/terms.py:
 * keep the two alike.
 */
object Terms
{
	private val SERIES_IDS = mapOf(
		"rdr" to setOf("rdr1", "rdr2"),
		"gta" to setOf("gta-sa", "gta4", "gta5", "gta6"),
		"tlou" to setOf("tlou1", "tlou2"),
		"yakuza" to setOf("yakuza0", "yakuza-kiwami", "yakuza-kiwami2", "yakuza3", "yakuza4", "yakuza5", "yakuza6",
			"lad7", "lad8", "lad-gaiden", "lad-pirate")
	)
	private val TITLE_HINTS = listOf("red dead" to "rdr", "grand theft auto" to "gta", "gta" to "gta",
		"last of us" to "tlou", "yakuza" to "yakuza", "like a dragon" to "yakuza")

	/** The series of a game profile ("" for none): by the library's id, else by its title. */
	fun seriesOf(profile: String, title: String = ""): String
	{
		for((series, ids) in SERIES_IDS) if(profile in ids) return series
		val text = "$title $profile".lowercase().replace('-', ' ').replace('_', ' ')
		return TITLE_HINTS.firstOrNull { it.first in text }?.second ?: ""
	}

	private fun rx(pattern: String, ignoreCase: Boolean = true) =
		if(ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)

	private const val THINGS = "(?:bank|place|house|store|shop|joint|train|town|camp|ranch|mansion|manor|station|saloon|farm|plantation)"
	private const val CHASERS = "(?:the law|the cops|the police|the posse|the lawmen|the bounty hunters|the pinkertons|the feds)"
	private const val SENT_START = """(?:^|(?<=[.!?] )|(?<=[.!?]" ))"""
	private const val CASE_VERB = """(?:^|(?<=\bto )|(?<=\bwill )|(?<='ll )|(?<=\blet's )|(?<=\bgo )|(?<=\band )|(?<=\bshould )|(?<=\bmust )|(?<=\bcan )|(?<=\bcould )|(?<=\bgonna )|(?<=\bwe )|(?<=\byou )|(?<=\bI ))"""
	private const val AUX = "(?:is|are|'s|was|were|will|'ll|has|have|had|might|may|could|would|can|won't|ain't|be|been)"
	private const val LAW_HUNTS = "(?:after|coming|here|onto|on to|on our|on your|on his|on their|on my|close|everywhere|outside|" +
		"waiting|chasing|looking for|searching|hunting|find|finds|found|catch|catches|caught|get|gets|got|" +
		"show up|shows up|showed up|turn up|turns up|turned up|arrive|arrives|arrived|all over)"

	/** Every game: western and crime-game words, and game words the translator reads as other words. */
	private val GENERAL: List<Pair<Regex, String>> = listOf(
		rx("""\bhog-?ties? (him|her|them|it|me|us)\b""") to "tie $1 up",
		rx("""\bhog-?ties? (the|that|this|a|an) (\w+)\b""") to "tie up $1 $2",
		rx("""\bhog-?tied\b""") to "tied up",
		rx("""\bhog-?tying\b""") to "tying up",
		rx("""\bhog-?ties?\b""") to "tie up",
		rx("""\blasso(?:es)? (him|her|them|it)\b""") to "catch $1 with your rope",
		rx("""\blasso(?:es)? (the|that|this|a|an) (\w+)\b""") to "catch $1 $2 with your rope",
		rx("""\blassoed\b""") to "caught with a rope",
		rx("""\b(your|my|a|the|his|her) lasso\b""") to "$1 rope",
		rx("""\bparleyed\b""") to "negotiated",
		rx("""\bparleying\b""") to "negotiating",
		rx("""\b(a|the) parley\b""") to "$1 negotiation",
		rx("""\bparleys?\b""") to "negotiate",
		rx("""${CASE_VERB}case (the|this|that|a) ($THINGS)\b""") to "scout $1 $2",
		rx("""\bcased (the|this|that|a) ($THINGS)\b""") to "scouted $1 $2",
		rx("""\bcasing (the|this|that|a) ($THINGS)\b""") to "scouting $1 $2",
		rx("""\b(?:lose|shake off|shake) ($CHASERS)""") to "escape from $1",
		rx("""\b(?:lost|shook off|shook) ($CHASERS)""") to "escaped from $1",
		rx("""\b(?:losing|shaking off|shaking) ($CHASERS)""") to "escaping from $1",
		rx("""\b(?:shake|shook) (them|him|her|'em) off\b""") to "escape from $1",
		rx("""(?:^|(?<=[.!?,;] )|(?<=[.!?]" ))(?:lose|shake) (them|him|her|'em)(?![\w'])""") to "escape from $1",
		rx("""\b(from|run from|running from|hide from|hiding from|on the run from|wanted by) the law\b""") to "$1 the police",
		rx("""\bthe law\b(?=\s+(?:$AUX\s+)?(?:gonna\s+|going to\s+|be\s+)?$LAW_HUNTS\b)""") to "the police",
		rx("""\b(we|you|I)(?:'ve| have)? got (?:some )?company\b""") to "$1 have visitors",
		rx("""\b(we|you|I) have (?:some )?company\b""") to "$1 have visitors",
		rx("""\b(?:lay|lie) low\b""") to "stay out of sight",
		rx("""\b(?:laying|lying) low\b""") to "staying out of sight",
		rx("""\bsaddle up\b""") to "get on your horse",
		rx("""\bgaining on (us|you|me|him|her|them)\b""") to "getting closer to $1",
		rx("""(?:(?<=\bwe )|(?<=\blet's )|(?<='ll )|(?<=\bto )|(?<=\bgonna ))hit (the|a|that) (train|bank|stagecoach|coach|store|shop|jewelry store|armored car|convoy)\b""") to "rob $1 $2",
		rx("""\bsafe ?houses\b""") to "hideouts",
		rx("""\bsafe ?house\b""") to "hideout",
		rx("""\b(?:you've got|you have|you got|you're at|you are at) an? (\w+)[- ]star wanted level\b""") to "the police are chasing you with $1 stars",
		rx("""\bwanted level\b""") to "pursuit level",
		rx("""\bchop shop\b""") to "stolen car workshop",
		rx("""\bloot (the|those|these|his|her|their|all the|every) (bodies|body|corpses|corpse|dead|house|wagon|camp|place|safe|chest|wreck)\b""") to "search $1 $2 for valuables",
		rx("""\bfast[- ]travel(?:led|ed)? to\b""") to "travel quickly to",
		rx("""\b(?:your|the) horse's bonding(?: level)? (?:went up|has gone up|increased|has increased|rose|has risen|improved|has improved)\b""") to "your bond with your horse grew stronger",
		rx("""\b(?:your|the) horse's bonding(?: level)?\b""") to "your bond with your horse",
		rx("""\bbonding level\b""") to "bond",
		rx("""${SENT_START}(?:go )?fish(?:ing)? (in|at|near|from|off) (?!.*\b(?:are|is|were|was|swim|swims|live|lives)\b)""") to "catch fish $1 ",
		rx("""\bchores\b""") to "tasks",
		rx("""\bheld up (the|a|that) (stagecoach|coach|train|bank|store|shop|wagon|stage)\b""") to "robbed $1 $2",
		rx("""\bhold(?:ing)? up (the|a|that) (stagecoach|coach|train|bank|store|shop|wagon|stage)\b""") to "rob $1 $2",
		rx("""\b(run|runs|ran|running) off\b(?! (?:the|a) (?:road|bridge|cliff|edge|track))""") to "$1 away",
		rx("""\b(the|a|that|your) gunsmith\b""") to "$1 gun shop"
	)
	private val ANY = Regex(GENERAL.joinToString("|") { "(?:${it.first.pattern})" }, RegexOption.IGNORE_CASE)

	private class SeriesRule(val rx: Regex, val repl: String, val condition: Regex? = null)

	private val SELLING = rx("""\b(?:sell|sold|selling|stolen|loot|valuables|trinkets|jewel|jewelry|take it|price|pay|buys|fenced|goods)\b""")
	private val LAW_KEEP = listOf("against", "above", "break", "breaking", "broke", "broken", "obey", "obeying", "within", "by",
		"under", "follow", "following", "respect", "it's", "is", "that's", "was", "uphold", "upholding",
		"enforce", "I'm", "am", "letter of", "according to", "eyes of").joinToString("") { "(?<!\\b$it )" }

	private val SERIES: Map<String, List<SeriesRule>> = mapOf(
		"rdr" to listOf(
			// in a western "the law" is the lawmen, except "against the law", "it's the law", ...
			SeriesRule(rx("""$LAW_KEEP\bthe law\b(?!\s+(?:of|says|said|is clear|and order|is the law))"""), "the police"),
			SeriesRule(rx("""\b(the|a|that|this|another|their|his|her|your) posse\b"""), "$1 pursuers"),
			SeriesRule(rx("""\b(your|my|his|her|the|a|that) repeater\b"""), "$1 rifle"),
			SeriesRule(rx("""\b(the|a|that|your) trapper\b"""), "$1 fur trader"),
			SeriesRule(rx("""\b(a|the|any|your|that) fence\b"""), "$1 stolen goods dealer", SELLING),
			SeriesRule(rx("""\bfence (it|them|this|that|these|those)\b(?! off| in)"""), "sell $1 as stolen goods")
		),
		"yakuza" to listOf(
			// "-san" stays: without it the translator takes a name like "Majima" for a woman's
			SeriesRule(rx("""\b([A-Z][a-z]+)-(?:chan|kun|sama|senpai|sensei|dono|han|bo)\b""", ignoreCase = false), "$1"),
			SeriesRule(rx("""\b(?:nii-?san|nii-?chan|onii-?san|aniki)\b"""), "big brother"),
			SeriesRule(rx("""\b(?:nee-?san|onee-?san|ane-?san)\b"""), "big sister"),
			SeriesRule(rx("""\b(?:oyaji|oyassan)\b"""), "old man"),
			SeriesRule(rx("""\boyabun\b"""), "boss"),
			SeriesRule(rx("""\bkyodai\b"""), "brother"),
			SeriesRule(rx("""\bpatriarch of\b"""), "head of"),
			SeriesRule(rx("""\bpatriarch\b"""), "family head"),
			SeriesRule(rx("""\bsworn brothers\b"""), "brothers by oath"),
			SeriesRule(rx("""\bsworn brother\b"""), "brother by oath")
		)
	)

	/** Arabic the translator still gets wrong: (English that must be in the line or null, wrong, right). */
	private val AR_FIXES: Map<String, List<Triple<Regex?, String, String>>> = mapOf(
		"" to listOf(Triple(null, "بطاقات التموينية", "بطاقات التموين")),
		"tlou" to listOf(
			Triple(rx("""\bspores?\b"""), "الجراثيم", "الأبواغ"),
			Triple(rx("""\bspores?\b"""), "جراثيم", "أبواغ")
		)
	)

	/** The line in plain English for the translator: game words, then the series' words. */
	fun rewrite(english: String, series: String = ""): String
	{
		var s = english
		if(ANY.containsMatchIn(s)) for((r, repl) in GENERAL) s = r.replace(s, repl)
		for(rule in SERIES[series].orEmpty())
			if(rule.condition == null || rule.condition.containsMatchIn(s)) s = rule.rx.replace(s, rule.repl)
		return s
	}

	fun fixArabic(arabic: String, english: String, series: String = ""): String
	{
		var s = arabic
		for(group in if(series.isEmpty()) listOf("") else listOf("", series))
			for((cond, wrong, right) in AR_FIXES[group].orEmpty())
				if(wrong in s && (cond == null || cond.containsMatchIn(english))) s = s.replace(wrong, right)
		return s
	}

	// -- commands written as verbal nouns -----------------------------------------------
	//
	// The translator often writes a command as a verbal noun: "Buy some tonic" → «شراء بعض
	// منشط». When the English line (or the part after "Name,") opens with one of these verbs
	// and the Arabic with its verbal noun, the command is written instead; its gender is put
	// right afterwards with the rest of the line (Gender).

	private val VERB_NOUNS: Map<String, Map<String, String>> = mapOf(
		"buy" to mapOf("شراء" to "اشتر"),
		"sell" to mapOf("بيع" to "بع"),
		"pay" to mapOf("دفع" to "ادفع"),
		"hunt" to mapOf("صيد" to "اصطد"),
		"catch" to mapOf("صيد" to "اصطد", "القبض" to "اقبض", "الإمساك" to "أمسك", "إمساك" to "أمسك"),
		"remove" to mapOf("إزالة" to "أزل"),
		"negotiate" to mapOf("التفاوض" to "تفاوض"),
		"escape" to mapOf("الهروب" to "اهرب", "الهرب" to "اهرب", "هروب" to "اهرب", "هرب" to "اهرب"),
		"run" to mapOf("الركض" to "اركض", "الهروب" to "اهرب"),
		"cook" to mapOf("طبخ" to "اطبخ", "طهي" to "اطبخ"),
		"craft" to mapOf("صنع" to "اصنع", "صناعة" to "اصنع"),
		"make" to mapOf("صنع" to "اصنع"),
		"rob" to mapOf("سرقة" to "اسرق"),
		"steal" to mapOf("سرقة" to "اسرق"),
		"search" to mapOf("البحث" to "ابحث", "بحث" to "ابحث", "تفتيش" to "فتّش"),
		"look" to mapOf("البحث" to "ابحث", "النظر" to "انظر"),
		"ride" to mapOf("ركوب" to "اركب"),
		"feed" to mapOf("إطعام" to "أطعم"),
		"clean" to mapOf("تنظيف" to "نظّف"),
		"brush" to mapOf("تنظيف" to "نظّف"),
		"save" to mapOf("حفظ" to "احفظ", "إنقاذ" to "أنقذ"),
		"rescue" to mapOf("إنقاذ" to "أنقذ"),
		"collect" to mapOf("جمع" to "اجمع"),
		"gather" to mapOf("جمع" to "اجمع"),
		"carry" to mapOf("حمل" to "احمل"),
		"kill" to mapOf("قتل" to "اقتل"),
		"drink" to mapOf("شرب" to "اشرب"),
		"follow" to mapOf("اتباع" to "اتبع"),
		"stay" to mapOf("البقاء" to "ابق", "الابتعاد" to "ابتعد"),
		"keep" to mapOf("الابتعاد" to "ابتعد", "الحفاظ" to "حافظ"),
		"go" to mapOf("الذهاب" to "اذهب"),
		"get" to mapOf("الحصول" to "احصل"),
		"watch" to mapOf("مشاهدة" to "شاهد", "مراقبة" to "راقب"),
		"bring" to mapOf("إحضار" to "أحضر", "جلب" to "اجلب"),
		"fetch" to mapOf("إحضار" to "أحضر", "جلب" to "اجلب"),
		"use" to mapOf("استخدام" to "استخدم"),
		"return" to mapOf("إعادة" to "أعد", "العودة" to "عد"),
		"deliver" to mapOf("تسليم" to "سلّم"),
		"hand" to mapOf("تسليم" to "سلّم"),
		"drive" to mapOf("قيادة" to "قد"),
		"draw" to mapOf("سحب" to "اسحب"),
		"pull" to mapOf("سحب" to "اسحب"),
		"take" to mapOf("أخذ" to "خذ", "اتخاذ" to "خذ"),
		"grab" to mapOf("أخذ" to "خذ", "الإمساك" to "أمسك"),
		"put" to mapOf("وضع" to "ضع"),
		"place" to mapOf("وضع" to "ضع"),
		"leave" to mapOf("ترك" to "اترك", "مغادرة" to "غادر"),
		"shoot" to mapOf("إطلاق" to "أطلق"),
		"fire" to mapOf("إطلاق" to "أطلق"),
		"find" to mapOf("العثور" to "اعثر", "إيجاد" to "جد"),
		"locate" to mapOf("تحديد" to "حدد"),
		"wait" to mapOf("الانتظار" to "انتظر", "انتظار" to "انتظر"),
		"check" to mapOf("التحقق" to "تحقق", "فحص" to "افحص"),
		"hide" to mapOf("الاختباء" to "اختبئ", "إخفاء" to "أخف"),
		"approach" to mapOf("الاقتراب" to "اقترب"),
		"listen" to mapOf("الاستماع" to "استمع"),
		"stop" to mapOf("التوقف" to "توقف", "إيقاف" to "أوقف"),
		"tie" to mapOf("ربط" to "اربط"),
		"travel" to mapOf("السفر" to "سافر"),
		"open" to mapOf("فتح" to "افتح"),
		"close" to mapOf("إغلاق" to "أغلق"),
		"help" to mapOf("مساعدة" to "ساعد", "المساعدة" to "ساعد"),
		"skin" to mapOf("سلخ" to "اسلخ"),
		"donate" to mapOf("التبرع" to "تبرع"),
		"head" to mapOf("التوجه" to "توجه"),
		"meet" to mapOf("مقابلة" to "قابل", "لقاء" to "قابل"),
		"talk" to mapOf("التحدث" to "تحدث"),
		"speak" to mapOf("التحدث" to "تحدث"),
		"defend" to mapOf("الدفاع" to "دافع"),
		"protect" to mapOf("حماية" to "احم"),
		"attack" to mapOf("مهاجمة" to "هاجم", "الهجوم" to "هاجم"),
		"destroy" to mapOf("تدمير" to "دمّر"),
		"eat" to mapOf("تناول" to "تناول"),
		"give" to mapOf("إعطاء" to "أعط"),
		"tell" to mapOf("إخبار" to "أخبر"),
		"send" to mapOf("إرسال" to "أرسل"),
		"call" to mapOf("الاتصال" to "اتصل"),
		"throw" to mapOf("رمي" to "ارم"),
		"jump" to mapOf("القفز" to "اقفز"),
		"swim" to mapOf("السباحة" to "اسبح"),
		"track" to mapOf("تعقب" to "تعقب", "تتبع" to "تتبع"),
		"sneak" to mapOf("التسلل" to "تسلل"),
		"climb" to mapOf("تسلق" to "تسلق")
	)
	/** The word after the first one shows it is a noun, not a command: "Escape is impossible". */
	private val NOT_A_COMMAND_NEXT = setOf("is", "was", "are", "were", "will", "would", "can", "could", "should", "has", "have",
		"had", "isn't", "wasn't", "won't", "might", "must", "may", "did", "does", "do", "makes", "made", "seems", "looks",
		"needs", "takes", "sounds", "feels", "costs", "of", "'s", "and", "or")
	private val EN_VOCATIVE = Regex("""^(?:[A-Z][\w'.\-]*\s+){0,2}[A-Z][\w'.\-]*,\s+""")
	private val AR_WORD = Regex("[ء-يّ]+")
	private val AND_VERB = Regex("""\band (?:then )?([a-z]+)\b""")
	private val SUFFIXES = listOf("", "ه", "ها", "هم", "هما", "ني", "نا")

	private fun command(token: String, nouns: Map<String, String>): String?
	{
		for((noun, cmd) in nouns)
			if(token.startsWith(noun) && token.substring(noun.length) in SUFFIXES) return cmd + token.substring(noun.length)
		return null
	}

	/** The first command of the line, and those joined to it by «و», as commands. */
	fun commandsNotNouns(arabic: String, english: String): String
	{
		var en = english.trimStart(' ', '"', '\'', '-', '–', '—')
		var start = 0
		EN_VOCATIVE.find(en)?.let { vm ->
			en = en.substring(vm.range.last + 1)
			var comma = arabic.indexOf('،')
			if(comma < 0) comma = arabic.indexOf(',')
			if(comma < 0 || comma > 40) return arabic
			start = comma + 1
		}
		val words = en.split(Regex("\\s+")).filter { it.isNotEmpty() }
		if(words.size < 2) return arabic
		val verb = words[0].lowercase().trim(',', '.', '!', '?')
		val next = words[1].lowercase().trim(',', '.', '!', '?')
		val nouns = VERB_NOUNS[verb] ?: return arabic
		if(next in NOT_A_COMMAND_NEXT) return arabic
		val m = AR_WORD.find(arabic, start) ?: return arabic
		if(arabic.substring(start, m.range.first).trim(' ', '«', '"', '\'').isNotEmpty()) return arabic
		val cmd = command(m.value, nouns) ?: return arabic
		var out = arabic.substring(0, m.range.first) + cmd + arabic.substring(m.range.last + 1)
		// «... واتخاذ اللحوم» for "... and take the meat": the next commands of the sentence
		var end = out.length
		for(stop in ".!؟?")
		{
			val k = out.indexOf(stop, m.range.first)
			if(k in 0 until end) end = k
		}
		var pos = m.range.first + cmd.length
		for(v in AND_VERB.findAll(en.lowercase()).map { it.groupValues[1] })
		{
			val later = VERB_NOUNS[v] ?: continue
			if(pos >= end) break
			for(w in AR_WORD.findAll(out.substring(0, end), pos))
			{
				val tok = w.value
				val c = if(tok.startsWith("و")) command(tok.substring(1), later) else null
				if(c != null)
				{
					out = out.substring(0, w.range.first) + "و" + c + out.substring(w.range.last + 1)
					pos = w.range.first + 1 + c.length
					end += c.length + 1 - tok.length
					break
				}
			}
		}
		return out
	}
}
