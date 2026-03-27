# Solax FVE Live Monitor

Live monitoring app for Solax X3 Hybrid G4 inverters. The Android app connects directly to the inverter on your home network. A web application is also provided for non-Android devices and for remote access outside your home network to a provided proxy that must be run within your home network.

<a href="docs/tablet-screenshot.png"><img src="docs/tablet-screenshot.png" height="300" alt="Solax FVE Monitor on Tablet"></a> <a href="docs/phone-screenshot.png"><img src="docs/phone-screenshot.png" height="300" alt="Solax FVE Monitor on Phone"></a>

- Tested with **Solax X3 Hybrid G4**, **Pocket WiFi 3.0 dongle**
- Scans your home network in order to find your Solax inverter 
- Live power flow visualization (Solar, Battery, Grid, Home consumption)
- 3-phase grid details (voltage, current, power, frequency)
- Battery status (SoC, voltage, temperature, charge/discharge)
- EPS (backup power) monitoring
- Energy totals (daily solar, battery, grid in/out; totals)
- Multiple inverter support with quick switching
- Works as a standalone Android app (phone and TV) or a web app in the browser
- Dark and Light mode (dark by default)
- Double tap to zoom, then zoom out on double tap or automatically after 8 seconds
- Full screen support, show/hide top menu on app icon tap
- Modbus TCP from within the home network, HTTP for WiFi 3.0 dongle HTTP API, or HTTPS when connecting through a proxy
- PWA support — install the web app to your home screen for a native-like experience
- Guide for hosting the web application and proxy server as a [service on Raspberry Pi](./docs/remote-hosting-through-raspberry-pi.md)

## Install on Android phone / TV

The easiest way to install the application on your phone/TV without a USB cable is via [LocalSend](https://localsend.org/):

1. Install LocalSend on both your computer and phone/TV
2. Make sure both devices are on the same WiFi network
3. Open LocalSend on both devices
4. Download the latest APK file from [releases](https://github.com/sranka/solax-fve-live-app/releases)
5. From your computer, send the APK file to phone/TV
6. Accept the file on your phone/TV and open it to install
7. You may need to allow installation from unknown sources in settings
8. Open the application and connect to your Solax inverter. Use **Scan Network** in the settings to discover dongles on your local network, or add a connection manually if you know the connection settings.

## Install on iOS

It is technically possible to build this app for iOS using Capacitor, but installing apps outside the Apple App Store is cumbersome and not practical for open distribution. If you are an experienced developer, you can build and install the application to your phone directly. Otherwise, you can run the app on iOS as a [PWA](#install-as-pwa-progressive-web-app) (see next section), but a PWA cannot communicate directly with inverters on your home network over Modbus due to browser security restrictions.

## Install as PWA (Progressive Web App)

The application can be installed as a PWA on any device — phone, tablet, or desktop. Once installed, it runs in its own window without browser, just like a native app.

**Note:** Unlike the native application, a PWA runs inside the browser and cannot connect directly to the inverter on your local network due to browser security restrictions (CORS, mixed content). You need to connect to the web application's proxy server (see [Web Application Server](#web-application-server) below) to access your inverter.

1. Open the web app in your browser
   - Running the app from an **HTTPS** URL, such as https://solax.sranka.fun, will disallow connections to inverters over HTTP, because browsers block mixed content (HTTPS→HTTP)
   - Running the app from an **HTTP** URL, such as http://localhost:8080, allows both HTTP and HTTPS connections — follow the instructions in the [Web Application Server](#web-application-server) section below to set this up
2. **Chrome / Edge:** Click the install icon in the address bar (or use the browser menu → "Install app") and follow the prompt
3. **Safari (macOS):** Choose **File → Add to Dock**

For more details on how PWA installation works, see [web.dev — Install your PWA](https://web.dev/learn/pwa/installation).

## Web Application and Proxy Server

Web application server is run with [Node.js 18+](https://nodejs.org/en/download), no other dependencies are required.

```bash
MODBUS=1 PROXY_TARGET=http://192.168.199.192 node scripts/server.js
```

An HTTP server is started at [http://localhost:8080](http://localhost:8080). It serves the web app and proxies HTTP POST requests to the inverter specified by `PROXY_TARGET`. This avoids CORS/mixed-content issues without any special browser flags.

The server also acts as an HTTP proxy to Solax Modbus TCP. Set `MODBUS=1` to make Modbus the default for POST requests (the Modbus host is derived from `PROXY_TARGET`). Both `/http` and `/modbus` endpoints are also available for side-by-side comparison.

When using the web app at http://localhost:8080, set the inverter hostname in the app's connection settings to:

- `localhost:8080/http` — Solax HTTP API
- `localhost:8080/modbus` — Solax Modbus TCP
- `localhost:8080` — server default (depends on the `MODBUS=1` environment variable)

```bash
# HTTP proxy by default:
PROXY_TARGET=http://192.168.199.192 pnpm start

# Modbus TCP as default:
MODBUS=1 PROXY_TARGET=http://192.168.199.192 pnpm start
```

## Development
### Android App

The project uses [Capacitor](https://capacitorjs.com/) to wrap the web app into a native Android application. This removes the HTTPS/mixed-content restriction — the app can freely connect to inverters over Solax Modbus TCP or to Solax local HTTP API on the local network.

#### Prerequisites

- Node.js 18+
- JDK 21 (the project includes `.sdkmanrc` for [SDKMAN!](https://sdkman.io/) users)
- Android Studio (optional — you can build from the command line)

#### Build APK

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

## License

MIT
