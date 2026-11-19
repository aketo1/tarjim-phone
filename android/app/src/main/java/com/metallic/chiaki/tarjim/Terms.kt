package com.metallic.chiaki.tarjim

/**
 * Game words that are not names.
 *
 * A game says "the fence" for a dealer in stolen goods (not «السياج»), "case the bank" for
 * scouting it, "lose the law" for escaping the police, "hogtie", "parley", "sworn brother",
 * "Kiryu-chan"... The translator knows plain English, so these are put into plain English
 * before it reads the line, and the few Arabic words it still gets wrong are put right after.
 *
 * Every game gets GENERAL (western, gangster, street and old English, soldiers' calls,
 * idioms); every game of the library belongs to a series that gets its own words as well.
 * The same rules as the laptop program's laptop/tarjim/terms.py: keep the two alike.
 */
object Terms
{
	private val SERIES_IDS = mapOf(
		"rdr" to setOf("rdr1", "rdr2"),
		"gta" to setOf("gta-sa", "gta4", "gta5", "gta6"),
		"tlou" to setOf("tlou1", "tlou2"),
		"yakuza" to setOf("yakuza0", "yakuza-kiwami", "yakuza-kiwami2", "yakuza3", "yakuza4", "yakuza5", "yakuza6",
			"lad7", "lad8", "lad-gaiden", "lad-pirate"),
		"sh" to setOf("sh2r"),
		"maxpayne" to setOf("max-payne-3"),
		"lanoire" to setOf("la-noire"),
		"bully" to setOf("bully"),
		"bg3" to setOf("bg3"),
		"ff7" to setOf("ff7r"),
		"kcd" to setOf("kcd2"),
		"mafia" to setOf("mafia-toc"),
		"persona" to setOf("p5r")
	)
	private val TITLE_HINTS = listOf("red dead" to "rdr", "grand theft auto" to "gta", "gta" to "gta",
		"last of us" to "tlou", "yakuza" to "yakuza", "like a dragon" to "yakuza", "silent hill" to "sh",
		"max payne" to "maxpayne", "l.a. noire" to "lanoire", "la noire" to "lanoire", "bully" to "bully",
		"canis canem" to "bully", "baldur" to "bg3", "final fantasy" to "ff7", "kingdom come" to "kcd",
		"mafia" to "mafia", "persona" to "persona")

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
	private const val OBJ = "(us|him|her|them|me|you)"

