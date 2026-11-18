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
		assertEquals("", Terms.seriesOf("bg3", "Baldur's Gate 3"))
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
}
