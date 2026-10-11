# RR 310 Nav Bridge — Google Maps to TVS Apache RR 310

A lightweight Android companion app that intercepts live turn-by-turn navigation notifications from the official **Google Maps** app and relays them natively to the **TVS Apache RR 310** (N597 / U796) TFT instrument cluster via Bluetooth Low Energy (BLE).

---

## Features
- **Real-Time Turn-by-Turn**: Displays turn arrows, distance counts, and street names on your motorcycle's dashboard.
- **Official Google Maps Support**: No reliance on the proprietary TVS Connect in-app map or MapmyIndia.
- **Symmetric XOR Mask & Checksum**: Implements the reverse-engineered TVS SmartXonnect wire protocol (XOR `0xEA`, 1's complement sum checksum).
- **Built-in Dashboard Simulator**: Test buttons ("Right (300m)", "Left (150m)", "U-Turn", "Arrived") let you verify cluster displays with your bike parked in the garage.
- **Persistent Background Service**: Keeps BLE connection alive across your entire ride.

---

## Project Structure

```
rr310-nav-bridge/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/rr310/navbridge/
│   │   │   ├── ble/
│   │   │   │   └── BleManager.kt                  # BLE Scanner, GATT client, characteristic writer
│   │   │   ├── parser/
│   │   │   │   └── GoogleMapsNotificationParser.kt # Extracts distance, ETA, pictograms & street names
│   │   │   ├── protocol/
│   │   │   │   └── TvsProtocol.kt                 # 20-byte navigation frame builder & XOR cipher
│   │   │   ├── service/
│   │   │   │   ├── NavBridgeService.kt            # Foreground service maintaining BLE connection
│   │   │   │   └── GoogleMapsNotificationListener.kt # Intercepts Google Maps notifications
│   │   │   └── ui/
│   │   │       └── MainActivity.kt                # Setup wizard, live telemetry, and test buttons
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       └── values/{colors, strings, themes}.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

---

## How to Build & Install

### Method 1: Using Android Studio (Recommended)
1. Open **Android Studio**.
2. Select **Open** and select the folder:
   `/Users/mayukhbiswas/.gemini/antigravity/scratch/rr310-nav-bridge`
3. Connect your Android phone via USB (with **USB Debugging** enabled in Developer Options).
4. Click **Run** (`Shift + F10`) to build and install the app on your phone.

### Method 2: Building via Terminal
If you have the Android SDK command-line tools installed:
```bash
cd /Users/mayukhbiswas/.gemini/antigravity/scratch/rr310-nav-bridge
./gradlew assembleDebug
```
The compiled APK will be at:
`app/build/outputs/apk/debug/app-debug.apk`

Install it via ADB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## How to Use the App

### Step 1: Grant Permissions
1. Open **RR 310 Nav Bridge** on your phone.
2. Grant Bluetooth & Location permissions when prompted.
3. Tap the yellow banner: **"Grant Access"**. It will take you to Android's **Device & App Notifications** settings.
4. Toggle **RR 310 Nav Bridge** to **ON** / **Allowed**.

### Step 2: Connect to Your Motorcycle
1. Turn ON your TVS Apache RR 310 ignition (ensure the bike's TFT cluster boots up and Bluetooth icon is blinking/ready).
2. In the app, tap **"Connect to RR 310"**.
3. The app scans for the SmartXonnect GATT service (`5456534D-5647-5341-5342-454E544F5251`) and connects.
4. The status card turns green: **"Status: CONNECTED"**.

### Step 3: Verify with Simulation Buttons
Before riding, test the cluster display using the buttons in the **Test On Dashboard** section:
- Tap **"Right (300m)"** $\to$ Look at your cluster: A right turn arrow with "300 m" and "MG Road" will appear!
- Tap **"U-Turn"** $\to$ A U-turn icon will display.
- Tap **"Arrived"** $\to$ The destination reached checkered flag will display.

### Step 4: Ride with Google Maps
1. Open the official **Google Maps** app.
2. Set your destination and tap **Start** navigation.
3. That's it! As you ride, Google Maps' navigation notifications will be captured and forwarded in real-time to your bike's cluster.
