# NetCam Pro — Android Sensor Camera

Turn your Android phone into a **WiFi IP camera + environmental sensor server**. No cloud, no subscription — just your phone and a browser.

## Features

### 📷 Video Streaming
- **MJPEG stream** — view in any browser at `/video`
- **RTSP stream** — port 8554, H.264 + AAC, playable in VLC/FFmpeg
- **Snapshot** — `/shot.jpg` for still image capture

### 🎤 Audio
- Real-time AAC audio capture from microphone
- Streamed alongside video via HTTP (`/audio.aac`) and RTSP

### 🔄 Camera Control
- Front/back camera switch
- Digital zoom (1x–8x)
- Flash/torch toggle
- Mirror flip
- Resolution select: 3840×2160 / 1920×1080 / 1280×720 / 864×480 / 640×480 / 320×240
- JPEG quality 1–100, FPS 5–30
- All controllable via **Web UI** or **HTTP API**

### 🌡️ Sensor Overlay (OSD)
Read and overlay phone sensor data on the video stream:
- Ambient light (lux)
- Temperature (°C)
- Atmospheric pressure (hPa)
- Humidity (%)
- Accelerometer (X/Y/Z)
- Gyroscope (X/Y/Z)
- Battery level + charging status
- Timestamp

### 🚶 Motion Detection
- Grid-based frame differencing (32×24 grid)
- Configurable sensitivity
- Motion alert via HTTP JSON API

### 🖥️ Web Management UI
Built-in web interface at `http://<phone-ip>:8080`:
- Live video player
- Screenshot download
- Motion detection toggle
- Server restart
- Status: resolution, audio, RTSP URL, motion alerts

### 🔌 HTTP API
All camera functions available via REST API:

| Endpoint | Description |
|----------|-------------|
| `POST /api/cam_switch?facing=front\|back` | Switch camera |
| `POST /api/torch?on=true\|false` | Flash toggle |
| `POST /api/mirror?enabled=true\|false` | Mirror flip |
| `POST /api/zoom?value=1.0-8.0` | Zoom level |
| `POST /api/quality?value=1-100` | JPEG quality |
| `POST /api/fps?value=5-30` | Frame rate |
| `POST /api/resolution?value=WxH` | 3840×2160 / 1920×1080 / ... |
| `POST /api/exposure?value=-3.0-3.0` | Exposure compensation |
| `POST /api/white_balance?mode=auto\|sunny\|...` | White balance |
| `GET /status` | Server status JSON |
| `GET /shot.jpg` | Snapshot |
| `POST /api/restart` | Restart server |

## Quick Start

1. **Install** the APK on your Android phone
2. **Open** the app, grant camera + microphone permissions
3. **Tap "Start Server"** — the app shows the server URL
4. **Open a browser** on the same WiFi network, go to the displayed URL

```
http://192.168.x.x:8080
```

That's it. You'll see the live video stream.

### RTSP Stream (VLC etc.)

```
rtsp://192.168.x.x:8554/live
```

## Build

```bash
git clone https://github.com/fanjumin/netcam-android.git
cd netcam-android
./gradlew assembleDebug
```

Requires Android SDK 35+, Kotlin 2.0+, Gradle 8.7+.

## Tech Stack

- **Camera**: Android Camera1 API (`android.hardware.Camera`)
- **UI**: Jetpack Compose + Material 3 (dark theme)
- **HTTP Server**: NanoHTTPD (embedded, no root required)
- **Video**: MJPEG over HTTP, H.264 over RTSP/RTP
- **Audio**: AAC-LC via MediaCodec hardware encoder
- **Persistence**: DataStore Preferences
- **License**: MIT

## License

MIT
