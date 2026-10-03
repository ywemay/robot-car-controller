# Robot Car Controller

Android app that turns an OTG-capable phone into the onboard computer of a
USB-serial robot car (Arduino Nano + H-bridge + 2-axis camera gimbal).

Package `com.ywemay.robotcar` · Kotlin + Jetpack Compose · minSdk 24 (Android 7.0)

---

## 1. What it does

| Requirement | Where it lives |
|---|---|
| First screen: a "car face" that blinks when idle | `ui/FaceScreen.kt` |
| Nine face emotions (neutral, happy, love, excited, surprised, sad, angry, sleepy, wink) | `control/Emotion.kt` + `ui/FaceScreen.kt` |
| Mood control from the browser *and* from the dashboard | `GET /emotion?e=<slug>` + `MoodStrip` |
| Double-tap the face → control dashboard | `MainActivity.RobotCarApp()` |
| Embedded web server + remote-control page | `web/WebControlServer.kt`, `web/RobotWebServer.kt`, `web/ControlPage.kt` |
| One command hub shared by the on-device UI *and* the web UI | `control/CarControl.kt` |
| USB serial to a Nano (CH340/FTDI/CP210x/PL2303/CDC-ACM) | `usb/UsbSerialManager.kt` + `com.github.mik3y:usb-serial-for-android:3.8.1` |
| Auto-connect the instant the OTG cable is plugged in | manifest `USB_DEVICE_ATTACHED` intent-filter + runtime `BroadcastReceiver` |
| 115200 / 8 / 1 / none, enforced | `UsbSerialManager.openPort()` → `setParameters(115200, 8, STOPBITS_1, PARITY_NONE)` |
| `D,DIR,SPEED\n` drive frames | `usb/CommandEngine.drive()` |
| `C,PAN,TILT\n` camera frames | `usb/CommandEngine.camera()` |
| Green/red USB status banner | `ui/RobotCarScreen.kt` → `UsbStatusBanner` |
| 5 movement buttons @ speed 180 | `DrivePad` (Forward / Backward / Left / Right / Stop) |
| Two 0–180 sliders, default 90, live transmit | `CameraControls` / `AngleSlider` |
| Scrollable, real-time TX/RX debug log | `DebugLogPanel` + `LogLine` |

---

## 2. Wire protocol

Every frame is ASCII and ends with **one `\n`** (0x0A) — never CRLF.

```
D,<DIR>,<SPEED>\n        DIR = F | B | L | R | S      SPEED = 0..255
C,<PAN>,<TILT>\n         PAN, TILT = 0..180 degrees
```

Produced by `CommandEngine`, so the format is defined in exactly one place:

| UI action | Frame |
|---|---|
| Forward button | `D,F,180\n` |
| Backward button | `D,B,180\n` |
| Left button | `D,L,180\n` |
| Right button | `D,R,180\n` |
| Stop button | `D,S,0\n` |
| Pan slider → 45 (tilt still 90) | `C,45,90\n` |
| Tilt slider → 120 (pan still 45) | `C,45,120\n` |

The two servos move as a pair, so each slider event re-sends *both* current
angles. Angles are clamped 0–180 and speed 0–255 inside `CommandEngine`, so no
UI value can ever emit a frame the sketch would reject.

Since `CommandEngine` is reached through the shared `CarControl` hub, the exact
same frames can be produced remotely: `GET /cmd?d=F&s=180` emits `D,F,180\n` and
`GET /camera?pan=45&tilt=90` emits `C,45,90\n` (see §9). The browser is not a
second implementation — it drives the identical code path.

---

## 3. Architecture

