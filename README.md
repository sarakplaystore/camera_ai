# Camera AI — people-triggered CCTV

An Android app that watches the camera, tracks people on-device, and starts
recording **automatically when someone joins the frame**. Every clip is
guaranteed to be at least **10 minutes** long (customisable), and keeps rolling
while people are still in view.

Built with CameraX + MediaPipe BlazePose. No cloud, no accounts, no network
needed at runtime.

## Download

Grab the latest `camera-ai.apk` from the [Releases](../../releases) page, then
open it on the phone and allow "install unknown apps" when Android asks.

## What it does

| | |
|---|---|
| **Tracks people** | BlazePose finds up to 5 people per frame, with skeletons + boxes drawn over the preview. |
| **Triggers itself** | Default policy: start recording when a **second person** enters frame. Other modes: any new person, or manual only. |
| **Minimum 10 minutes** | Once a clip starts it never stops before the configured minimum (10 min by default) — even if everyone walks away. |
| **Keeps rolling** | After the minimum is met, the clip continues while people are in frame, stops after a grace period (30 s) once the frame is empty, and is capped at the max length (60 min by default). |
| **Works in the background** | Runs as a foreground service, so the screen can be off or another app can be open. |
| **Long, safe takes** | Each take is spliced into 4-minute segments so a multi-hour run can never corrupt one file; the gallery groups them by take. |
| **Saved to the gallery** | Clips go to `Movies/CameraAI` and are visible to other apps. |

## Settings

Open the gear icon in the top right:

* **Start recording** — second person / any new person / manual
* **Minimum clip length** — hours + minutes sliders (default 10 min, max 2 h)
* **Keep rolling while people stay** — on by default
* **Stop delay after the last person leaves** — default 30 s
* **Maximum clip length** — default 60 min, 0 = unlimited
* **Detection sensitivity**, **people at once**, **analysis fps**, **lite/full model**
* **Record audio**, **skeleton overlay**, **boxes**, **start watching automatically**

## Build it yourself

The APK is built by GitHub Actions (`.github/workflows/build-apk.yml`): it
fetches the MediaPipe pose models, generates a signing key, and publishes
`camera-ai.apk` to a GitHub Release.

Locally you need JDK 17 and the Android SDK (compileSdk 35):

```bash
mkdir -p app/src/main/assets
curl -L -o app/src/main/assets/pose_landmarker_lite.task \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
./gradlew :app:assembleRelease
```

If the model is missing from `assets/`, the app downloads it once on first run.

## Privacy

Everything happens on the phone. The only network use is the optional one-time
model download. Clips stay in local storage.

## Requirements

* Android 7.0 (API 24) or newer
* A camera; hardware acceleration recommended
