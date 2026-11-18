# Tarjim for the phone: project report

Written 2026-11-16. Handover for anyone, human or AI, who continues this
project. It says what exists, what was verified and how, what was measured,
and what is still open. The laptop program it grew out of lives in the
private repository `aketo1/tarjim`; its `docs/REPORT.md` explains the idea,
the TV overlay app and the wire protocol in depth.

> **بالعربي باختصار:** هذا تطبيق أندرويد يسوي شغل اللابتوب كله من الجوال:
> ياخذ نسخة من صورة الـPS5، يقرأ السطر الإنجليزي، يترجمه داخل الجوال،
> ويرسل العربي لتطبيق ترجِم على التلفزيون. ما يحتاج لابتوب وقت اللعب.

## 1. What it does

While the user plays a PS5 game on the TV, the phone receives a video-only
copy of the picture (Sony's Remote Play protocol, signed in with a second PSN
account), reads the English subtitle, translates it on the device and sends
the Arabic as a small picture to the Tarjim overlay app on the TV, which
draws it over the game. The PS5 still feeds the TV directly over HDMI.

The TV overlay app is the same one the laptop program installs
(`dev.tarjim.tv`, TCP port 47800). The phone does not install it: that is
still done once from the laptop program.

## 2. Status

Worked live on 2026-11-16 on a Samsung Galaxy S20 FE 5G (SM-G781B, Android
13, Snapdragon 865) with a PS5 and the TCL TV, in Red Dead Redemption 2: the
user confirmed Arabic on the TV and a working controller.

