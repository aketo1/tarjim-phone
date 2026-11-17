package com.metallic.chiaki.tarjim

/**
 * Masculine and feminine "you" in commands, for whole families of verbs at once: the
 * translator guesses the gender of the one spoken to («غطّي» said to Javier), the program
 * knows it (a man's name, a woman's name, or nobody named: masculine).
 *
 * The same rules as the laptop program's laptop/tarjim/gender.py: keep the two lists alike.
 * One list of masculine commands; the feminine is the masculine plus «ي» except for the
 * hollow and hamza verbs listed as pairs; object endings are added to both. Only these
 * words change, and only where a command stands: at the start of a sentence or clause,
 * joined to «و/ف», after a word like «ثم», or after «لا» for "don't".
 */
object Gender
{
	private val MASC = listOf(
		"اذهب", "ارجع", "قف", "اجلس", "انهض", "تعال", "اركض", "اهرب", "اختبئ", "انبطح", "اخفض", "ارفع",
		"انتظر", "اسمع", "استمع", "انظر", "تفقد", "ابحث", "جد", "اعثر", "خذ", "أعط", "هات", "ضع", "ألق",
		"ارم", "اترك", "دع", "ساعد", "أنقذ", "احم", "غطّ", "راقب", "احرس", "احذر", "انتبه", "تمهل", "تحرك",
		"توقف", "أسرع", "امش", "اركب", "انزل", "اصعد", "ادخل", "اخرج", "افتح", "أغلق", "اقفل", "أطفئ",
		"أشعل", "احمل", "اسحب", "ادفع", "أمسك", "تمسك", "اقبض", "اربط", "فكّ", "اقتل", "أطلق", "صوّب",
		"اضرب", "قاتل", "ادفن", "احفر", "اشرب", "استرح", "اسكت", "اصمت", "اخرس", "تكلم", "أخبر", "اشرح",
		"اسأل", "أجب", "اكتب", "تذكر", "انس", "صدّق", "سامح", "ثق", "جرّب", "حاول", "فكّر", "اختر", "اتبع",
		"اتصل", "ابق", "ابتعد", "اقترب", "تراجع", "ارحل", "اصبر", "استعد", "تجهز", "أحضر", "اجلب", "أرسل",
		"سلّم", "نظّف", "اغسل", "البس", "اخلع", "اشتر", "احتم", "أكمل", "واصل", "استمر", "انطلق", "اهجم",
		"انسحب", "اعبر", "استدر", "التفت", "ابتسم", "اضحك", "ابك", "اصرخ", "اهمس", "اقرأ", "ابصق", "اهدأ",
		"تماسك", "تمالك", "ارتح", "أبق", "ارقص", "عانق", "قبّل", "اغفر", "اهتم", "اعتن", "انصت", "تأكد",
		"تصرّف", "تعلّم", "علّم", "خبّئ", "اسرق", "اقطع", "اصنع", "أصلح", "املأ", "أفرغ", "اصمد", "انج",
		"حافظ", "ناول", "أبعد", "تعامل", "اذكر", "تخيّل", "انقل", "أوقف", "تجاهل", "لاحق", "طارد", "اتجه",
		"استخدم", "أظهر", "اعرض", "اعتمد", "جهّز", "اجمع", "فتّش", "أطعم", "ناد", "امض", "تحقق", "أخرج",
		"أدخل", "اسمح", "اركع", "انحن", "اقفز", "تقدّم", "تأخّر", "ادع", "اعترف", "اعتذر", "استسلم",
		"توقّع", "اختف", "أمّن", "حرّك", "ركّز", "اهدئ", "ارتد", "خلّص", "اعطف", "اصفح", "احتفظ"
	)
	private val IRREGULAR = mapOf(
		"قومي" to "قم", "عودي" to "عد", "نامي" to "نم", "قولي" to "قل", "كوني" to "كن", "خافي" to "خف",
		"سيري" to "سر", "بيعي" to "بع", "عيشي" to "عش", "دوري" to "در", "زوري" to "زر", "ذوقي" to "ذق",
		"صومي" to "صم", "طيري" to "طر", "ميلي" to "مل", "موتي" to "مت", "ارتاحي" to "ارتح", "اهدئي" to "اهدأ",
		"اقرئي" to "اقرأ", "ابدئي" to "ابدأ", "املئي" to "املأ", "نالي" to "نل", "أعيدي" to "أعد",
		"أقيمي" to "أقم", "أريحي" to "أرح", "أجيبي" to "أجب", "اختاري" to "اختر", "استريحي" to "استرح",
		"أطيعي" to "أطع", "أضيفي" to "أضف"
	)
	private val SPELLING = mapOf("اقرأي" to "اقرئي", "اهدأي" to "اهدئي", "ابدأي" to "ابدئي", "املأي" to "املئي", "اهدأى" to "اهدئي")
	private val SUFFIXES = listOf("", "ني", "ه", "ها", "هم", "هن", "نا")
	private val AMBIGUOUS = setOf("عد", "قل", "جد", "مت", "در", "مل", "طر", "كن", "دع", "ثق", "خف", "سر", "بع", "عش", "صم", "زر", "ذق")
	private val PRESENT_FEM = mapOf(
		"تفعلين" to "تفعل", "تذهبين" to "تذهب", "تريدين" to "تريد", "تعرفين" to "تعرف", "تقولين" to "تقول",
		"تحتاجين" to "تحتاج", "تستطيعين" to "تستطيع", "تنتظرين" to "تنتظر", "تفكرين" to "تفكر", "تمزحين" to "تمزح",
		"تكذبين" to "تكذب", "تحاولين" to "تحاول", "تبحثين" to "تبحث", "تتحدثين" to "تتحدث", "تتكلمين" to "تتكلم",
		"تبقين" to "تبقى", "تنسين" to "تنسى", "ترين" to "ترى", "تأتين" to "تأتي", "تبدين" to "تبدو", "تظنين" to "تظن",
		"تحبين" to "تحب", "تشعرين" to "تشعر", "تفهمين" to "تفهم", "تسمعين" to "تسمع", "تعملين" to "تعمل",
		"تندمين" to "تندم", "تخافين" to "تخاف", "تثقين" to "تثق", "تنامين" to "تنام", "تعودين" to "تعود"
	)
	private val PROHIBIT = listOf(
		"تقلق", "تتحرك", "تذهب", "تفعل", "تصدر", "تكذب", "تلمس", "تطلق", "تقتل", "تنظر", "تلتفت", "تتكلم",
		"تتحدث", "تصرخ", "تهرب", "تترك", "تفكر", "تستسلم", "تيأس", "تغضب", "تخبر", "تخرج", "تدخل", "تقترب",
		"تبتعد", "تتأخر", "تتدخل", "تتصرف", "تفتح", "تغلق", "تلعب", "تضحك", "تحزن", "تتوقف", "تجرؤ", "تنزل",
		"تصعد", "تركض", "تعبث", "تثق", "تسمح", "تحكم", "تنتظر", "تقاطع", "تحرّك", "تسرق", "تشرب"
	)
	private val PROHIBIT_IRREGULAR = mapOf("تخافي" to "تخف", "تقولي" to "تقل", "تكوني" to "تكن", "تلومي" to "تلم",
		"تموتي" to "تمت", "تنامي" to "تنم", "تعودي" to "تعد", "تقومي" to "تقم", "تبيعي" to "تبع")
	private val LEADS = setOf("ثم", "الآن", "فقط", "هيا", "رجاء", "رجاءً", "أرجوك", "حسناً", "حسنا", "إذن", "بسرعة",
		"أولاً", "أولا", "والآن", "فوراً", "فورا")
	private val NOT = setOf("لا", "ولا", "فلا")

