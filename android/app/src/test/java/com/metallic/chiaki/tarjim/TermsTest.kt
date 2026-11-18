package com.metallic.chiaki.tarjim

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the laptop program's tests/test_terms.py: both engines must agree. */
class TermsTest
{
	@Test
	fun seriesOfAGame()
	{
		assertEquals("rdr", Terms.seriesOf("rdr2"))
		assertEquals("gta", Terms.seriesOf("gta5"))
		assertEquals("tlou", Terms.seriesOf("tlou2"))
		assertEquals("yakuza", Terms.seriesOf("lad8"))
		assertEquals("rdr", Terms.seriesOf("my-rdr", "Red Dead Redemption 2"))
		assertEquals("yakuza", Terms.seriesOf("x", "Like a Dragon Gaiden"))
		assertEquals("", Terms.seriesOf("default"))
		assertEquals("bg3", Terms.seriesOf("bg3", "Baldur's Gate 3"))
	}

	private fun rewrites(en: String, series: String, out: String) =
		assertEquals(out.lowercase(), Terms.rewrite(en, series).lowercase())

	@Test
	fun gameWordsInPlainEnglish()
	{
		rewrites("Hogtie him and put him on your horse.", "rdr", "tie him up and put him on your horse.")
		rewrites("Lasso the horse before it runs off.", "rdr", "catch the horse with your rope before it runs away.")
		rewrites("Parley with them first.", "", "negotiate with them first.")
		rewrites("We'll case the bank tomorrow.", "", "We'll scout the bank tomorrow.")
		rewrites("Lose the law before you go back to camp.", "rdr", "escape from the police before you go back to camp.")
		rewrites("The cops are on us, lose them!", "gta", "The cops are on us, escape from them!")
		rewrites("Shake them off!", "", "escape from them!")
		rewrites("If the law finds us here, we're finished.", "", "If the police finds us here, we're finished.")
		rewrites("The law is coming, saddle up!", "", "The police is coming, get on your horse!")
		rewrites("Uncle, wake up, we've got company.", "", "Uncle, wake up, we have visitors.")
		rewrites("Meet me at the safehouse.", "", "Meet me at the hideout.")
		rewrites("You've got a five-star wanted level.", "gta", "the police are chasing you with five stars.")
		rewrites("Take the stolen wagon to the fence.", "rdr", "Take the stolen wagon to the stolen goods dealer.")
		rewrites("Do your chores.", "", "Do your tasks.")
		rewrites("Lay low for a few days.", "", "stay out of sight for a few days.")
		rewrites("Kiryu-chan, you came!", "yakuza", "Kiryu, you came!")
		rewrites("He's my sworn brother.", "yakuza", "He's my brother by oath.")
		rewrites("The patriarch of the Dojima Family has spoken.", "yakuza", "The head of the Dojima Family has spoken.")
		rewrites("We hit the train tomorrow night.", "rdr", "We rob the train tomorrow night.")
	}

	@Test
	fun everydayWordsStay()
	{
		for((en, series) in listOf(
			"That's against the law, Arthur." to "rdr", "It's the law." to "rdr",
			"In case the bank is guarded, bring dynamite." to "rdr", "Jump over the fence and wait for me." to "rdr",
			"We can't lose him, he's family." to "rdr", "I don't want to lose them." to "",
			"Fish are jumping in the lake." to "rdr", "The car hit the truck." to "gta",
			"Hold up, I need to catch my breath." to "", "The company is doing well." to "gta",
			"He's the patriarch." to "rdr", "Kiryu-chan!" to "rdr", "Majima-san wants to see you." to "yakuza"))
			assertEquals(en, Terms.rewrite(en, series))
	}

	@Test
	fun commandsNotVerbalNouns()
	{
		assertEquals("اشتر بعض منشط في المتجر العام.", Terms.commandsNotNouns("شراء بعض منشط في المتجر العام.", "Buy some tonic at the general store."))
		assertEquals("ادفع أموال الحماية في الوقت المحدد.", Terms.commandsNotNouns("دفع أموال الحماية في الوقت المحدد.", "Pay the protection money on time."))
		assertEquals("تفاوض معهم أولاً.", Terms.commandsNotNouns("التفاوض معهم أولاً.", "negotiate with them first."))
		assertEquals("اسلخ الغزلان وخذ اللحم.", Terms.commandsNotNouns("سلخ الغزلان واتخاذ اللحم.", "Skin the deer and take the meat."))
		assertEquals("سايدي، اشتر بعض المنشط.", Terms.commandsNotNouns("سايدي، شراء بعض المنشط.", "Sadie, buy some tonic."))
		assertEquals("أنقذها!", Terms.commandsNotNouns("إنقاذها!", "Save her!"))
		// a noun, not a command
		assertEquals("الهروب مستحيل.", Terms.commandsNotNouns("الهروب مستحيل.", "Escape is impossible."))
		assertEquals("الصيد ممتع.", Terms.commandsNotNouns("الصيد ممتع.", "Hunting is fun."))
		assertEquals("شراء الأسلحة غير قانوني.", Terms.commandsNotNouns("شراء الأسلحة غير قانوني.", "The buying of guns is illegal."))
	}