| Part | State | How it was verified |
| --- | --- | --- |
| Build on GitHub Actions | Works | Runs succeed; the APK is published to the `latest` release |
| Linking to the PS5 (PIN) | Works | The user linked the phone; the console answered and returned its keys |
| Picture copy on the phone | Works | Frames arrive at about 30 per second |
| Reading, translating, drawing on the TV | Works | Lines logged on the phone with timings; the user saw them on the TV |
| Interface (same as the laptop's) | Works, per the user | The user tried it and answered "fine"; the developer never saw it, because the phone was locked at every capture attempt |
| Names through stand-ins | Built | Not confirmed live on the phone |
| Sony sign-in for the Account ID | Built | **Never run.** Written from the laptop library's request shapes |
| Reading-area picture in settings | Built | **Never run** |
| Black resting screen, keep-awake | Built | Not confirmed; the measurement in section 5 is why it exists |

## 3. How it is put together

The repository started as a copy of the Android client of chiaki-ng
(AGPL-3.0, commit in `UPSTREAM_COMMIT`), which already had the hard parts:
the Remote Play protocol in C, PIN registration and a hardware video decoder.
The desktop GUI, the Switch port and other unused parts were left out.

Tarjim's own code is in `android/app/src/main/java/com/metallic/chiaki/tarjim/`
(the package name of the original is kept because the native library binds
to it; the application ID is `dev.tarjim.mobile`):

| File | What it holds |
| --- | --- |
| `TarjimEngine.kt` | The loop: session into an `ImageReader`, copy the subtitle area, read, translate, render, send; names; watchdog; preview |
| `TvLink.kt` | Client for the TV overlay protocol v1 |
| `TarjimService.kt` | Foreground service that owns the engine; state for the interface |
| `PhoneServer.kt` | HTTP server on 127.0.0.1 with the laptop program's JSON API |
| `TarjimActivity.kt` | WebView that shows the interface; keep-awake, black screen, linking |
| `PsnLoginActivity.kt` | Sony's sign-in page for the second account, to learn its Account ID |
| `Store.kt` | Addresses, per-game profiles (JSON), the user's line fixes; `Look` |
| `assets/web/` | The laptop program's interface (`index.html`, `app.css`, `app.js`) with a phone mode |

Changes to the original client: a mute switch in `chiaki-jni.c`
(`setAudioMuted`), the launcher is Tarjim's screen, the linking form is in
Arabic with broadcast search off, and the app ID, icon and signing key.

### One line, step by step

1. The decoder writes each frame into an `ImageReader` surface. Only its
   arrival is used (frame count, watchdog).
2. `PixelCopy` copies the subtitle area (default: the bottom 32%, central
   84%) into a bitmap. Reading the decoder's buffer directly aborted on this
   phone (section 6), and `PixelCopy` also converts to RGB.
3. ML Kit text recognition reads the area (about 40 ms). Lines centred on
   the screen are kept, stacked lines are joined, the longest group wins.
4. The first time a text is seen its translation starts on another thread.
5. The line is confirmed when it is read the same twice (and, for games that
   type their text, unchanged for the profile's settle time).
6. Translation: the user's own fix if there is one, else ML Kit on-device
   English to Arabic with glossary names replaced by stand-in names and
   swapped back (the same idea as the laptop's `NameGuard`).
7. The Arabic is drawn with Android's own text layout (which shapes Arabic)
   on an optional dark plate that covers the English, compressed as PNG and
   sent to the TV. It is kept alive every 250 ms and cleared when the
   English has left and the reading time has passed.

The session never sends controller input (`setControllerState` is not
called in Tarjim mode) and plays no sound.

### The interface

`PhoneServer` implements the subset of the laptop's API the pages need
(`/api/hello`, `state`, `check`, `events`, `start`, `stop`, `profile`,
`profile/new`, `preview`, `frame`, `line/fix`, `tv/try`) plus `/api/setup`.
`hello` answers `app: "phone"`, which switches the pages into phone mode:
a Setup screen instead of the Guide, no "cover" background (the phone cannot
repaint the scene under the letters, it uses the plate), and the black
resting screen.

Linking the PS5 from Setup is two steps. `PsnLoginActivity` opens Sony's
sign-in page; when Sony redirects with a one-time code, the code is exchanged
for the account's numeric ID, which is stored as Remote Play wants it (8
bytes, little-endian, base64). Then the original client's PIN form opens with
the address and the ID filled in.

## 4. Building

`.github/workflows/build.yml` builds on every push to `main` (Ubuntu, JDK 17,
`protobuf-compiler`) and replaces the `latest` release with
`Tarjim-Phone.apk` and `native-libs.zip`. A fixed signing key
(`android/tarjim.keystore`, in this private repository) lets every build
install over the previous one.

The native part cannot be built on Windows (OpenSSL's build needs `make`).
For local work on the Kotlin side, take the three libraries
`libchiaki-jni.so`, `libc++_shared.so` and `liboboe.so` from
`native-libs.zip` into `android/app/prebuilt/arm64-v8a/` and run
`gradlew -PtarjimPrebuilt assembleDebug` in `android/`.

On a machine with an Arabic number locale, code generation writes Arabic
digits into Kotlin sources and fails; `gradle.properties` forces
`-Duser.language=en`. The development laptop has 8 GB of memory and a local
Gradle build can exhaust it; building on GitHub avoids that.

## 5. What was measured

On the Galaxy S20 FE, from first sight of a line to the picture being sent
to the TV, read from the phone's log:

| | Screen on | Screen off |
| --- | --- | --- |
| Whole line | about 105 ms (80 to 135) | about 1500 ms |
| Text recognition | about 40 ms | 60 to 200 ms |
| Translation | 20 to 35 ms (first of a session 150 to 230) | 75 to 140 ms |
| Drawing and PNG | 3 to 10 ms, 4 to 20 KB | 20 to 40 ms |

With the screen off Android throttles the app: lines arrive 1.5 s late and
after a while the session drops. That is why the app keeps the screen on
while it runs and offers a black screen at minimum brightness.

Not measured: the delay of Remote Play itself, the TV's drawing time from
the phone, heat and battery use over a long session.

## 6. Problems met, and their fixes

- **First GitHub build failed**: `protoc` missing on the runner. Installed in the workflow.
- **Crash at start**: the layout named two classes relative to the application ID, which had changed. Full class names now.
- **Linking failed with "Invalid argument"**: broadcast search was on by default and does not work when the phone itself is the hotspot for the other devices. Off by default now, and a typed address is used.
- **"Failed to parse host address"**: a registered console that discovery did not find has no address. The address is asked for in Setup.
- **Crash when the picture arrived**: `Image.getPlanes()` aborted (`non-zero capacity for nullptr pointer`) on the Qualcomm decoder's buffers. Replaced by `PixelCopy` from the reader's surface.
- **Slow and missing lines**: translation blocked the reading thread, reading was paced at 110 ms, short lines were dropped, and only one block of a two-line subtitle was taken. All four changed; the timings in section 5 are from after.
- **"Dutch" as a nationality, late lines with the screen off, a "connected" label after the picture had stopped, a primitive first screen**: reported by the user after the first working version; answered by the names code, keep-awake and the black screen, the frame watchdog, and the laptop's interface.

## 7. Known limits

- **Translation quality** is that of ML Kit's small on-device model, weaker than the laptop's model. Names are handled; meanings are not improved. The user can rewrite any line.
- **The screen must stay on** (the black screen is the answer).
- **The English is covered by a plate**, not erased stroke by stroke as on the laptop.
- **The TV app is installed from the laptop program.**
- **One console and arm64 only.** The first registered console is used.
- **The phone was the Wi-Fi hotspot** for the PS5 and TV in every test; an ordinary router was not tried.
- **Text selection**: anything centred in the reading area is treated as dialogue, so a centred prompt can be translated too.
- **No automated tests** for the Tarjim code.

## 8. Not built

| Item | Notes |
| --- | --- |
| A stronger translator on the phone | The laptop's model would need CTranslate2 built for Android |
| Installing the TV app from the phone | Needs adb-over-network from the phone |

## 9. Xbox (built, not tested)

Chosen in Setup (PlayStation 5 or Xbox) with the linking steps explained
there. Code: `XboxAuth.kt`, the Xbox branches in `TarjimEngine.kt`
(`feedJpeg`, `xboxState`), `PhoneServer.kt` (routes `/api/xbox*`,
`/api/console`, the `/v5`, `/v6` proxy), `assets/web/xbox.js`. On the phone
the page sends only the reading area of each frame.

Xbox home streaming ("Remote Play") is WebRTC, a different protocol from
Sony's. Both programs show their interface in a browser view (Edge WebView2 on
the laptop, Android WebView on the phone), and a browser speaks WebRTC
natively. So the page itself receives the stream, with the open-source
`xbox-xcloud-player` library (MIT, bundled as `xCloudPlayer.min.js`; glue in
`xbox.js`), draws frames to a canvas and posts them as JPEG to the local
server, which feeds the normal reading and translating code. The library's API
calls (`/v5/...`, `/v6/...`) go to the local server, which forwards them to
Xbox with the token added.

Sign-in is Microsoft's device-code flow, ported from `xal-node` 1.1.5
(`dist/msal.js`): the user types a short code at microsoft.com/link; then
Xbox Live user token, XSTS token for `http://gssv.xboxlive.com/`, and the
home-streaming token (`xhome.gssv-play-prod.xboxlive.com/v2/login/user`) with
its region host. Only the refresh token is stored, as a secret.

No controller input is sent: the input channel is opened because the console
requires it and nothing is ever put into it; the stream's media elements are
muted.

**What was verified:** Microsoft answers the device-code request made by this
code with a valid code. The token chain, the frame hand-off and the error
texts are covered by tests with stand-in servers.
**What was not:** everything after the sign-in. No Microsoft account was
signed in and no Xbox was available: listing consoles, starting a session,
the WebRTC connection, picture quality through JPEG, whether the player's own
controller and TV picture stay untouched, and behaviour when the window is
minimised have never been observed. Treat it as written, not as working.

The Sony sign-in for the PS5 Account ID (`PsnLoginActivity`) is in the same
state: written from the laptop library's request shapes, never run.

## 10. Rules for whoever changes this

- Never send controller input and never store a PSN password. The sign-in
  happens on Sony's page; only the redirect's one-time code is used.
- Nothing may leave stale Arabic on the TV: keep the keep-alive, the clear
  on drop and the TV's own one-second watchdog.
- Say only what was run. Device behaviour that was not observed goes in
  section 2 as not verified.
- The code is under AGPL-3.0 (from chiaki-ng). Personal, non-distributed project.
- Talk to the user in simple Saudi Arabic; code and commits in English.
- The page (`assets/web`) is a copy of the laptop program's: change it there
  and copy it with its `launcher/sync_phone_web.py` (sections 12 and 13).
- The translation rules exist twice: here (`TarjimEngine.kt`, `Gender.kt`, `Terms.kt`) and
  in the laptop program (`translate.py`, `gender.py`, `terms.py`). Change both and keep
  `GenderTest.kt` / `test_gender.py` and `TermsTest.kt` / `test_terms.py` alike (sections
  14 and 15).

## 11. Game files, the game library, and names (added 2026-11-17)

The laptop program's report (section 17) explains the format and the rules;
the phone follows the same ones, so a file made on one opens on the other.

| What | Where |
| --- | --- |
| Format, checks, merge, export, library | `Packs.kt` (same rules as the laptop's `tarjim/packs.py`) |
| The library (28 games, names only) | `assets/packs/*.json`, generated by the laptop repository's `launcher/build_packs.py`: do not edit here |
| Routes | `GET /api/packs`, `GET /api/pack/export`, `POST /api/packs/add`, `POST /api/pack/import` in `PhoneServer.kt` (requests up to 8 MB) |
| Picking a file for «استورد ملف» | `TarjimActivity`: `WebChromeClient.onShowFileChooser` opens Android's file picker |
| «صدّر ملف» | `Bridge.shareFile`: the file goes to the cache (`packs/`, in `filepaths.xml`) and Android's share sheet (WhatsApp, Files, Drive) |
| "Open with ترجِم" / "Share to ترجِم" | Intent filters for `application/json`; the page asks which game the file goes into |

A first install now gets Red Dead Redemption 2 from the library (157 names)
instead of the old built-in 28. An existing install keeps its own list; the
library shows "حدّث" with the number of names it would add, and the old
spellings «سيدي» and «دتش» are updated if the user never changed them.

**Names, as on the laptop:** a name written with a capital is matched only
with that capital or all in capitals ("Chop" is not found in "chop the wood");
a name in small letters is found in any case; straight and curly apostrophes
count as the same; the patterns are compiled once per list (`Look.names`).

**A fix of an older bug:** each `Store` kept its own copy of the user's line
fixes, and the running session has its own `Store`, so a line rewritten during
play was not used until the next session. The copy is now shared by the whole
app. Line keys now also turn curly quotes into straight ones, like the
laptop's, so fixes made on either device match; fixes saved before are read
back in that form.

**Verified:** the Kotlin side compiles and the whole app builds locally
(`gradlew -PtarjimPrebuilt assembleDebug`, with the 28 library files inside
the APK). The page's library, import and search code is the laptop's, which
was clicked through in Chrome.
**Not verified:** nothing of this has run on a phone yet (the phone was not
connected): the file picker, the share sheet, "Open with", the library and the
import on Android are untested.

## 12. A new look: desert night and liquid glass (added 2026-11-17)

*Replaced the same day by section 13; kept here for the record.*

The page was redone from scratch, together with the laptop program's (its
report, section 18, has the full description and how the liquid glass works).
What matters on the phone:

- **The same page, copied.** `assets/web` is now a byte-for-byte copy of the
  laptop repository's `laptop/tarjim/app/web`, made by its
  `launcher/sync_phone_web.py`. The phone-only parts (the «الإعداد» tab, «عتّم
  الشاشة», the resting screen) live in that one page and show only inside this
  app. Do not edit `assets/web` here: change the laptop's page and copy it.
- **New file `assets/web/glass.js`**: stars, liquid glass, the dock's lens,
  the start button's shapes. The page loads it before `app.js`.
- **On a phone** the dock is a glass capsule at the bottom, sheets rise from
  the bottom with a grabber, and the content panels are opaque (no blur) to
  save power. While a translation runs, the glass falls back to plain blur and
  the sky and the start button stand still, so the phone's power goes to the
  picture, the reading and the translation. The resting screen is unchanged
  (pure black).
- **The middle device on the home screen is «الجوال»** (it said «اللابتوب»
  inside this app) with a phone icon.
- **Native colours follow the page:** status bar `#04050F` (the top of the
  sky), navigation bar `#07050F` (the dunes), the WebView's and the window's
  background `#070A1C`, the Sony sign-in screen too. The launcher icon is new
  (made by the laptop repository's `launcher/make_icon.py`, which now writes
  this app's `mipmap-*` and `assets/web/icon.png`).

**Verified:** the app builds with the new page (`gradlew -PtarjimPrebuilt
assembleDebug`; `glass.js` and the new icon are inside the APK). The page was
clicked through in Chrome with the phone mode switched on (the three tabs,
«الإعداد») and photographed at phone width.
**Not verified:** nothing ran on the phone (it was not connected). The
refraction needs the WebView's Chromium SVG backdrop filters; the S20 FE's
WebView should have them, but it is unseen. Messages above an open sheet need
popovers (WebView 114 or newer); an older WebView shows them as before.

## 13. Two layouts over the games' own pictures (added 2026-11-17)

Section 12's look was rejected by the user the same day and redone, again in
the laptop repository's page (its report, section 19, has the full description
and the measurements). What matters on the phone:

- **The page is still a copy** of the laptop's (`launcher/sync_phone_web.py`
  there). It now loads `art.js` and `look.js` (`glass.js` is gone) and carries
  `assets/web/art/`: 111 WebP pictures of the 28 library games (6.8 MB; the APK
  is about 60 MB). On a phone the background uses each game's tall picture,
  cut around its subject.
- **Two layouts**, «سينما» and «رف الألعاب», chosen in the menu at the top
  left of the page, remembered in the WebView's storage. On a phone the
  sections are a glass tab bar at the bottom (الرئيسية، المظهر، الأسماء،
  الإعداد), and the home page is one screen of picture with the game and the
  start button; the latest lines and the devices are below it.
- **The live picture behind the page**: while translating, the page asks
  `/api/frame` twice a second (the laptop four times), only on the home page,
  never while the resting screen is on or the app is hidden. `frameJpeg()`
  makes it with a PixelCopy of the decoder's surface; this path has never run
  on the phone. If it fails, the page keeps the game's own picture.
- **`PhoneServer.kt`** serves `.webp` as `image/webp` and lets the WebView keep
  the games' pictures (like the fonts).
- **Native colours**: status bar, navigation bar, the WebView's and the Sony
  sign-in screen's background are the page's near-black `#0B0B0D`. The look
  page's preview scene is a plain slate instead of the dusk. New launcher icon
  (black teh on white).

**Verified:** the app builds (`gradlew -PtarjimPrebuilt assembleDebug`) with
the page, `look.js`, `art.js` and all 111 pictures inside, and without
`glass.js`. The page was clicked through in Chrome at phone width.
**Not verified:** nothing ran on the phone (not connected): the look in the
S20 FE's WebView, the live picture there, and the cost of both on the phone.

## 14. Arabic wording and the translation rules (added 2026-11-18)

Done with the laptop program (its report, section 20, has the measurements). Here:

- **Wording**: one name per feature in the shared page; this app's own messages (errors,
  the PS5 link screen, settings, `res/values/strings.xml`) in plain Arabic.
