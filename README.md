# Solax FVE Monitor

Real-time monitoring app for Solax solar inverters, tested with **Solax X3 Hybrid G4** with **WiFi 3.0 dongle**. Connects directly to the inverter's local API (modbus TCP or HTTP) within your home network and displays live power data, battery status, grid details, and energy statistics.

## Features

- Live power flow visualization (Solar, Battery, Grid, Home consumption)
- 3-phase grid details (voltage, current, power, frequency)
- Battery status (SoC, voltage, temperature, charge/discharge)
- EPS (backup power) monitoring
- Energy totals (daily yield, feed-in, consumption)
- Multiple inverter support with quick switching
- Works as a standalone Android/iOS app or a web app in the browser
- Communicates over Solax modbus TCP or Solax HTTP API

## Screenshots

<a href="docs/tablet-screenshot.png"><img src="docs/tablet-screenshot.png" width="400" alt="Solax FVE Monitor on Tablet"></a> <a href="docs/phone-screenshot.png"><img src="docs/phone-screenshot.png" width="130" alt="Solax FVE Monitor on Phone"></a>

## Requirements

- Solax X3 Hybrid G4 inverter with a dongle on the local network
- The inverter's local IP address and dongle password (usually the dongle serial number)

## Web Application

```bash
PROXY_TARGET=http://192.168.199.192 pnpm start
```

Starts a Node.js HTTP server at [http://localhost:8080](http://localhost:8080) that serves the web app and proxies all POST requests to the inverter specified by `PROXY_TARGET`. This avoids CORS/mixed-content issues without any special browser flags. Without `PROXY_TARGET`, it serves static files only.

The server also acts as an HTTP proxy to Solax Modbus TCP. Set `MODBUS=1` to make Modbus the default for POST requests (the Modbus host is derived from `PROXY_TARGET`). Both `/http` and `/modbus` endpoints are additional available for side-by-side comparison.

Assuming that you open web application in the browser at http://localhost:8080, the app's connection settings set the inverter hostname to `localhost:8080/http` to communicate using Solax HTTP API, `localhost:8080/modbus` to communicate using Solax modbus TCP, or `localhost:8080`to use a protocol that the server was started with depending on MODBUS env variable.


```bash
# HTTP proxy by default:
PROXY_TARGET=http://192.168.199.192 pnpm start

# Modbus TCP as default:
MODBUS=1 PROXY_TARGET=http://192.168.199.192 pnpm start
```

## Android App

The project uses [Capacitor](https://capacitorjs.com/) to wrap the web app into a native Android application. This removes the HTTPS/mixed-content restriction — the app can freely connect to inverters over HTTP on the local network.

### Prerequisites

- Node.js 18+
- JDK 21 (the project includes `.sdkmanrc` for [SDKMAN!](https://sdkman.io/) users)
- Android Studio (optional — you can build from the command line)

### Build APK

```bash
pnpm install
pnpm run build
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

## iOS App

The project also uses [Capacitor](https://capacitorjs.com/) to wrap the web app into a native iOS application. Like the Android version, this removes the HTTPS/mixed-content restriction — the app can freely connect to inverters over HTTP on the local network.

### Prerequisites

- Node.js 18+
- Xcode (with command-line tools installed)
- An Apple Developer account (free account works for personal device testing)

### Build

```bash
pnpm install
pnpm run build:ios
```

Alternatively, open the project in Xcode and build from there:

```bash
npx cap open ios
```

Then select your target device or simulator and hit **Product > Build** (⌘B).

### Install on iPhone

1. Open the project in Xcode: `npx cap open ios`
2. Connect your iPhone via USB (or set up wireless debugging)
3. Select your iPhone as the build target
4. Set your Apple Developer team in **Signing & Capabilities** (under the "App" target)
5. Hit **Run** (⌘R) — Xcode will build, install, and launch the app on your phone
6. On first install, you may need to trust the developer certificate on your iPhone: **Settings > General > VPN & Device Management**

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
