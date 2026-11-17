package com.metallic.chiaki.tarjim

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the laptop program's tests/test_gender.py: both engines must agree. */
class GenderTest
{
	@Test
	fun commandsToAManOrNobodyAreMasculine()
	{
		assertEquals("خافيير، غطّ الجانب الأيسر.", Gender.masculinize("خافيير، غطّي الجانب الأيسر."))
		assertEquals("ابصقها الآن!", Gender.masculinize("ابصقيها الآن!"))
		assertEquals("خذ البندقية وغطّني.", Gender.masculinize("خذي البندقية وغطّيني."))
		assertEquals("ابق خلفي ولا تصدر صوتاً.", Gender.masculinize("ابقي خلفي ولا تصدري صوتاً."))
		assertEquals("لا تخف!", Gender.masculinize("لا تخافي!"))
		assertEquals("اهدأ، كل شيء بخير.", Gender.masculinize("اهدئي، كل شيء بخير."))
		assertEquals("أعطني المفتاح.", Gender.masculinize("اعطيني المفتاح."))
		assertEquals("سأعطيك المال، ثم اذهب.", Gender.masculinize("سأعطيك المال، ثم اذهبي."))
		assertEquals("ماذا تفعل هنا؟", Gender.masculinize("ماذا تفعلين هنا؟"))
		assertEquals("قل لي الحقيقة.", Gender.masculinize("قولي لي الحقيقة."))
	}

	@Test
	fun wordsThatOnlyLookLikeCommandsStay()
	{
		assertEquals("جدي كان رجلاً طيباً.", Gender.masculinize("جدي كان رجلاً طيباً."))
		assertEquals("دوري الآن.", Gender.masculinize("دوري الآن."))
		assertEquals("دعني ألقي نظرة على الجرح.", Gender.masculinize("دعني ألقي نظرة على الجرح."))
		assertEquals("لا تعني شيئاً.", Gender.masculinize("لا تعني شيئاً."))
		assertEquals("وقف الرجل أمام الباب.", Gender.feminize("وقف الرجل أمام الباب."))
		assertEquals("إذا أردت، أذهب معك.", Gender.feminize("إذا أردت، أذهب معك."))
	}

	@Test
	fun commandsToAWomanAreFeminine()
	{
		assertEquals("سايدي، ابقي هنا وراقبي الخيول.", Gender.feminize("سايدي، ابق هنا وراقب الخيول."))
		assertEquals("كارين، ضعي الزجاجة جانباً وساعديني.", Gender.feminize("كارين، ضع الزجاجة جانباً وساعدني."))
		assertEquals("تيلي، لا تتحركي ولا تتركيني.", Gender.feminize("تيلي، لا تتحرك ولا تتركني."))
		assertEquals("سوزان، عودي إلى المخيم.", Gender.feminize("سوزان، عد إلى المخيم."))
		assertEquals("كارين، كوني حذرة.", Gender.feminize("كارين، كن حذرة."))
		assertEquals("مولي، أجيبيني!", Gender.feminize("مولي، أجبني!"))
		assertEquals("كارين، اقرئي هذا.", Gender.feminize("كارين، اقرأ هذا."))
	}
}