- **Translation rules** in `TarjimEngine.kt`: more than 250 idioms, phrasal verbs and set
  phrases are put into plain English before ML Kit translates; the Arabic clean-up of the
  laptop program.
- **Commands in the right gender** (`Gender.kt`, new): the laptop's `gender.py` in Kotlin,
  the same lists. A man's name or nobody named: masculine commands (this app used to fix
  them only when no name at all was in the line); a woman's name: feminine.
- **Tests**: `app/src/test/.../GenderTest.kt` holds the same cases as the laptop's
  `test_gender.py` and passes (`gradlew -PtarjimPrebuilt :app:testDebugUnitTest --tests
  "com.metallic.chiaki.tarjim.GenderTest"`).

**Verified:** the app builds; the gender unit test passes on the computer.
**Not verified:** anything on the phone. ML Kit's mistakes differ from the laptop model's,
so how much these rules help here was not measured.

## 15. Game words and more places (added 2026-11-18)

Done with the laptop program (its report, section 21, has the measurements). Here:

- **Game words** (`Terms.kt`, new; the laptop's `terms.py` in Kotlin, the same lists): words
  a game uses in its own sense go into plain English before ML Kit translates ("the fence",
  "case the bank", "lose the law", "hogtie", "safehouse", "wanted level", "we've got
  company"...), plus each series' own words (Red Dead, GTA, The Last of Us, Yakuza). The
  series comes from the game's library id or its title (`TarjimEngine.series`).
- **Commands, not verbal nouns**: «شراء بعض منشط» for "Buy some tonic" becomes «اشتر بعض
  منشط», when the English line opens with that verb.
- **85 more places and names** in the game library (`assets/packs/`, written by the laptop's
  `launcher/build_packs.py`).
- **22 game commands** in `Gender.kt`.
- **Tests**: `app/src/test/.../TermsTest.kt` (the cases of the laptop's `test_terms.py`) and
  `GenderTest.kt` pass on the computer.

**Verified:** the app builds; the unit tests pass. **Not verified:** anything on the phone.
ML Kit's mistakes differ from the laptop model's, so how much these rules help here was not
measured.

## 16. Words for every game of the library, more GTA VI, and fixes (added 2026-11-18)

Done with the laptop program (its report, section 22, has the details and the measurements).
Here: `Terms.kt` has the same 13 series and rules (western, gangster, street and old English,
soldiers' calls, idioms, and each series' own words), keeps a capital when it replaces
words, and fixes «إبقى» and the lost «النار»; `Gender.kt` makes «إدفعي» masculine and treats
«اعتني بنفسك» as a command anywhere; `TarjimEngine.kt` writes «يا عم» for "Uncle, ..."; the
game library has GTA VI's new official names (59, was 35) and the other games' new words.
(This app gives every name a stand-in already, so the "Majima" fix of the laptop program was
not needed here.)

**Verified:** the app builds; `TermsTest` and `GenderTest` pass on the computer.
**Not verified:** anything on the phone.

## 17. The README (added 2026-11-18)

Rewritten in everyday Arabic: a banner (a phone going through the app's screens), badges,
links to the sections, the features beside a phone GIF, the library's covers, collapsible
setup and questions, and a section on how the app was built with the help of AI (Claude
through Claude Code; Gemini for one round of Arabic wording). The pictures in `docs/media/`
are made by the laptop program's `launcher/make_readme_media.py` from the shared page at phone
size (not from the phone itself); the laptop remote's "second screen" is left out, since this
app has none. The chiaki-ng attribution stays in the developer part.

## 18. Version 0.4.1, installed on the phone (added 2026-11-18)

`versionCode 13`, `versionName "0.4.1"` (was 12, "0.1.0", which was never raised). The build
of GitHub Actions for commit aab2dc1 was installed on the Galaxy S20 FE with `adb install -r`
over the version of 2026-11-16.

**Verified:** the phone reports versionCode 13 / 0.4.1; the app starts (its process runs, no
crash in the log).
**Not verified:** the new page on the phone's screen (the phone was locked when the app was
opened), and a translation session.

## 19. The hotspot's new network, a page that fits, version 0.4.2 (added 2026-11-18)

- **The error the user saw:** "Session request connect failed: Timeout" every 12 s. The app
  had saved the PS5 as 192.168.172.109 and the TV as 192.168.172.251, but the phone's hotspot
  now runs 192.168.136.x: Android's hotspot moves to a new network now and then, and each
  device keeps its last number (192.168.136.109, 192.168.136.251).
- **Fix (`NetFix.kt`, used when a translation starts):** an address that is not on any of the
  phone's home networks is looked for on the current one. The same last number is tried
  first, then every address of a home-sized network:
  - the PS5 by Sony's discovery on UDP 9302, which also answers in rest mode;
  - the TV app by its port, 47800.

  The found address is saved. When the PS5 is not found, the page says so instead of retrying
  silently.
- **The page fits a 360-point screen** (the laptop program's section 25).
- **Version 0.4.2** (versionCode 14), built by GitHub Actions for commit 15bf03a and installed
  on the Galaxy S20 FE with `adb install -r`.

**Verified:** the app compiles and installs; the phone reports 0.4.2.
**Not verified:** a translation session that finds the PS5 on the new network; the PS5 was
not tried (the phone was locked when the app was opened).

## 20. The official build, and the TV app in the release (added 2026-11-18)

- **A release build.** GitHub Actions now builds the release variant (`assembleRelease`),
  signed with the same fixed key, so it installs over the earlier builds.
  - It is not debuggable: the earlier test build let anyone with a cable read the app's data,
    the PS5 keys included.
  - It is not shrunk: R8 could drop classes reached by reflection (Room, the page's
    JavaScript bridge, ML Kit).
  - Lint's checks are not run on the release build.
- **`Tarjim-TV.apk` in the release**, from `tv/` (the laptop program's TV app, 0.4.2,
  versionCode 5), so a phone used without the laptop can get it. Its screen has a
  «الإعدادات» button to the TV's accessibility settings, where the second window is switched
  on without adb.
- `Terms.kt`: «وابقى» → «وابق» too.
- versionCode 15.

**Not verified:** the release build on the phone (to be installed when it is built).
