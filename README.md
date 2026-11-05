# NearMe 🎯⏰ – Smart Destination-Based Commuter Alarm

A native Android application built with **Kotlin**, **Jetpack Compose (Material 3)**, and **OpenStreetMap (OSMDroid)** designed to alert travelers and commuters the moment they approach or reach their destination.

---

## ✨ Features

- **🗺️ 100% Free & Open-Source Mapping**: Built with OpenStreetMap (OSMDroid) + Nominatim place search + OSRM road routing engine. Zero API keys, zero Google Cloud billing, zero cost.
- **🛣️ Real Road Distance & Travel Duration**: Uses OSRM to calculate the actual road/bus network distance and travel time rather than inaccurate straight-line measurements, with drawn road polyline routing.
- **⏰ Native Loud Alarm & Continuous Vibration**: Plays the device's default alarm tone via `RingtoneManager.TYPE_ALARM` on the alarm audio stream with looping vibration.
- **📱 Lock-Screen Wake**: Wakes display and overlays the lock screen using `setShowWhenLocked(true)` and `setTurnScreenOn(true)` so sleeping passengers are awakened reliably.
- **🔔 Multi-Stage Alert Zones & Early Warnings**: Configurable outer warning zones (e.g. 2 km, 500 m) with gentle heads-up notifications and vibration before reaching the stop.
- **🛡️ Missed-Stop Detection**: Monitors closest distance reached and sounds an emergency alert if the commuter sleeps past their stop and starts moving away.
- **🌐 Dual-Language Support**: Instant 1-tap switcher between English (`EN`) and Malayalam (`മലയാളം` / `മ`).
- **🎯 Custom Alert Radius**: Choose from presets (200m, 500m, 1km, 2km) or type any custom distance with an in-box `m` ⇄ `km` toggle.
- **🧪 Desk-Test Simulator**: Built-in test simulator in the Mobile Debug menu allows testing arrival and missed-stop alarms without leaving your desk.

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin 2.0
- **UI Framework:** Jetpack Compose (Material 3)
- **Map Engine:** OSMDroid 6.1.18 (OpenStreetMap)
- **Place Search:** OpenStreetMap Nominatim API
- **Routing Engine:** Project OSRM (Open Source Routing Machine)
- **Background Service:** Android Foreground Service (`FOREGROUND_SERVICE_TYPE_LOCATION`, `PARTIAL_WAKE_LOCK`)
- **Audio & Haptics:** `MediaPlayer` + `RingtoneManager` fallback + `Vibrator`

---

## 🚀 Getting Started

### Open in Android Studio
1. Clone the repository:
   ```bash
   git clone <YOUR_REPO_URL>
   ```
2. Open **Android Studio** and choose **Open**, selecting the project folder.
3. Android Studio will automatically sync Gradle dependencies.

### Run on Your Physical Phone
1. Enable **Developer Options** and **USB Debugging** on your phone.
2. Connect your phone via USB.
3. Select your device from the Android Studio device dropdown and click **Run (▶)**.

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