	@Test
	fun arabicFixes()
	{
		assertEquals("ابتعد عن الأبواغ.", Terms.fixArabic("ابتعد عن الجراثيم.", "Stay away from the spores.", "tlou"))
		assertEquals("ابتعد عن الجراثيم.", Terms.fixArabic("ابتعد عن الجراثيم.", "Stay away from the germs.", "tlou"))
		assertEquals("ابتعد عن الجراثيم.", Terms.fixArabic("ابتعد عن الجراثيم.", "Stay away from the spores.", "rdr"))
	}

	@Test
	fun slangAndSeriesWords()
	{
		val cases = listOf(
			Triple("Howdy, partner. Much obliged.", "rdr", "Hello, partner. Thank you very much."),
			Triple("We gotta high-tail it outta here.", "rdr", "We gotta run away outta here."),
			Triple("The jig is up, Dutch.", "rdr", "It's over, Dutch."),
			Triple("Yonder's the cabin.", "rdr", "Over there is the cabin."),
			Triple("He's packing a heater, be careful.", "lanoire", "He's carrying a gun, be careful."),
			Triple("Let's sweat him a little.", "lanoire", "Let's pressure him a little."),
			Triple("He's a made man now.", "mafia", "He's a full member of the mafia now."),
			Triple("Omertà is everything.", "mafia", "The law of silence is everything."),
			Triple("Pay the pizzo.", "mafia", "Pay the protection money."),
			Triple("They whacked him last night.", "mafia", "They killed him last night."),
			Triple("He ratted us out.", "gta", "He informed on us to the police."),
			Triple("You strapped?", "gta", "You armed?"),
			Triple("The opps are rolling up on us.", "gta", "The enemies are coming at us."),
			Triple("We gotta hit a lick tonight.", "gta", "We gotta do a robbery tonight."),
			Triple("Nice whip, bro.", "gta", "Nice car, bro."),
			Triple("Five-O! Run!", "gta", "Police! Run!"),
			Triple("Hold thy tongue, peasant!", "kcd", "Be quiet, peasant!"),
			Triple("Methinks he lies.", "kcd", "I think he lies."),
			Triple("Wait here ere nightfall.", "kcd", "Wait here before nightfall."),
			Triple("Tango down.", "", "The enemy is dead."),
			Triple("Roger that.", "", "Understood."),
			Triple("Clear!", "", "The area is clear!"),
			Triple("The favela is crawling with gangsters.", "maxpayne", "The favela is full of gangsters."),
			Triple("The boss wants a word with you.", "", "The boss wants to talk to you."),
			Triple("Don't worry about me, worry about yourself.", "", "Don't worry about me, take care of yourself."),
			Triple("The radio is making static again.", "sh", "The radio is making noise again."),
			Triple("Go to class or you'll get detention.", "bully", "Go to class or you'll get punished after school."),
			Triple("Don't let them give you a wedgie.", "bully", "Don't let them humiliate you."),
			Triple("We need to send the calling card.", "persona", "We need to send the warning card."),
			Triple("Makoto-senpai, wait!", "persona", "Makoto, wait!"),
			Triple("The lieutenant wants to see you.", "yakuza", "The deputy wants to see you."),
			Triple("Runners! Get behind me.", "tlou", "Runners are coming! Get behind me.")
		)
		for((en, series, out) in cases) assertEquals(out, Terms.rewrite(en, series))
	}

	@Test
	fun slangLeftAlone()
	{
		for((en, series) in listOf(
			"He's a self-made man." to "mafia", "He whacked him with a stick." to "bully",
			"Use your whip on the horse." to "rdr", "I'm strapped for cash." to "gta",
			"The coast is clear, let's go." to "", "I'll wait here." to "kcd",
			"The lieutenant wants to see you." to "maxpayne", "We need to send the calling card." to "gta",
			"Runners are fast." to "tlou", "The static on his shirt." to "rdr"))
			assertEquals(en, Terms.rewrite(en, series))
	}

	@Test
	fun arabicPatterns()
	{
		assertEquals("الرانرز قادمون! ابق خلفي.", Terms.fixArabic("الرانرز قادمون! إبقى خلفي.", "Runners are coming! Get behind me."))
		assertEquals("لا تطلق النار إلا إذا أطلقوا النار أولاً.", Terms.fixArabic("لا تطلق النار إلا إذا أطلقوا أولاً.", "Don't shoot unless they shoot first."))
		assertEquals("أطلقوا سراحه.", Terms.fixArabic("أطلقوا سراحه.", "They shot him free."))
		assertEquals("أطلقوا أولاً.", Terms.fixArabic("أطلقوا أولاً.", "They released it first."))
		assertEquals("persona", Terms.seriesOf("p5r"))
		assertEquals("kcd", Terms.seriesOf("kcd2"))
		assertEquals("sh", Terms.seriesOf("my-sh", "Silent Hill 2"))
	}

	@Test
	fun genderHamzaAndAnywhere()
	{
		assertEquals("ادفع للحماية وإلا سيحرقون متجرك.", Gender.masculinize("إدفعي للحماية وإلا سيحرقون متجرك."))
		assertEquals("إذا أردت، أذهب معك.", Gender.masculinize("إذا أردت، أذهب معك."))  // "I go" stays
		assertEquals("لا تقلق بشأني اعتن بنفسك.", Gender.masculinize("لا تقلق بشأني اعتني بنفسك."))
		assertEquals("لا تقلقي بشأني، اعتني بنفسك.", Gender.feminize("لا تقلقي بشأني، اعتن بنفسك."))
	}
}