```
MainActivity (ComponentActivity)
 │  onCreate -> WebControlServer.start()       embedded HTTP server, process lifetime
 │  onStart  -> UsbSerialManager.start(this)   register hot-plug receiver + probe
 │  onStop   -> UsbSerialManager.stop()        unregister (port stays OPEN)
 │  onDestroy(isFinishing) -> WebControlServer.stop()
 │  setContent { CompositionLocalProvider(LocalLifecycleOwner provides this) { … } }
 │
 ├── RobotCarApp   two screens, Crossfade + BackHandler
 │     ├── ui/FaceScreen       FIRST SCREEN — drawn eyes/mouth, nine moods, idle blinks,
 │     │                       double-tap to open
 │     └── ui/RobotCarScreen   UsbStatusBanner · MoodStrip · DrivePad · CameraControls
 │                              · DebugLogPanel + a slim header with the web URL and back button
 │
 ├── RobotCarViewModel (AndroidViewModel) — thin bridge; owns no real state
 │
 ├── control/Emotion      nine moods as pure data (eye shape/openness, brow tilt+lift,
 │                        pupil, mouth archetype, blush/tear/wink, ARGB tint) + slug lookup
 │
 ├── control/CarControl   ← THE command hub, used by BOTH front-ends
 │     pan / tilt / emotion StateFlows · drive() · setPan() · setTilt() · setCamera()
 │     · setEmotion() · stop()
 │        └── CommandEngine.drive()/camera() -> UsbSerialManager.send()
 │            (setEmotion writes nothing — an emotion is presentation, not a frame)
 │
 ├── web/WebControlServer  (object) — owns the NanoHTTPD instance + the LAN URL
 │     └── web/RobotWebServer   routes / , /status , /cmd , /camera , /stop ,
 │     │                        /emotions , /emotion
 │     └── ControlPage.HTML   self-contained remote-control page (no CDN);
 │                            its mood grid is built from /emotions at load
 │
 └── usb/UsbSerialManager   ← the single owner of the cable (object singleton)
       ├── BroadcastReceiver   ATTACHED / DETACHED / USB_PERMISSION
       ├── connect()           prober -> hasPermission? -> requestPermission | openPort()
       ├── openPort()          open + setParameters(115200, 8, 1, NONE) + dtr/rts (best effort)
       ├── read loop           Dispatchers.IO, line-buffered on '\n'  -> LogKind.RX
       └── send(frame)         writeMutex-guarded Dispatchers.IO write -> LogKind.TX
```

Design decisions worth knowing:

* **One command hub, two drivers.** The car now has two independent front-ends
  (the on-device dashboard and the Wi-Fi web page). Both funnel through
  `CarControl`, so pan/tilt pose, the current mood and the frame format live in
  exactly one place and the two controllers cannot drift apart.
  `RobotCarViewModel` is a bridge over it, not a second source of truth.

* **The manager is an `object`, not a per-Activity instance.** There is one cable
  and one chip on it; the link must survive Activity recreation (permission
  dialog round trips, launcher returns). This mirrors the `CameraSession`
  singleton pattern used elsewhere in these Android projects.
* **Never block the UI thread.** `send()` returns immediately and does the
  blocking `write()` on `Dispatchers.IO` behind a `Mutex`, so a slider drag and
  a button tap landing in the same millisecond can never interleave two frames
  on the wire.
* **`stop()` deliberately does NOT close the port.** It only detaches the
  receiver; otherwise the USB permission dialog (which pauses the Activity)
  would tear down a perfectly good link.
* **Two independent attach paths.** The manifest intent-filter auto-launches the
  app on plug-in and gets it USB access for free for filtered gear; the runtime
  receiver handles the "already running" case and any device *not* in the
  filter (which falls back to the standard permission dialog).
* **The outer Compose `Column` is not scrollable on purpose.** The log panel
  takes `Modifier.weight(1f)` and scrolls internally; nesting a `LazyColumn` in
  a `verticalScroll` parent would give it infinite height and crash.

---

