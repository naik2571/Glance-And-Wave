# Glance & Wave

> **Touch-free ambient screen wake and glance dashboard triggered by sensor fusion.**

An enterprise-grade, privacy-first Android application developed in modern Kotlin and Jetpack Compose/Views. Built with strict adherence to modern Android architecture, lifecycle awareness, and hardware sensor efficiency.

---

## 🚀 Key Features

- Hardware sensor fusion combining ultrasonic/optical proximity sensor and ambient light sensor.
- **Wave-to-Wake**: A gentle hand wave within 5cm wakes the screen to show time, date, and battery.
- **Pocket Lockout**: Prevents accidental wakeups while inside pockets or bags.
- Ultra-low standby battery drain (< 1% per 8 hours).

---

## 🛠️ Hardware & Sensor Architecture

* **Sensors & APIs:** `Proximity Sensor, Ambient Light Sensor, PowerManager WakeLock`
* **Declared Permissions:** `WAKE_LOCK, POST_NOTIFICATIONS, FOREGROUND_SERVICE`
* **Target SDK:** Android 14 (API 34)
* **Minimum SDK:** Android 8.0 (API 26+)
* **Architecture:** MVVM / Unidirectional Data Flow (UDF) with Coroutines & StateFlow

---

## 🔒 Privacy & Permission Transparency

This application is engineered under the **Zero Unnecessary Permissions** doctrine:
* **No Hidden Trackers:** Contains zero advertising SDKs or third-party behavioral analytics.
* **No Accessibility Privilege Misuse:** All background operations utilize sanctioned Android APIs (`WorkManager`, `ForegroundService`, `JobScheduler`).
* **Air-Gapped Operation:** Runs locally on your device hardware without requiring external cloud servers.

---

## 📦 Building and Running

### Prerequisites
* Android Studio Iguana / Jellyfish / Ladybug or newer
* Android SDK 34
* JDK 17+

### Build APK via CLI
```bash
# Clone the repository
git clone https://github.com/naik2571/Glance-And-Wave.git
cd Glance-And-Wave

# Build Debug APK
./gradlew assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License
Licensed under the [Apache License 2.0](LICENSE).
