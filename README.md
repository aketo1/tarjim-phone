<div align="center" dir="rtl">

<img src="docs/media/hero.gif" width="100%" alt="ترجِم للجوال: الجوال لحاله يترجم ألعاب الـPS5 ويرسم العربي على التلفزيون">

<br>

[![Download APK](https://img.shields.io/badge/Download-APK-f4f4f5?style=for-the-badge&labelColor=111114&logo=android&logoColor=white)](../../releases/latest)
![PS5](https://img.shields.io/badge/PS5-Remote%20Play-f4f4f5?style=for-the-badge&labelColor=111114&logo=playstation&logoColor=white)
![Offline](https://img.shields.io/badge/100%25-offline-f4f4f5?style=for-the-badge&labelColor=111114)
[![Built with AI](https://img.shields.io/badge/Built%20with-AI%20%C2%B7%20Claude-d97757?style=for-the-badge&labelColor=111114&logo=anthropic&logoColor=white)](#ai)

**[🎬 وش يسوي؟](#what) · [✨ المميزات](#features) · [⬇️ التثبيت](#install) · [🎮 كل مرة](#play) · [🤖 الذكاء الاصطناعي](#ai) · [❓ أسئلة](#faq)**

</div>

<div dir="rtl">

<a name="what"></a>

## 🎬 وش يسوي؟

الجوال ياخذ نسخة من صورة الـPS5 عن طريق شبكة البيت، يقرأ سطر الحوار الإنجليزي، **يترجمه داخل الجوال**، ويرسل العربي لتطبيق ترجِم على التلفزيون اللي يرسمه فوق اللعبة في نفس المكان. لعبتك ويدك ما يتغيّر فيهم شي، وما تحتاج لابتوب ولا إنترنت.

على Galaxy S20 FE السطر ياخذ حوالي **عُشر ثانية** والشاشة شغّالة.

</div>

```mermaid
flowchart LR
    PS5["🎮 PS5"] ==>|"HDMI · اللعبة كما هي"| TV["📺 التلفزيون"]
    PS5 -.->|"نسخة من الصورة عبر الشبكة"| PHONE["📱 الجوال<br>يقرأ · يترجم · يرسم"]
    PHONE -->|"العربي كصورة صغيرة"| TV
```

<div dir="rtl">

<a name="features"></a>

## ✨ المميزات

<table>
<tr>
<td width="44%" align="center"><img src="docs/media/phone.gif" width="300" alt="شاشات ترجِم على الجوال"></td>
<td valign="top">

- 📱 **بدون لابتوب:** الجوال يسوي كل الشغل لحاله.
- 🔌 **بدون إنترنت:** القراءة والترجمة داخل الجوال.
- 🎮 **ما يلمس اللعبة:** الـPS5 موصول بالتلفزيون مثل ما هو.
- 🖼️ **لعبتك هي الخلفية:** صورتها الرسمية، ووقت الترجمة البث المباشر. وتختار بين شكلين: «سينما» أو «رف الألعاب».
- 🧠 **يفهم كلام الألعاب:** أسماء الشخصيات والأماكن، وكلمات كل لعبة («the fence» = تاجر البضائع المسروقة)، وعامية الويسترن والمافيا والشوارع.
- 👥 **يعرف مين يكلّم:** المذكر للرجل والمؤنث للمرأة.
- 🎨 **لكل لعبة شكلها:** الخط والحجم واللون والخلفية والموضع.
- ✍️ **القلم جنب أي سطر:** اكتب الترجمة بطريقتك وتنحفظ.
- 🔁 **ملف اللعبة:** صدّر أسماءك وتعديلاتك وأرسلها واتساب، أو افتح ملف وصلك مباشرة. نفس ملفات نسخة الكمبيوتر.

</td>
</tr>
</table>

### 📚 مكتبة ٢٨ لعبة

قوائم أسماء وكلمات جاهزة لألعاب ما فيها عربي: ريد ديد، قراند (حتى **GTA 6**، أسماؤها من موقع روكستار الرسمي والعروض)، ياكوزا، ذا لاست أوف أس، سايلنت هيل ٢، بلدرز قيت ٣، بيرسونا ٥ وغيرها. ضغطة وحدة وتنضاف.

<p align="center"><img src="docs/media/library.jpg" width="820" alt="أغلفة الألعاب الـ٢٨ في المكتبة"></p>

<table>
<tr>
<td align="center" width="33%"><b>الرئيسية</b><br><img src="docs/media/phone-home.png" width="240" alt="الرئيسية"></td>
<td align="center" width="33%"><b>رف الألعاب</b><br><img src="docs/media/phone-shelf.png" width="240" alt="رف الألعاب"></td>
<td align="center" width="33%"><b>المكتبة</b><br><img src="docs/media/phone-library.png" width="240" alt="المكتبة"></td>
</tr>
</table>

<a name="install"></a>

## ⬇️ التثبيت

1. من [صفحة الإصدارات](../../releases/latest) نزّل **`Tarjim-Phone.apk`** وثبّته (اسمح بالتثبيت من هذا المصدر لو سألك).
2. تطبيق ترجِم لازم يكون على التلفزيون. يتثبّت مرة وحدة من [نسخة الكمبيوتر](https://github.com/aketo1/tarjim).

<details>
<summary><b>🛠️ أول مرة: الإعداد</b> (اضغط)</summary>
<br>

افتح التطبيق وروح لـ**«الإعداد»**:

1. **عنوان التلفزيون:** افتح ترجِم على التلفزيون وتلقاه مكتوب بالأصفر.
2. **عنوان الـPS5:** الإعدادات ‹ الشبكة ‹ عرض حالة الاتصال ‹ عنوان IP.
3. **اربط الـPS5:**
   - على الـPS5 أضف مستخدم بحساب PSN **ثاني** (مجاني) وفعّل: الإعدادات ‹ النظام ‹ اللعب عن بُعد ‹ تمكين اللعب عن بُعد.
   - اضغط «اربط الـPS5» وسجّل دخول بالحساب الثاني في صفحة Sony.
   - على الـPS5 بالحساب الثاني: الإعدادات ‹ النظام ‹ اللعب عن بُعد ‹ ربط جهاز، واكتب الأرقام الثمانية في الجوال.

> 🔐 **ليش حساب ثاني؟** لو اتصل الجوال بنفس الحساب اللي تلعب فيه، الـPS5 يفصل يدك.

</details>

<a name="play"></a>

## 🎮 كل مرة

1. شغّل لعبتك بحسابك العادي.
2. افتح ترجِم، اختر اللعبة، واضغط **«ابدأ الترجمة»**.
3. اضغط **«عتّم الشاشة»** وخلّ الجوال على الشاحن.

> 📵 **لا تقفل شاشة الجوال.** أندرويد يبطّئ التطبيق لو انطفت الشاشة: الترجمة تتأخر ثانية ونص ثم يفصل. زر التعتيم يخلي الشاشة سوداء وما تستهلك شي تقريباً.

<a name="ai"></a>

## 🤖 انبنى بمساعدة الذكاء الاصطناعي

**ترجِم انبنى بمساعدة الذكاء الاصطناعي:** الفكرة والطلبات من صاحب المشروع بالعربي، و**Claude** (من Anthropic، عن طريق Claude Code) يكتب الكود ويجرّبه ويقيس سرعته ويصلّح أخطاءه. وساعد **Gemini** من Google في جولة تحسين العربي.

| اليوم | وش صار |
| --- | --- |
| ١٥ نوفمبر ٢٠٢٦ | نسخة الكمبيوتر الأولى: العربي يطلع على التلفزيون فوق اللعبة |
| ١٦ نوفمبر | نسخة الجوال: الجوال لحاله يقرأ ويترجم ويرسم |
| ١٧ نوفمبر | مكتبة الألعاب، والإكس بوكس (تجريبي) |
| ١٨ نوفمبر | الشكل الجديد بصور الألعاب، عربي أوضح، وكلمات ٢٨ لعبة |

<a name="faq"></a>

## ❓ أسئلة سريعة

<details>
<summary><b>الترجمة هنا مثل نسخة الكمبيوتر؟</b></summary>
<br>
الواجهة والقوائم والكلمات نفسها، لكن المترجم اللي يشتغل داخل الجوال أصغر، فالجودة أقل شوي من نسخة الكمبيوتر.
</details>

<details>
<summary><b>ليش الإنجليزي يتغطّى بخلفية داكنة؟</b></summary>
<br>
عشان ما يبان تحت العربي. شكل الخلفية تغيّره من «المظهر».
</details>

<details>
<summary><b>يشتغل مع الإكس بوكس؟</b></summary>
<br>
مكتوب كتجربة، لكن ما انجرّب على إكس بوكس حقيقي للحين.
</details>

<details>
<summary><b>انقطعت الصورة؟</b></summary>
<br>
التطبيق يعيد الاتصال لحاله. لو ما رجع، تأكد إن الجوال والـPS5 والتلفزيون على نفس الشبكة.
</details>

</div>

---

<details>
<summary><b>For developers</b></summary>

Everything technical is in [`docs/REPORT.md`](docs/REPORT.md). The app is built by GitHub Actions on every push to `main` and published to the `latest` release. The pictures in this page come from the laptop program's `launcher/make_readme_media.py`; the page itself is the laptop program's interface (`app/web`), copied in by its `launcher/sync_phone_web.py`.

Built with the help of AI: Claude (Anthropic) through Claude Code, and Gemini (Google) for one round of Arabic wording.

Built on the Android client of [chiaki-ng](https://github.com/streetpea/chiaki-ng) (AGPL-3.0, see `COPYING` and `LICENSES/`). Personal, non-distributed project. The games' pictures belong to their publishers.

</details>