## 4. AndroidManifest notes

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="true" />
<uses-permission android:name="android.permission.INTERNET" />
```

There is **no `android.permission.USB_PERMISSION`** declaration — that string is
not a real platform permission. USB host access is gated by the `<uses-feature>`
above plus per-device grants the system hands out at runtime via
`UsbManager.requestPermission()`.

`INTERNET` is a normal permission and is what lets the embedded NanoHTTPD server
open a listening TCP socket. It is also what lets the app enumerate
`java.net.NetworkInterface` to find the Wi-Fi IPv4 address it prints as the
connect URL.

The auto-launch block on `MainActivity`:

```xml
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/device_filter" />
```

`res/xml/device_filter.xml` lists the specific VID/PID pairs (CH340, FTDI,
CP210x, PL2303, ATmega16U2 CDC) that get the auto-launch + auto-grant treatment.
Keep it tight — a broad entry hijacks the attach event of unrelated gear such as
USB modems. Unlisted devices still work; they just go through the permission
dialog.

`launchMode="singleTop"` means a plug-in while the app is open re-foregrounds it
rather than stacking a second copy.

---

## 5. Build

Gradle CLI is not on `PATH` on this machine, and the shell profile exports two
*conflicting* SDK paths — both must be forced to the same value:

```bash
cd ~/Projects/robot-car-controller
ANDROID_HOME=/home/dorian/Android/Sdk \
ANDROID_SDK_ROOT=/home/dorian/Android/Sdk \
./gradlew :app:assembleDebug
```

APK → `app/build/outputs/apk/debug/app-debug.apk` (~10.5 MB).

Toolchain (same proven matrix as the other `com.ywemay.*` Android repos):
AGP 8.13.0 · Kotlin 1.9.24 · Gradle 8.13 · compileSdk/targetSdk 34 · minSdk 24 ·
Compose compiler 1.5.14 · Compose BOM 2024.05.00.

`usb-serial-for-android` is **not on Maven Central** — it resolves from
`https://jitpack.io` (declared in `settings.gradle.kts`). Coordinate:
`com.github.mik3y:usb-serial-for-android:3.8.1`.

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ywemay.robotcar/.MainActivity
```

---

## 6. Hardware hookup

```
Phone ──(USB-C/micro OTG adapter)── Nano ──(USB)── same Nano (data + power)
```

* The phone **must** be the USB *host*, so you need a real OTG adapter (or a
  powered USB hub for a phone that cannot source enough current).
* An Arduino Nano draws its power from the same USB link. If the board browns
  out when the motors spin up, power the Nano from the car's BEC instead — keep
  the data lines only.
* Servos and motors should share a common ground with the Nano, and the motor
  supply should **not** come from the Nano's 5 V rail.

---

## 7. Companion firmware

`firmware/robot_car_nano/robot_car_nano.ino` is a working reference sketch:
parses both frame types, drives an L298N-class H-bridge with mixed
direction/PWM, moves the two servos, and ACKs each frame back up the link so the
app's debug log shows traffic in both directions:

```
» TX  D,F,180\n
« RX  ACK D F 180
```

Two things in it are worth copying even if you write your own sketch:

1. **Timer1 gotcha** — `Servo.h` on an ATmega328P owns Timer1, which disables
   `analogWrite()` on pins 9 and 10. The motor-enable pins are therefore on
   Timer0 (5, 6). Move them to 9/10 and PWM silently stops working.
2. **Failsafe** — if no drive frame arrives for 1 s the sketch cuts the motors.
   Essential for a moving vehicle on a flaky USB cable.

---

## 8. The face screen

The app opens on a **face**, not the dashboard: two eyes and a smile drawn with
Compose `Canvas` (no image assets — it scales to any screen). It exists so the
car has an "off" state that is not a black rectangle, and so the driver has to
make a deliberate gesture before any control is live.

* **Idle animation.** Blinks on a random 2.8–6.4 s timer, with an occasional
  double-blink, an occasional slow "sleepy" blink, a small wandering gaze, and a
  slow breathing scale. A single tap triggers a blink back (acknowledgement).
* **Double-tap anywhere → the control dashboard.** `detectTapGestures(onDoubleTap = …)`.
* **Status without opening controls.** A USB status pill sits at the top of the
  face and the web-control URL at the bottom, so link state and the address to
  type into a laptop are both visible from the "off" screen.
* **Current mood** is printed under the URL, tinted with that mood's colour.

### Emotions

The face can wear nine moods, and any of them can be set from the browser:

| Slug | Reads as | How it is drawn |
|---|---|---|
| `neutral` | resting / content | pill eyes, gentle smile |
| `happy` | happy | arc ( ∩ ) eyes, wide grin |
| `love` | affectionate | arc eyes, smile, pink blush |
| `excited` | excited | big round eyes with pupils, grin, blush |
| `surprised` | surprised | widest round eyes, raised brows, open "O" mouth |
| `sad` | sad | inner-ended-up brows, frown, a teardrop |
| `angry` | angry | narrowed eyes, inner-ended-down brows, flat mouth |
| `sleepy` | sleepy | eyes squashed almost shut, flat mouth |
| `wink` | playful | one eye shut, small smirk |

The design goal was that **a mood is data, not code**. `Emotion.kt` carries a
shape for each mood — eye shape (`CAPSULE` / `ROUND` / `ARC`), base openness,
brow tilt and lift, pupil size, mouth archetype, blush/tear/wink flags and an
ARGB tint — and `FaceIllustration()` just reads those fields. There is no
per-emotion `when` in the renderer, so adding a tenth mood means adding one row
to the enum and nothing else: the Compose face, the dashboard strip and the
web page's button grid all derive from the same list.

Two deliberate choices:

* **An emotion is never a serial frame.** It changes what the car *looks* like,
  not what it *does*, so nothing is written to the link. It still lives on
  `CarControl` alongside the gimbal pose, for the same reason the pose does: the
  face and the web page are two independent drivers of one value, and separate
  copies would drift.
* **The idle blink still runs on every mood.** The mood supplies the base
  openness; the blink signal multiplies it. So even a drawn expression blinks
  like a real face, and an arc eye (`∩`) flattens into a line on a blink.

Tuning note: the proportions (eye size/spacing, smile depth and height) were
checked by mirroring the same drawing maths in a PIL script and rendering it,
because there is no device in the loop; the constants in `FaceIllustration()`
are the single source of truth. The same script renders all nine moods to a
grid (`/tmp/robotcar/webtest/moods.png` while it existed) — worth re-running
after any geometry change.

---

## 9. Web control interface (remote)

The app runs an embedded **NanoHTTPD** server on port **8080**. Open
`http://<phone-ip>:8080` from any browser on the same Wi-Fi — no app, no install.