	private class Ctx(val other: String, val after: Regex)
	private val ctxPairs = listOf(
		Triple("قل", "قولي", Regex("^\\s*(?:لي|له|لها|لهم|لنا|الحقيقة|شيئاً|شيئا|ذلك|هذا|ما)(?![\\u0621-\\u064a])")),
		Triple("عد", "عودي", Regex("^\\s*(?:إلى|الى|هنا|للمخيم|للبيت|للمنزل|أدراجك|حالاً|حالا|فوراً|فورا)(?![\\u0621-\\u064a])")),
		Triple("كن", "كوني", Regex("^\\s*(?:حذر|حذرة|حذراً|حذرا|هادئ|هادئة|هادئاً|هادئا|مستعد|مستعدة|مستعداً|مستعدا)(?![\\u0621-\\u064a])"))
	)
	private val ctxFem = ctxPairs.associate { it.first to Ctx(it.second, it.third) }
	private val ctxMasc = ctxPairs.associate { it.second to Ctx(it.first, it.third) }

	private fun loose(s: String) = s.replace('أ', 'ا').replace('إ', 'ا').replace("ّ", "")

	private val toMasc = HashMap<String, String>()
	private val toFem = HashMap<String, String>()
	private val prohibitMasc = HashMap<String, String>()
	private val prohibitFem = HashMap<String, String>()

