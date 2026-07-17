# AI Packing Monitor

Android vision app that detects items left behind on packing tables, carts, or baskets and alerts staff before an order is dispatched.

The app is built for an on-device pilot workflow. It uses the phone rear camera, captures empty references for configured zones, waits until packing activity stops, compares the stable camera view against the reference, and alerts only when a possible added leftover object remains.

## Current Features

- Native Android app written in Kotlin with Jetpack Compose.
- CameraX preview, image analysis, and audit video recording.
- OpenCV first-layer computer vision for frame differencing, thresholding, morphology cleanup, and changed-region detection.
- Optional TensorFlow Lite second checker for local AI verification when a bundled model is provided.
- Empty reference capture per monitored zone.
- Croppable packing table and cart areas with saved normalized bounds.
- Main table and cart monitoring, plus up to 4 additional table/cart zones.
- Zone labels on the camera feed, such as `Table 1`, `Cart 1`, `Table 2`, and `Cart 2`.
- Motion-aware packing workflow: alerts are suppressed while staff are packing.
- Adjustable post-packing quiet time from 3 to 60 seconds.
- Alert loop that continues until the leftover is removed or movement starts again.
- Red suspected-region overlay around the changed area during leftover alerts.
- Alert reason logging with occupancy, motion, region size, added/removed scores, verifier decision, and confidence.
- Local Room history for alert events, pilot feedback, and audit video records.
- Optional audit videos saved privately on device for 48 hours, with an in-app `Watch` action.
- DataStore-backed settings for thresholds, alert delay, alarm volume, vibration, audit video, and zone bounds.
- Keep-screen-awake behavior while the app is active.
- Local-only privacy posture: no backend, account, analytics, upload, cloud AI, microphone, contacts, or location access.

## Tech Stack

- Kotlin
- Android SDK
- Jetpack Compose
- CameraX
- OpenCV
- TensorFlow Lite
- Room
- DataStore
- Hilt
- Gradle Kotlin DSL

## Vision Logic

The detector keeps an empty luma reference for each configured zone. During monitoring, each camera frame is sampled inside the zone bounds.

OpenCV is the first layer. It compares the current frame against the saved reference, builds a cleaned change mask, finds the largest changed region, and classifies the change as an added object, removed reference object, lighting change, mixed change, or no meaningful change.

TensorFlow Lite is the second checker. It is wired to load this local asset when available:

```text
app/src/main/assets/leftover_verifier.tflite
```

If no model is bundled, the app continues with the OpenCV/rule-based verifier. If a model is bundled, it can confirm or veto an added-object alert locally on the phone.

## Packing Workflow

1. Mount the phone with a stable rear-camera view of the packing table and cart area.
2. Grant camera permission.
3. Set the table and cart areas so the app monitors only the practical packing zones.
4. Clear the selected zone and capture an empty reference.
5. Start monitoring.
6. Staff brings picked items and bags into the zone. The app enters packing activity and suppresses alerts.
7. When movement stops, the app waits for the configured delay, from 3 to 60 seconds.
8. The stable zone is compared against the empty reference.
9. If the view matches the reference, the app resets for the next packing task.
10. If a possible added leftover object remains, the app alerts and highlights the suspected area.
11. If movement starts again, the alert stops and the app treats it as renewed packing activity before scanning again.

## Audit Video

Audit video can be enabled in settings. When enabled, recording starts during an active packing session and stops when the table/cart returns to a clear state. Videos are stored in app-private storage, listed in the app, playable through the `Watch` button, and deleted automatically after 48 hours.

## Build

### Requirements

- A Windows, macOS, or Linux computer that can run [Android Studio](https://developer.android.com/studio).
- [Android Studio](https://developer.android.com/studio) installed. This is the easiest way to get the Android SDK, SDK Manager, emulator tools, and the bundled JetBrains Runtime.
- Android SDK Platform 36 installed through Android Studio SDK Manager. The project currently uses `compileSdk = 36`.
- Android SDK Platform-Tools and a compatible Android SDK Build-Tools package. These can be installed from Android Studio SDK Manager or with the [sdkmanager command-line tool](https://developer.android.com/tools/sdkmanager).
- JDK 17. The bundled Android Studio JBR works, so a separate Java install is not required if you point `JAVA_HOME` to it.
- A real Android phone is recommended for testing because the app depends on the rear camera. The app supports Android 8.0/API 26 and newer.
- USB debugging or wireless debugging enabled if you want to install directly from Android Studio or ADB. See Android's guide to [run apps on a hardware device](https://developer.android.com/studio/run/device.html).

No separate Gradle installation is needed because this repo includes the Gradle wrapper.

If `java` is not on PATH, run Gradle with:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

The app is split around three responsibilities:

```text
Seeing: CameraX frame analysis, OpenCV change detection, optional TensorFlow Lite verification.
Deciding: Domain state machine, movement gating, timing, thresholds, and clear/leftover decisions.
Presenting: Compose UI, camera overlays, alert loop, settings, local history, and audit videos.
```

The state machine is intentionally separate from the detector so the vision engine can improve without changing the packing workflow.