	/** Every game: western and crime-game words, slang, old English, soldiers' calls, idioms. */
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
		rx("""\b(the|a|that|your) gunsmith\b""") to "$1 gun shop",
		// western speech
		rx("""\bhowdy\b""") to "hello",
		rx("""\bmuch obliged\b""") to "thank you very much",
		rx("""\b(?:skedaddle|vamoose)\b""") to "run away",
		rx("""\bhigh-?tailed it\b""") to "ran away",
		rx("""\bhigh-?tail(?:ing)? it\b""") to "run away",
		rx("""\bthe jig(?:'s| is) up\b""") to "it's over",
		rx("""\b(?:greenhorn|tenderfoot)s\b""") to "beginners",
		rx("""\b(?:greenhorn|tenderfoot)\b""") to "beginner",
		rx("""\byonder's\b""") to "over there is",
		rx("""\b(?:over |out )?yonder\b""") to "over there",
		// gangster and police slang
		rx("""\bpacking heat\b""") to "carrying a gun",
		rx("""\bpacking (a|his|her|their|the|my|your) (?:heater|gat|rod|roscoe|piece|gun|pistol|revolver)\b""") to "carrying $1 gun",
		rx("""\b(has|had|got|with|pulled|pulls|drew|draws) (a|his|her|their|the|my|your) (?:heater|gat|roscoe|piece)\b""") to "$1 $2 gun",
		rx("""(?<!self-)\bmade men\b""") to "full members of the mafia",
		rx("""(?<!self-)\ba made man\b""") to "a full member of the mafia",
		rx("""\b(?:rub|bump) $OBJ (?:out|off)\b""") to "kill $1",
		rx("""\b(?:rubbed|bumped) $OBJ (?:out|off)\b""") to "killed $1",
		rx("""\brub out (the|that|this) (\w+)\b""") to "kill $1 $2",
		rx("""\brubbed out (the|that|this) (\w+)\b""") to "killed $1 $2",
		rx("""\bsleeps with the fishes\b""") to "is dead at the bottom of the sea",
		rx("""\bsleeping with the fishes\b""") to "dead at the bottom of the sea",
		rx("""\bsleep with the fishes\b""") to "be dead at the bottom of the sea",
		rx("""\bomert[àa]\b""") to "the law of silence",
		rx("""\bpizzo\b""") to "protection money",
		rx("""\bpicciotti\b""") to "young men",
		rx("""\bpicciotto\b""") to "young man",
		rx("""\bconsiglieres?\b""") to "advisor",
		rx("""\bspeakeasies\b""") to "secret bars",
		rx("""\bspeakeasy\b""") to "secret bar",
		rx("""\b(?:hooch|moonshine)\b""") to "illegal alcohol",
		rx("""\bbootleg (liquor|booze|whiskey|alcohol)\b""") to "illegal $1",
		rx("""\bsweat (him|her|them)\b(?! out)""") to "pressure $1",
		rx("""\brats? $OBJ out\b""") to "inform on $1 to the police",
		rx("""\bratted $OBJ out\b""") to "informed on $1 to the police",
		rx("""\bratting $OBJ out\b""") to "informing on $1 to the police",
		rx("""\bsquealed on $OBJ\b""") to "informed on $1 to the police",
		rx("""\bsqueals? on $OBJ\b""") to "inform on $1 to the police",
		// street slang
		rx("""\b(you|you're|he's|she's|they're|we're|I'm|are you|is he|is she|y'all) strapped\b(?! for)""") to "$1 armed",
		rx("""\bopps\b""") to "enemies",
		rx("""\b(?:rolling|rollin') up on\b""") to "coming at",
		rx("""\brolled up on\b""") to "came at",
		rx("""\bhit (?:a|another|one more|this|that) lick\b""") to "do a robbery",
		rx("""\bfive-o\b""") to "police",
		rx("""\btrap houses?\b""") to "drug house",
		rx("""\bgive $OBJ (?:a )?wedgies?\b""") to "humiliate $1",
		rx("""\bgives $OBJ (?:a )?wedgies?\b""") to "humiliates $1",
		rx("""\bgave $OBJ (?:a )?wedgies?\b""") to "humiliated $1",
		rx("""\bgiving $OBJ (?:a )?wedgies?\b""") to "humiliating $1",
		// old English (Kingdom Come, Baldur's Gate and the like)
		rx("""\bhold (?:thy|your|thine) tongue\b""") to "be quiet",
		rx("""\bprithee\b""") to "please",
		rx("""\bmethinks\b""") to "I think",
		rx("""\b(?:mayhap|perchance)\b""") to "maybe",
		rx("""\bverily\b""") to "truly",
		rx("""\bforsooth\b""") to "indeed",
		rx("""\bnaught\b""") to "nothing",
		rx("""\b(?:m'lord|milord)\b""") to "my lord",
		rx("""\b(?:m'lady|milady)\b""") to "my lady",
		rx("""\bwherefore\b""") to "why",
		rx("""\bwhence\b""") to "from where",
		rx("""\bthither\b""") to "there",
		rx("""\bhither\b""") to "here",
		// soldiers' calls
		rx("""\btango down\b""") to "the enemy is dead",
		rx("""(?<=, )moving (to|up|out|in)\b""") to "we're moving $1",
		rx("""^man down\b""") to "a man has fallen",
		rx("""\bfrag out\b""") to "grenade",
		rx("""\b(?:roger|copy) that\b""") to "understood",
		rx("""^move out\b""") to "let's go",
		rx("""^(?:all |room |area )?clear(?=!*$)""") to "the area is clear",
		// idioms
		rx("""\b(is|are|was|were|it's|it is) (?:crawling|swarming) with\b""") to "$1 full of",
		rx("""\b(want|wants|wanted|need|needs|needed|'d like|would like) a (?:quick )?word with (you|him|her|them|me|us)\b""") to "$1 to talk to $2",
		rx("""\b(want|wants|wanted|need|needs|needed|'d like|would like) a (?:quick )?word\b""") to "$1 to talk to you",
		rx("""\bworry about yourself\b""") to "take care of yourself",
		rx("""\bcatch you on the flip(?: ?side)?\b""") to "see you later",
		rx("""\bsay less\b""") to "understood",
		rx("""^do what now\?""") to "what do you mean?",
		rx("""\b(?:it'?ll|it will) be a cold day in (?:\w+ )?hell(?=[.!?]|$)""") to "that will never happen",
		rx("""\b(he|she|it)(?:'s| has| is) got another thing coming\b""") to "$1 is badly mistaken",
		rx("""\b(you|they|we)(?:'ve| have)? got another thing coming\b""") to "$1 are badly mistaken",
		rx("""\bpull(?:ed)? the plug on\b""") to "cut ties with",
		rx("""\bpulling the plug on\b""") to "cutting ties with",
		rx("""\bpull up (?:in )?front of\b""") to "stop in front of",
		rx("""\bblackball(ed|ing)?\b""") to "blacklist$1",
		rx("""\bpoof!""") to "just like that!",
		rx("""\bwith your bun in her oven\b""") to "pregnant with your baby",
		rx("""\b(?:a|your|his|my) bun in (?:the|her) oven\b""") to "a baby on the way",
		rx("""\bhang(ing)? out with\b""") to "spend$1 time with",
		// someone who stops answering: "she ghosted me"
		rx("""\bghosted (me|you|him|her|us|them)\b""") to "cut off all contact with $1",
		rx("""\bghosting (me|you|him|her|us|them)\b""") to "cutting off all contact with $1",
		rx("""\bghost (me|you|him|her|us|them)\b""") to "cut off all contact with $1",
		rx("""\bgot ghosted\b""") to "was abandoned",
		rx("""\bpeaced out\b""") to "left",
		rx("""\bhook (me|us) up(?=[?!.,]|$)""") to "introduce $1 to someone",
		rx("""\bhooking up with\b""") to "dating",
		rx("""\bhooked up with\b""") to "dated",
		rx("""\bhook up with\b""") to "date",
		// "I got you": I understand, or I'm here for you, not "I caught you"
		rx("""(?:^|(?<=[.!?] ))I got you(?=[.!]|$)""") to "I'm with you",
		rx("""\bmy guy\b""") to "my friend",
		rx("""(?<=[,!] )bro(?:-bro)?(?=[.!?,]|$)""") to "brother",
		rx("""\bdude\b""") to "man",
		// "baby" said to someone, not a child: "Drinks for everyone, baby!"
		rx("""(?<=[,!] )baby(?=[.!?,]|$)""") to "darling",
		rx("""^baby(?=, )""") to "darling",
		// swearing the translator takes word for word
		rx("""\bget fucked\b""") to "go to hell",
		rx("""\byou fuck(?=[!.?,]|$)""") to "you bastard",
		rx("""\bfuckin'? A\b""") to "absolutely",
		rx("""\b(really|just|so|even) fuckin'?\s+(?=(?:you|me|him|her|it|us|them)\b)""") to "$1 ",
		rx("""\byou fuckin'? (understand|hear|listen|get|know|think|want|see)\b""") to "you $1",
		rx("""\bfuckin'(?=\s)""") to "fucking",
		rx("""(?:^|(?<=[.!?\u2026] ))((?:oh|ah|aw|aww|ooh|uh),? )?shit(?=[!.\u2026,]|$)""") to "$1damn",
		rx("""\bholy shit\b""") to "oh my God",
		rx("""^no shit\b""") to "obviously",
		rx("""\bget (my|your|his|her|our|their) shit together\b""") to "get $1 life in order",
		rx("""\b(my|your|his|her|our|their) shit\b""") to "$1 stuff",
		rx("""\bpiece of shit (car|truck|boat|phone|place|house|gun)\b""") to "junk $1",
		rx("""\bpiece of shit\b""") to "bastard",
		rx("""\b(is|'s|are|'re) shitting (?:his|her|their|your) pants\b""") to "$1 terrified",
		rx("""\bdon't shit yourself\b""") to "don't panic",
		// button prompts: the button is a picture the reader leaves out, "Hold to sprint"
		rx("""\b(Press|Hold|Use)to\b""") to "$1 to",
		rx("""^Hold(?= (?:[LR][123]|or|and|to|down)\b)""") to "Press and hold",
		rx("""^Press to\b""") to "Press the button to",
		rx("""^Release to\b""") to "Let go of the button to",
		rx("""\bloadout\b""") to "equipment",
		rx("""^Keep (left|right)\b""") to "stay on the $1",
		rx("""^Stash (the|your)\b""") to "hide $1"
	)
	private val ANY = Regex(GENERAL.joinToString("|") { "(?:${it.first.pattern})" }, RegexOption.IGNORE_CASE)

	private class SeriesRule(val rx: Regex, val repl: String, val condition: Regex? = null)

	private val SELLING = rx("""\b(?:sell|sold|selling|stolen|loot|valuables|trinkets|jewel|jewelry|take it|price|pay|buys|fenced|goods)\b""")
	private val LAW_KEEP = listOf("against", "above", "break", "breaking", "broke", "broken", "obey", "obeying", "within", "by",
		"under", "follow", "following", "respect", "it's", "is", "that's", "was", "uphold", "upholding",
		"enforce", "I'm", "am", "letter of", "according to", "eyes of").joinToString("") { "(?<!\\b$it )" }

	private val HONORIFICS = listOf(
		// "-san" stays: without it the translator takes a name like "Majima" for a woman's
		SeriesRule(rx("""\b([A-Z][a-z]+)-(?:chan|kun|sama|senpai|sensei|dono|han|bo)\b""", ignoreCase = false), "$1"),
		SeriesRule(rx("""\b(?:nii-?san|nii-?chan|onii-?san|onii-?chan|aniki)\b"""), "big brother"),
		SeriesRule(rx("""\b(?:nee-?san|onee-?san|onee-?chan|ane-?san)\b"""), "big sister")
	)
	/** "whacked him" is killed him in a crime story, hit him elsewhere. */
	private val KILLED = listOf(
		SeriesRule(rx("""\bwhacks? (him|her|them|you|me|us|the \w+|that \w+)\b"""), "kill $1"),
		SeriesRule(rx("""\bwhacked (him|her|them|you|me|us|the \w+|that \w+)\b"""), "killed $1"),
		SeriesRule(rx("""\bwhacking $OBJ\b"""), "killing $1"),
		SeriesRule(rx("""\b(got|get|gets|getting) whacked\b"""), "$1 killed")
	)
	private val ERE = listOf(SeriesRule(rx("""\bere\b"""), "before"))

	private val SERIES: Map<String, List<SeriesRule>> = mapOf(
		"gta" to KILLED + listOf(
			SeriesRule(rx("""\b(nice|new|sweet|fancy|my|your|his|her|that|this) whip\b"""), "$1 car"),
			SeriesRule(rx("""\bchicks\b"""), "girls"),
			SeriesRule(rx("""\bchick\b"""), "girl"),
			SeriesRule(rx("""\b(so|real|really|pretty|damn|fucking) dope\b"""), "$1 cool"),
			SeriesRule(rx("""\bdope!"""), "cool!"),
			SeriesRule(rx("""\b(?:a )?lame(?=[,.!?]|$)"""), "boring"),
			SeriesRule(rx("""\b(?:yeyo|yayo)\b"""), "cocaine"),
			SeriesRule(rx("""\bsome blanco\b"""), "some cocaine"),
			SeriesRule(rx("""\bstay on his ass\b"""), "stay right behind him"),
			SeriesRule(rx("""\bstay on her ass\b"""), "stay right behind her"),
			SeriesRule(rx("""\bstay on their ass(?:es)?\b"""), "stay right behind them"),
			SeriesRule(rx("""\bthe Keys\b""", ignoreCase = false), "the islands"),  // Leonida's islands, not keys
			SeriesRule(rx("""\ball over the keys\b"""), "all over the islands"),
			SeriesRule(rx("""(?<=, )(?:dog|dawg)(?=[.!?,]|$)"""), "man"),
			SeriesRule(rx("""(?<=, )mama(?=[.!?,]|$)"""), "girl"),
			SeriesRule(rx("""^Chase(?= [A-Z]|$)""", ignoreCase = false), "Pursue"),  // a mission goal, not a name
			SeriesRule(rx("""\bcap (him|her|them|you|that \w+)\b"""), "shoot $1"),
			SeriesRule(rx("""\bcapped (him|her|them|you|that \w+)\b"""), "shot $1")
		),
		"mafia" to KILLED,
		"lanoire" to KILLED,
		"maxpayne" to KILLED,
		"persona" to HONORIFICS + listOf(
			SeriesRule(rx("""\bcalling cards\b"""), "warning cards"),
			SeriesRule(rx("""\bcalling card\b"""), "warning card")
		),
		"sh" to listOf(SeriesRule(rx("""\bstatic\b"""), "noise")),  // the radio's warning
		"bully" to listOf(
			SeriesRule(rx("""\b(get|got|getting|gets) detention\b"""), "$1 punished after school"),
			SeriesRule(rx("""\bdetention\b"""), "after-school punishment")
		),
		"kcd" to ERE,
		"bg3" to ERE,
		"tlou" to listOf(
			SeriesRule(rx("""^(Runners|Clickers|Stalkers|Bloaters|Shamblers|Infected)!"""), "$1 are coming!")
		),
		"rdr" to listOf(
			// in a western "the law" is the lawmen, except "against the law", "it's the law", ...
			SeriesRule(rx("""$LAW_KEEP\bthe law\b(?!\s+(?:of|says|said|is clear|and order|is the law))"""), "the police"),
			SeriesRule(rx("""\b(the|a|that|this|another|their|his|her|your) posse\b"""), "$1 pursuers"),
			SeriesRule(rx("""\b(your|my|his|her|the|a|that) repeater\b"""), "$1 rifle"),
			SeriesRule(rx("""\b(the|a|that|your) trapper\b"""), "$1 fur trader"),
			SeriesRule(rx("""\b(a|the|any|your|that) fence\b"""), "$1 stolen goods dealer", SELLING),
			SeriesRule(rx("""\bfence (it|them|this|that|these|those)\b(?! off| in)"""), "sell $1 as stolen goods")
		),
		"yakuza" to HONORIFICS + listOf(
			SeriesRule(rx("""\b(?:oyaji|oyassan)\b"""), "old man"),
			SeriesRule(rx("""\blieutenants\b"""), "deputies"),
			SeriesRule(rx("""\blieutenant\b"""), "deputy"),
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
		"" to listOf(
			Triple(null, "بطاقات التموينية", "بطاقات التموين"),
			Triple(rx("""\bbachelor part(?:y|ies)\b"""), "حزب البكالوريوس", "حفلة توديع العزوبية"),
			Triple(rx("""\bbachelor part(?:y|ies)\b"""), "حفلة البكالوريوس", "حفلة توديع العزوبية")
		),
		"tlou" to listOf(
			Triple(rx("""\bspores?\b"""), "الجراثيم", "الأبواغ"),
			Triple(rx("""\bspores?\b"""), "جراثيم", "أبواغ")
		)
	)
	private val SHOOT_EN = rx("""\b(?:shoot|shoots|shot|shooting|fire|fires|fired|firing)\b""")
	/** Arabic patterns the translator gets wrong: (English that must be in the line or null, wrong, right). */
	private val AR_PATTERNS: List<Triple<Regex?, Regex, String>> = listOf(
		Triple(null, Regex("(?<![ء-ي])([وف]?)[اإأ]بقى(?![ء-ي])"), "$1ابق"),  // «إبقى خلفي» → «ابق خلفي», «وابقى» → «وابق»
		// "unless they shoot first" → «إلا إذا أطلقوا أولاً»: the fire went missing
		Triple(SHOOT_EN, Regex("(?<![ء-ي])((?:و|ف)?[أا]طلق(?:وا|ت|نا)?) (?=(?:أولاً|أولا|أولًا|علي(?:ه|ها|هم|نا|ك|كم)|عليّ)(?![ء-ي]))"), "$1 النار ")
	)

	private val GROUP_REF = Regex("""\$(\d)""")

	/** Replace, keeping a capital where the replaced words had one ("Howdy" → "Hello"). */
	private fun sub(r: Regex, repl: String, text: String): String = r.replace(text) { m ->
		var out = GROUP_REF.replace(repl) { g -> m.groupValues.getOrElse(g.groupValues[1].toInt()) { "" } }
		if(m.value.isNotEmpty() && m.value[0].isUpperCase() && out.isNotEmpty() && out[0].isLowerCase())
			out = out[0].uppercaseChar() + out.substring(1)
		out
	}

	/** The line in plain English for the translator: game words, then the series' words. */
	fun rewrite(english: String, series: String = ""): String
	{
		var s = english
		if(ANY.containsMatchIn(s)) for((r, repl) in GENERAL) s = sub(r, repl, s)
		for(rule in SERIES[series].orEmpty())
			if(rule.condition == null || rule.condition.containsMatchIn(s)) s = sub(rule.rx, rule.repl, s)
		return s
	}

	fun fixArabic(arabic: String, english: String, series: String = ""): String
	{
		var s = arabic
		for(group in if(series.isEmpty()) listOf("") else listOf("", series))
			for((cond, wrong, right) in AR_FIXES[group].orEmpty())
				if(wrong in s && (cond == null || cond.containsMatchIn(english))) s = s.replace(wrong, right)
		for((cond, r, right) in AR_PATTERNS)
			if((cond == null || cond.containsMatchIn(english)) && r.containsMatchIn(s)) s = r.replace(s, right)
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
		"rescue" to mapOf("إنقاذ" to "أنقذ", "الإنقاذ" to "أنقذ"),
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
		"drive" to mapOf("قيادة" to "قد", "القيادة" to "قد"),
		"draw" to mapOf("سحب" to "اسحب"),
		"pull" to mapOf("سحب" to "اسحب"),
		"take" to mapOf("أخذ" to "خذ", "اتخاذ" to "خذ"),
		"grab" to mapOf("أخذ" to "خذ", "الإمساك" to "أمسك"),
		"put" to mapOf("وضع" to "ضع"),
		"place" to mapOf("وضع" to "ضع"),
		"leave" to mapOf("ترك" to "اترك", "مغادرة" to "غادر"),
		"shoot" to mapOf("إطلاق" to "أطلق"),
		"fire" to mapOf("إطلاق" to "أطلق"),
		"find" to mapOf("العثور" to "اعثر", "إيجاد" to "جد", "البحث" to "ابحث"),
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
		"climb" to mapOf("تسلق" to "تسلق"),
		"escort" to mapOf("مرافقة" to "رافق"),
		"choose" to mapOf("اختيار" to "اختر"),
		"select" to mapOf("اختيار" to "اختر", "تحديد" to "حدد"),
		"sit" to mapOf("الجلوس" to "اجلس", "جلوس" to "اجلس"),
		"move" to mapOf("التحرك" to "تحرك", "تحريك" to "حرّك", "نقل" to "انقل"),
		"dance" to mapOf("الرقص" to "ارقص"),
		"examine" to mapOf("فحص" to "افحص"),
		"pick" to mapOf("التقاط" to "التقط", "إلتقاط" to "التقط", "اختيار" to "اختر"),
		"deal" to mapOf("التعامل" to "تعامل"),
		"pursue" to mapOf("مطاردة" to "طارد", "ملاحقة" to "لاحق"),
		"chase" to mapOf("مطاردة" to "طارد", "ملاحقة" to "لاحق"),
		"change" to mapOf("تغيير" to "غيّر", "التغيير" to "غيّر"),
		"visit" to mapOf("زيارة" to "زر"),
		"enter" to mapOf("الدخول" to "ادخل", "دخول" to "ادخل"),
		"equip" to mapOf("تجهيز" to "جهّز"),
		"investigate" to mapOf("التحقيق" to "حقق"),
		"order" to mapOf("طلب" to "اطلب"),
		"spend" to mapOf("قضاء" to "اقض")
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

	/** The line opens with a command we know ("Buy Maria a ring"), not with a noun. */
	fun startsWithCommand(english: String): Boolean
	{
		val words = english.trimStart(' ', '"', '\'', '-', '–', '—').split(Regex("\\s+")).filter { it.isNotEmpty() }
		if(words.size < 2) return false
		return words[0].lowercase().trim(',', '.', '!', '?') in VERB_NOUNS &&
			words[1].lowercase().trim(',', '.', '!', '?') !in NOT_A_COMMAND_NEXT
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