	init
	{
		val bases = LinkedHashMap<String, String>()
		for(m in MASC) bases[m] = IRREGULAR.entries.firstOrNull { it.value == m }?.key ?: (m + "ي")
		for((f, m) in IRREGULAR) bases.putIfAbsent(m, f)
		for((m, f) in bases) for(suf in SUFFIXES)
		{
			if(suf.isEmpty() && m in AMBIGUOUS) continue
			for(key in listOf(f + suf, loose(f + suf))) toMasc.putIfAbsent(key, m + suf)
			for(key in listOf(m + suf, loose(m + suf))) toFem.putIfAbsent(key, f + suf)
		}
		val pBases = LinkedHashMap<String, String>()
		for(m in PROHIBIT) if(!m.endsWith("ي")) pBases[m] = m + "ي"
		for((f, m) in PROHIBIT_IRREGULAR) pBases[m] = f
		for((m, f) in pBases) for(suf in SUFFIXES) prohibitMasc[f + suf] = m + suf
		for((f, m) in prohibitMasc) prohibitFem[m] = f
	}

	private val TOKEN = Regex("[\\u0621-\\u064d\\u0651]+|[^\\u0621-\\u064d\\u0651]+")
	private val STOP = Regex("[.!؟?،,:؛«\"]\\s*$")
	private val HARAKAT = Regex("[\\u064e\\u064f\\u0650\\u0652\\u0670]")
	private val PRESENT = Regex("(?<![\\u0621-\\u064d\\u0651])(" + PRESENT_FEM.keys.sortedByDescending { it.length }.joinToString("|") + ")(?![\\u0621-\\u064d\\u0651])")

	private fun swap(text: String, table: Map<String, String>, ctx: Map<String, Ctx>, prohibit: Map<String, String>): String
	{
		val tokens = TOKEN.findAll(text).map { it.value }.toList()
		val out = StringBuilder()
		var atCmd = true
		var afterNot = false
		for((i, tok) in tokens.withIndex())
		{
			if(tok[0] !in 'ء'..'ي')
			{
				out.append(tok)
				if(STOP.containsMatchIn(tok)) atCmd = true else if(tok.isNotBlank()) atCmd = false
				continue
			}
			if(afterNot)
			{
				val p = prohibit[tok]
				if(p != null) { out.append(p); atCmd = false; afterNot = false; continue }
			}
			val joined = if(tok[0] in "وف" && tok.length > 3) table[tok.substring(1)] else null
			var new: String? = null
			if(atCmd)
			{
				new = table[tok] ?: joined?.let { tok[0] + it }
				if(new == null)
				{
					val c = ctx[tok]
					if(c != null && c.after.containsMatchIn(tokens.drop(i + 1).take(2).joinToString(""))) new = c.other
				}
			}
			else if(joined != null) new = tok[0] + joined  // «ابقي هنا وراقبي»: a list of commands
			out.append(new ?: tok)
			atCmd = tok in LEADS
			afterNot = tok in NOT
		}
		return out.toString()
	}

	private fun spelling(text: String): String
	{
		var s = text
		for((wrong, right) in SPELLING) if(wrong in s) s = s.replace(wrong, right)
		return s
	}

	/** Commands and "you" verbs said to a woman, turned to a man (or to nobody in particular). */
	fun masculinize(arabic: String): String
	{
		if('ي' !in arabic) return arabic
		val s = swap(HARAKAT.replace(spelling(arabic), ""), toMasc, ctxMasc, prohibitMasc)
		return if("ين" in s) PRESENT.replace(s) { PRESENT_FEM[it.value] ?: it.value } else s
	}

	/** Commands said to a man, turned to a woman (when a woman is the one spoken to). */
	fun feminize(arabic: String): String = spelling(swap(HARAKAT.replace(arabic, ""), toFem, ctxFem, prohibitFem))
}
