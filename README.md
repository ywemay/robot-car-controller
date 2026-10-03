# Robot Car Controller

Android app that turns an OTG-capable phone into the onboard computer of a
USB-serial robot car (Arduino Nano + H-bridge + 2-axis camera gimbal).

Package `com.ywemay.robotcar` · Kotlin + Jetpack Compose · minSdk 24 (Android 7.0)

---

## 1. What it does

| Requirement | Where it lives |
|---|---|
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

---

## 3. Architecture

```
MainActivity (ComponentActivity)
 │  onStart -> UsbSerialManager.start(this)   register hot-plug receiver + probe
 │  onStop  -> UsbSerialManager.stop()        unregister (port stays OPEN)
 │  setContent { CompositionLocalProvider(LocalLifecycleOwner provides this) { … } }
 │
 ├── RobotCarViewModel (AndroidViewModel)
 │     connection / logs  <-- re-exposed StateFlows from the manager
 │     pan / tilt         <-- the only genuinely UI-local state
 │     onDrive(char)  onPanChanged(Int)  onTiltChanged(Int)  clearLog()
 │
 ├── ui/RobotCarScreen (Compose)
 │     UsbStatusBanner  ·  DrivePad  ·  CameraControls  ·  DebugLogPanel
 │
 └── usb/UsbSerialManager   ← the single owner of the cable (object singleton)
       ├── BroadcastReceiver   ATTACHED / DETACHED / USB_PERMISSION
       ├── connect()           prober -> hasPermission? -> requestPermission | openPort()
       ├── openPort()          open + setParameters(115200, 8, 1, NONE) + dtr/rts (best effort)
       ├── read loop           Dispatchers.IO, line-buffered on '\n'  -> LogKind.RX
       └── send(frame)         writeMutex-guarded Dispatchers.IO write -> LogKind.TX
```

Design decisions worth knowing:

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
```

There is **no `android.permission.USB_PERMISSION`** declaration — that string is
not a real platform permission. USB host access is gated by the `<uses-feature>`
above plus per-device grants the system hands out at runtime via
`UsbManager.requestPermission()`.

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

APK → `app/build/outputs/apk/debug/app-debug.apk` (~8.7 MB).

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

## 8. Manual test plan

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

---

## 9. Known limits

* One serial device at a time — the manager takes the first supported driver it
  finds. A multi-bridge rig would need device selection.
* The debug log is a 500-line ring buffer; older lines are dropped from the top.
* Rows in the log are not individually copyable (screen-read only).
* `FLAG_KEEP_SCREEN_ON` holds the display while the dashboard is visible. It is
  window-scoped and releases itself when the app leaves the foreground — no
  wake lock, no permission.
* No CI/release signing yet; `assembleDebug` is the signing-free path.
