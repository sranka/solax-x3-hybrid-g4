# Solax FVE Monitor

Real-time monitoring app for **Solax X3 Hybrid G4** solar inverters. Connects directly to the inverter's local API and displays live power data, battery status, grid details, and energy statistics.

## Features

- Live power flow visualization (Solar, Battery, Grid, Home consumption)
- 3-phase grid details (voltage, current, power, frequency)
- Battery status (SoC, voltage, temperature, charge/discharge)
- EPS (backup power) monitoring
- Energy totals (daily yield, feed-in, consumption)
- Multiple inverter support with quick switching
- Works as a standalone Android app or a web app in the browser

## Screenshot

<img src="docs/screenshot.png" width="320" alt="Solax FVE Monitor dashboard">

## Requirements

- Solax X3 Hybrid G4 inverter with WiFi dongle on the local network
- The inverter's local IP address and dongle password (usually the dongle serial number)

## Local Development

```bash
DEV_PROXY_TARGET=http://192.168.199.192 npm start
```

Starts a Node.js dev server at [http://localhost:8080](http://localhost:8080) that serves the web app and proxies all POST requests to the inverter specified by `DEV_PROXY_TARGET`. This avoids CORS/mixed-content issues without any special browser flags. Without `DEV_PROXY_TARGET`, it serves static files only.

In the app's connection settings, set the inverter hostname to `localhost:8080` — the dev server will forward requests to the real inverter.

### Insecure mode (legacy)

If you prefer the old approach that launches Chrome with relaxed security:

```bash
npm run dev:insecure
```

This opens Chrome with `--disable-web-security` and a separate profile so the app can directly call the inverter. Use only for local development.

## Android App

The project uses [Capacitor](https://capacitorjs.com/) to wrap the web app into a native Android application. This removes the HTTPS/mixed-content restriction — the app can freely connect to inverters over HTTP on the local network.

### Prerequisites

- Node.js 18+
- JDK 21 (the project includes `.sdkmanrc` for [SDKMAN!](https://sdkman.io/) users)
- Android Studio (optional — you can build from the command line)

### Build APK

```bash
npm install
npm run build
```

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, open the project in Android Studio and build from there:

```bash
npx cap open android
```

Then **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Install on phone

The easiest way to install the APK on your phone without a USB cable is via [LocalSend](https://localsend.org/):

1. Install LocalSend on both your computer and phone
2. Make sure both devices are on the same WiFi network
3. Open LocalSend on both devices
4. From your computer, send the APK file: `android/app/build/outputs/apk/debug/app-debug.apk`
5. Accept the file on your phone and open it to install
6. You may need to allow installation from unknown sources in your phone's settings

Other options for transferring the APK: email, cloud storage (Google Drive, etc.), or serve it over HTTP:

```bash
cd android/app/build/outputs/apk/debug
python3 -m http.server 8080
```

Then open `http://<your-computer-ip>:8080/app-debug.apk` on your phone.

## Inverter API

The app communicates with the Solax inverter via a simple HTTP POST:

```
POST http://<inverter-ip>/
Content-Type: application/x-www-form-urlencoded

optType=ReadRealTimeData&pwd=<dongle-password>
```

The response is a JSON object with a `Data` array containing register values for the X3 Hybrid G4.

## License

MIT