| Route | Does |
|---|---|
| `GET /` | the control page (mood grid, D-pad, gimbal sliders, live status, activity log) |
| `GET /status` | JSON: USB state, device, pan, tilt, last frame, **emotion**, advertised address |
| `GET /cmd?d=F&s=180` | drive frame — `d` ∈ F/B/L/R/S, `s` optional 0–255 |
| `GET /drive?dir=R` | alias of `/cmd` |
| `GET /camera?pan=45&tilt=90` | gimbal frame — either argument may be omitted |
| `GET /stop` | explicit kill frame `D,S,0` |
| `GET /emotions` | JSON list of every mood + which one is current (drives the page's buttons) |
| `GET /emotion?e=happy` | change the car's expression — 400 with the valid slugs if unknown |

```bash
curl 'http://192.168.1.50:8080/cmd?d=F&s=180'      # -> {"ok":true,"sent":true,"frame":"D,F,180"}
curl 'http://192.168.1.50:8080/camera?pan=45'       # -> {"ok":true,...,"pan":45,"tilt":90,...}
curl 'http://192.168.1.50:8080/emotion?e=angry'     # -> {"ok":true,"emotion":"angry","label":"Angry"}
curl -s http://192.168.1.50:8080/emotions           # -> {"ok":true,"current":"angry","emotions":[...]}
curl -s http://192.168.1.50:8080/status
```

Behaviour worth knowing:

* **Hold to drive, release to stop.** The page uses pointer events, so pressing
  and holding a direction keeps moving and releasing sends `D,S,0`. Keyboard
  `W A S D` / arrows drive, `Space` stops.
* **Same hub as the app.** Every route calls `CarControl`, so a command from the
  browser lands in the same debug log and moves the same gimbal sliders as the
  on-device UI — and a mood set from the browser turns up on the phone's face
  immediately, then on every other open browser within one status poll.
* **The mood buttons are fetched, not hard-coded.** The page asks for
  `/emotions` and builds the grid from the reply, so the `Emotion` enum stays the
  single source of truth and adding a mood needs no page edit at all.
* **Offline-friendly page.** The HTML/CSS/JS is a single self-contained string
  (`ControlPage.HTML`) — no CDN — so it works on a car with no internet.
* **Server lifetime.** Started in `onCreate`, deliberately **not** stopped in
  `onStop`, so the link survives the phone's screen going dark. Stopped only when
  the Activity finishes. The address is recomputed per `/status` poll, so a DHCP
  change does not require an app restart.

> **Security:** there is no authentication. It assumes the robot's own trusted
> LAN, which is the same assumption the camera app makes. Anyone on that network
> can drive the car. Do not put this on an untrusted Wi-Fi, and if it ever needs
> to be, add a shared-secret header check in `RobotWebServer.serve()`.

---

## 10. Manual test plan

0. Launch with no cable → the **face** appears and blinks. Tap it once (a blink),
   then double-tap → the dashboard opens. Press Back → the face returns.
1. Launch the app with **no cable** → banner is **red**, "No USB link".
2. Press Forward → log shows `» TX  D,F,180\n` in blue, and
   `· SYS  TX blocked (no link)` in amber. Nothing crashes.
3. Plug the OTG cable in with the Arduino attached → banner turns **green**
   without any interaction; log shows `OPEN CH340 [1a86:7523] @ 115200 8N1`.
4. Press each of the 5 buttons → 5 TX lines; LEDs/motors respond.
5. Drag the Pan slider → a stream of `C,<pan>,90\n` frames. Drag Tilt → the
   pan value stays at wherever you left it.
6. Unplug the cable → banner returns to **red** instantly, `LINK LOST`.
7. Tap the banner to force a manual re-probe.
8. Open `http://<phone-ip>:8080` on a laptop → the remote page loads, the status
   dot is green and the sliders show the current angles. Press and hold ▲ on the
   page → the motors run; release → stop. Repeat with `W`/`A`/`S`/`D`. Now drag a
   slider on the *phone* and watch the browser's slider follow within ~1.2 s.
9. `curl -s http://<phone-ip>:8080/status` returns the JSON snapshot;
   `curl 'http://<phone-ip>:8080/cmd?d=F'` makes the same log entry as the button.
10. On the remote page, the **FACE MOOD** grid shows nine buttons with the
    current mood highlighted. Tap **Angry** → the phone's face changes to angry
    immediately (narrowed eyes, slanted brows) and the log gains `Emotion: Angry`.
    Open the page on a *second* device → within ~1.2 s its grid highlights Angry
    too, without touching it.
11. `curl 'http://<phone-ip>:8080/emotion?e=wink'` → `{"ok":true,...}` and the
    face winks. `curl 'http://<phone-ip>:8080/emotion?e=nope'` → HTTP 400 naming
    the valid slugs. `curl -s http://<phone-ip>:8080/emotions` lists all nine and
    reports `"current"`.
12. On the dashboard, swipe the mood strip and pick **Sleepy**; return to the
    face (`◀ FACE`) → the face is sleepy. The browser's grid follows within one
    poll.

---

## 11. Known limits

* The web server has **no authentication** — it trusts the LAN. See §9.
* Moods are **sticky**: the car stays in the mood it was given until something
  changes it. There is no auto-revert-to-neutral timer, so a `surprised` face set
  an hour ago is still surprised. A timed `setEmotion(e, holdMillis)` would be
  the obvious next step if that gets annoying.
* The web server lives for the life of the app **process**, not in a foreground
  service. It survives screen-off and backgrounding, but if Android kills the
  process (or the user swipes the app away) the link drops. Promoting
  `WebControlServer` to a foreground service is the fix if that ever matters.
* The face's blink cadence is a fixed random range; there is no "sleep after N
  minutes idle" state yet.
* One serial device at a time — the manager takes the first supported driver it
  finds. A multi-bridge rig would need device selection.
* The debug log is a 500-line ring buffer; older lines are dropped from the top.
* Rows in the log are not individually copyable (screen-read only).
* `FLAG_KEEP_SCREEN_ON` holds the display while the dashboard is visible. It is
  window-scoped and releases itself when the app leaves the foreground — no
  wake lock, no permission.
* No CI/release signing yet; `assembleDebug` is the signing-free path.
