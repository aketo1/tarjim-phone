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
