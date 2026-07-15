# AI Packing Monitor

Native Android pilot app for monitoring a packing table with the phone rear camera. The first release is local-only: it captures an empty reference frame, watches one configured zone, alerts on stable visual differences, and records pilot alert results on the device.

## What is implemented

- Kotlin Android app with Jetpack Compose UI.
- CameraX preview and `ImageAnalysis` using keep-latest backpressure.
- Deterministic monitoring state machine in a separate `domain` module.
- Empty-reference capture with app-private `reference.jpg` and `reference-luma.bin` persistence.
- Workflow-aware monitoring: packing activity suppresses alerts, a 3-second quiet period starts post-pack scanning, and only then can the app alert.
- Hysteresis, stopped-work confirmation, post-pack scan delay, and automatic clear reset.
- Packing-area crop setup with persisted normalized bounds.
- Adjustable post-pack scan wait from 3 to 60 seconds.
- Audible tone and vibration alert loop.
- Local Room event log with pilot feedback.
- DataStore-backed sensitivity, delay, volume, and vibration settings.
- Hilt dependency injection.
- Local-only privacy posture: no accounts, backend, analytics, video upload, microphone, contacts, or location permissions.

## Build

This machine has the Android SDK and Android Studio JBR installed. If `java` is not on PATH, run Gradle with:

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

## Pilot workflow

1. Mount the phone with a stable rear-camera view of the packing table.
2. Grant camera permission.
3. Tap `Set area`, adjust the blue crop rectangle so it covers only the packing area, then save.
4. Clear the cropped table area and tap `Capture empty`.
5. Start monitoring.
6. Staff brings items and a bag into the zone. The app enters `Packing active` and suppresses alerts.
7. When motion stops for 3 seconds, the app scans the stable table against the saved empty reference.
8. If the table matches the reference, the app resets for the next packing cycle.
9. If a stable difference remains, the app alerts for a possible leftover item.
10. Dismissed alerts can be marked as correct or false alarm for pilot metrics.

## Architecture

The important separation is:

```text
Seeing: CameraX frame analyzer and detector observations.
Deciding: Workflow state machine and clearance policy.
Presenting: Compose UI, local alerts, settings, and pilot metrics.
```

The current detector is intentionally simple and replaceable. It reports occupancy, motion, and largest changed-region size. A future OpenCV or TensorFlow Lite engine should implement the same `DetectionResult` contract and leave the workflow state machine unchanged.
