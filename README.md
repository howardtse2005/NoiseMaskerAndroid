# Noise Masker (Android)

A small Android app that helps mask distracting background noise (office chatter, traffic,
neighbours) by playing a soft, filtered noise bed through your speaker or headphones.

The app listens to the room through the microphone, measures how loud it is, and adjusts the
masking noise in real time: louder room -> louder noise, quiet room -> nearly silent. Ten sliders
let you tune how it reacts and how it sounds.

**This is masking, not noise cancelling.** It does not remove sound from the room and it does not
need ANC headphones. It simply adds a pleasant noise floor that makes other sounds less noticeable.
No audio is recorded or stored.

- Language: Kotlin
- Min Android: 8.0 (API 26), target/compile SDK 34

---

## Build the APK

### What you need

- Android Studio (recommended), or the Android SDK command-line tools
- JDK 17
- Android SDK Platform 34
- A device or emulator running Android 8.0+

### Option A — Android Studio

1. Open the project folder in Android Studio.
2. Let Gradle sync (it will download the Gradle 9.0 wrapper and dependencies).
3. Build the APK: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Click **locate** in the popup, or find it at:
   `app/build/outputs/apk/debug/app-debug.apk`

### Option B — Command line

From the project root:

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build (optional)

```bash
gradlew.bat assembleRelease
```

Without a signing config this produces an **unsigned** APK
(`app/build/outputs/apk/release/app-release-unsigned.apk`). To install it, either add a
`signingConfig` in `app/build.gradle.kts` or sign it manually with `apksigner`. For personal use,
the debug APK is enough.

### If the build cannot find the SDK

Create `local.properties` in the project root (Android Studio does this for you):

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

---

## Using the app

### First run

1. Open **Noise Masker**.
2. Tap **Start**. Android asks for microphone permission — allow it. (The mic is only used to
   measure loudness; nothing is recorded.)
3. Masking noise starts immediately. A silent, ongoing notification appears — that is the
   foreground service keeping audio alive while you use other apps.
4. Tap **Stop** to end it, or swipe the app away and stop it from the notification.
5. Slide the bars to adjust the controls. Read "The controls" sub-section for more details


The status line shows what is happening, for example `Running (source: UNPROCESSED)`. The input
readout shows peak / RMS / detection / normalised level / output volume, which is useful while
tuning.

> Use headphones or a Bluetooth speaker for the best result.

### The controls

Sliders take effect **immediately**, even while running.

| Control | What it does | Start here if... |
| --- | --- | --- |
| **Digital Preamp** | Gain applied to the measured level before reacting. Higher = more sensitive to quiet sounds. | Noise is quiet but the app barely responds -> raise it. |
| **Base Vol** | Volume of the noise floor when the room is silent. | You want a constant, gentle bed -> raise slightly. |
| **Max Vol** | Upper volume limit. | It gets too loud in busy rooms -> lower it. |
| **Attack Swell** | How fast the noise rises when sound appears. Higher = snappier. | It feels sluggish -> raise. It pumps on every word -> lower. |
| **Release Sink** | How fast the noise fades when the room goes quiet. Higher = fades faster. | It stays loud too long -> raise. It feels like it "breathes" -> lower. |
| **HP Cutoff (Hz)** | High-pass filter on the analysis signal. Removes rumble, traffic and handling noise from the detection. | Bass noise triggers it constantly -> raise to 120-200 Hz. |
| **RMS Weight** | Balance between peak and average level in the detection. | Sharp sounds (clicks, cutlery) trigger it too much -> raise. |
| **Log Detection Scale** | Shape of the response curve. Higher = reacts sooner at low levels and compresses more at high levels. | It only reacts to loud noise -> raise. |
| **Volume Scale** | How much extra volume is added at full detection, on top of Base Vol. | The effect is too subtle -> raise. |
| **Warm Cutoff Max (Hz)** | Brightness of the noise. Lower = darker, softer, more "rain-like"; higher = brighter, more hiss. | It sounds harsh -> lower to 600-900. |

### Quick recipe

1. Start with defaults, put on headphones, and sit in the noisy room.
2. Set **Warm Cutoff Max** until the noise sounds pleasant rather than hissy (try 800-1200).
3. Raise **Volume Scale** until the room noise is comfortably covered, then lower **Max Vol** if it
   ever gets too loud.
4. Adjust **Attack Swell** and **Release Sink** so the noise follows the room without pumping.
5. Leave it running in the background while you work.