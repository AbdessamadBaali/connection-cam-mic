# Connection Cam Mic

Use your Android phone as a **camera and microphone for your PC**, over Wi-Fi — with automatic detection.

Two apps:

| App | Folder | What it does |
|---|---|---|
| **Cam Mic Streamer** (Android APK) | `android/` | Streams the phone's camera (MJPEG video) and microphone (live WAV audio) from a small built-in web server, and broadcasts a beacon so PCs can find it automatically. |
| **Cam Mic Viewer** (Windows EXE) | `desktop/` | Electron + TypeScript desktop app: auto-detects phones on the network, shows the video, plays the audio, and offers snapshot, recording, camera flip, and more. |

## Desktop app features

- **Auto-detection** — phones running the app appear in the dropdown automatically (UDP beacon), plus a **Scan** button that sweeps the local network as a fallback.
- One-click **Connect / Disconnect** (manual IP entry still available).
- **Flip camera** — switch the phone between front and back camera remotely.
- **Snapshot** — save the current frame as a PNG.
- **Record** — record the stream (with audio) to a WebM video file.
- **Mirror / Rotate / Fullscreen** view controls.
- **Audio toggle + volume slider**.
- **Always-on-top** window mode.

## How to get the apps

Both apps are built automatically by GitHub Actions:

1. Open the **Actions** tab of this repository.
2. Open the latest **"Build Android APK"** run → download the **CamMicStreamer-Android-APK** artifact (contains `app-debug.apk`).
3. Open the latest **"Build Windows App"** run → download the **CamMicViewer-Windows-EXE** artifact (contains `CamMicViewer.exe`).

Install the APK on your phone (you may need to allow "install from unknown sources"). The Windows `.exe` is portable — just double-click it. Windows SmartScreen may warn because the app is unsigned; click **More info → Run anyway**. When Windows Firewall asks, **allow access** — that's needed for auto-detection.

## How to use

1. Connect your **phone and PC to the same Wi-Fi network**.
2. Open **Cam Mic Streamer** on the phone and allow camera + microphone permissions.
3. Open **CamMicViewer.exe** on the PC — within a few seconds your phone appears in the dropdown ("Phone detected").
4. Press **Connect**. Video and audio start immediately.

If auto-detection doesn't trigger (some routers block broadcasts), press **Scan**, or type the IP shown on the phone screen.

### Bonus: use it in other programs

The streams are standard, so they also work without the viewer:

- **Browser**: open `http://PHONE_IP:8080` to watch the video.
- **VLC**: open network stream `http://PHONE_IP:8080/video` (video) or `http://PHONE_IP:8080/audio` (audio).
- **OBS Studio**: add a *Browser* or *Media* source with `http://PHONE_IP:8080/video` to use the phone as a webcam source.

## Building locally (optional)

- **Android**: `cd android && gradle assembleDebug` (requires Android SDK + JDK 17). APK appears in `android/app/build/outputs/apk/debug/`.
- **Desktop**: `cd desktop && npm install && npm start` to run in development, or `npm run dist` to build the portable Windows `.exe` (appears in `desktop/release/`).

## How it works

- The Android app runs an HTTP server on port `8080`: `/video` (MJPEG), `/audio` (streaming WAV), `/info` (JSON identity), `/switch` (toggle front/back camera).
- It broadcasts a UDP beacon on port `8888` every 2 seconds; the desktop app listens for it and also can actively scan the subnet by probing `/info`.
