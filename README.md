# Connection Cam Mic

Use your Android phone as a **wireless camera and microphone for your PC** — like DroidCam, over Wi-Fi, with automatic detection.

## 📥 Download

| | Direct download |
|---|---|
| 🤖 **Android app** (install on your phone) | **[Download CamMicStreamer.apk](https://github.com/AbdessamadBaali/connection-cam-mic/releases/latest/download/CamMicStreamer.apk)** |
| 🖥️ **Windows app** (run on your PC) | **[Download CamMicViewer.exe](https://github.com/AbdessamadBaali/connection-cam-mic/releases/latest/download/CamMicViewer.exe)** |

These links always point to the newest build (published automatically to [Releases](https://github.com/AbdessamadBaali/connection-cam-mic/releases/latest) on every update).

- **APK**: your phone may ask to allow "install from unknown sources" — allow it.
- **EXE**: portable, no installation. Windows SmartScreen may warn because the app is unsigned → click **More info → Run anyway**. When Windows Firewall asks, click **Allow** (needed for auto-detection).

## 🚀 Quick start

1. Put your **phone and PC on the same Wi-Fi**.
2. Open **Cam Mic Streamer** on the phone, allow camera + mic permissions.
3. Open **CamMicViewer.exe** on the PC — your phone is **detected and connected automatically** within a few seconds.

That's it. If your router blocks auto-detection, press **Scan**, or type the IP shown on the phone screen.

## ✨ Features

- **Auto-detect & auto-connect** — the phone announces itself on the network; the PC app connects by itself (can be turned off).
- **Flip camera** — switch front/back camera remotely from the PC.
- **Snapshot** (PNG) and **Record** (WebM video with audio).
- **Mirror / Rotate / Fullscreen** view, **volume slider**, **always-on-top** window.
- Modern dark UI, built with **TypeScript + Electron**.

## 🎥 Use it as a webcam in Zoom / Meet / Discord / OBS

The phone's stream is standard MJPEG, so any app that accepts a video source can use it:

- **OBS Studio** (recommended, free):
  1. Add source → **Browser**, URL: `http://PHONE_IP:8080/video` (the IP is shown on the phone and in the viewer's title bar area).
  2. Click **Start Virtual Camera** in OBS.
  3. In Zoom/Meet/Discord, pick **"OBS Virtual Camera"** as your camera. 🎉
- **VLC**: Media → Open Network Stream → `http://PHONE_IP:8080/video` (or `/audio` for the mic).
- **Browser**: just open `http://PHONE_IP:8080`.

For the microphone in calls, apps like [VB-Audio Cable](https://vb-audio.com/Cable/) can route the viewer's audio output into a virtual mic input.

> Note: DroidCam shows up directly as a camera in Zoom because it installs a signed Windows camera driver. This project keeps things driver-free; OBS's virtual camera provides the same result.

## 🔧 How it works

- The Android app runs an HTTP server on port `8080`: `/video` (MJPEG), `/audio` (streaming WAV, 44.1 kHz mono), `/info` (JSON identity), `/switch` (toggle camera).
- It broadcasts a UDP beacon on port `8888` every 2 seconds; the desktop app listens for beacons and can also actively scan the subnet.
- On every push, GitHub Actions builds the APK (Gradle) and the portable EXE (electron-builder) and refreshes the **latest** release.

## 🛠️ Building locally

- **Android**: `cd android && gradle assembleDebug` (Android SDK + JDK 17). APK: `android/app/build/outputs/apk/debug/`.
- **Desktop**: `cd desktop && npm install && npm start` for development, `npm run dist` to produce `desktop/release/CamMicViewer.exe`.
