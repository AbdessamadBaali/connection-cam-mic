# Connection Cam Mic

Use your Android phone as a **camera and microphone for your PC**, over Wi-Fi.

Two apps:

| App | Folder | What it does |
|---|---|---|
| **Cam Mic Streamer** (Android APK) | `android/` | Streams the phone's camera (MJPEG video) and microphone (live WAV audio) from a small built-in web server. |
| **Cam Mic Viewer** (Windows EXE) | `windows/` | Connects to the phone, shows the video and plays the audio on your PC. |

## How to get the apps

Both apps are built automatically by GitHub Actions:

1. Open the **Actions** tab of this repository.
2. Open the latest **"Build Android APK"** run → download the **CamMicStreamer-Android-APK** artifact (contains `app-debug.apk`).
3. Open the latest **"Build Windows App"** run → download the **CamMicViewer-Windows-EXE** artifact (contains `CamMicViewer.exe`).

Install the APK on your phone (you may need to allow "install from unknown sources"). The Windows `.exe` needs no installation — just double-click it. Windows SmartScreen may warn because the app is unsigned; click **More info → Run anyway**.

## How to use

1. Connect your **phone and PC to the same Wi-Fi network**.
2. Open **Cam Mic Streamer** on the phone and allow camera + microphone permissions.
3. The app shows an address like `http://192.168.1.23:8080`.
4. Open **CamMicViewer.exe** on the PC, type the phone's IP (e.g. `192.168.1.23`), and press **Connect**.
5. You'll see the phone's camera and hear its microphone on the PC.

### Bonus: use it in other programs

The streams are standard, so they also work without the viewer:

- **Browser**: open `http://PHONE_IP:8080` to watch the video.
- **VLC**: open network stream `http://PHONE_IP:8080/video` (video) or `http://PHONE_IP:8080/audio` (audio).
- **OBS Studio**: add a *Browser* or *Media* source with `http://PHONE_IP:8080/video` to use the phone as a webcam source for streaming/recording.

## Building locally (optional)

- **Android**: `cd android && gradle assembleDebug` (requires Android SDK + JDK 17). APK appears in `android/app/build/outputs/apk/debug/`.
- **Windows**: `cd windows && pip install -r requirements.txt && python viewer.py`, or package with `pyinstaller --onefile --windowed --name CamMicViewer viewer.py`.
