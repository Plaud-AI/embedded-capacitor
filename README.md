# Plaud Embedded's Capacitor Plugin

This plugin helps you convert **web apps to iOS and Android** that implement [Plaud Embedded](https://docs.plaud.ai/plaud-embedded) to integrate with Plaud devices.

Both platforms expose the **same JavaScript surface** (`PlaudSdk`), so your web app code is
written once. Only the native shell differs: Swift + xcframeworks on iOS, Java + an `.aar`
on Android.

## How to run the demo app

The demo app is included in the Plaud Embedded plugin as reference for implementing the plugin in your own app and seeing how everything works.

### 1. Clone the Embedded Capacitor repo
```bash
git clone https://github.com/Plaud-AI/embedded-capacitor.git
```

### 2. Install dependencies and set up env vars
```bash
cd nextjs-demo
npm i
```

### 3a. Build and open in XCode (iOS)
```bash
npx cap sync ios
npx cap open ios
```
In XCode, make sure to include your Apple developer credentials and certificate. 

Then **run on a physical device** to test out the demo app with your Plaud devices.

### 3b. Build and open in Android Studio (Android)
```bash
npx cap sync android
npx cap open android
```
The Gradle build pulls the Plaud SDK from `android/app/libs/plaud-sdk.aar` and its
transitive dependencies from Maven — the first sync will take a few minutes.

Then **run on a physical device** as well. The Android emulator has no Bluetooth radio, so
scanning finds nothing there.

## How to use in your own app

### Step 0: Install the skill from this repo

The Skill has context on the Plaud Embedded plugin to help you implement this 
plugin for your web app.

```bash
npx skills add Plaud-AI/embedded-capacitor
```

### Step 1: Setup Capacitor

Start by installing Capacitor in your js project

```bash
npm i @capacitor/core @capacitor/ios @capacitor/android @capacitor-community/bluetooth-le
npm i -D @capacitor/cli
```

> Install only the platform packages you need — `@capacitor/ios`, `@capacitor/android`, or both.

Then initialize Capacitor to setup your Capactior configs

```bash
npx cap init
```

Lastly add your platforms to the capacitor project and sync your web app

```bash
npx cap add ios
npx cap add android

npx cap sync ios
npx cap sync android
```


### Step 2 (iOS): Copy PlaudPlugin files

1. Copy the `ios/PlaudPlugin/` framework and paste into the `ios/` directory.

2. Copy the `ios/App/App/MainViewController.swift` into your `ios/App/App` directory to register the PlaudPlugin

After steps 1–2, your `ios/` directory should look like this (★ = files/folders you copied in):

```
ios/
├── App/
│   ├── App/
│   │   └── MainViewController.swift
└── PlaudPlugin/
    ├── Package.swift
    ├── Frameworks/ 
    │   ├── PlaudBleSDK.xcframework
    │   ├── PlaudDeviceBasicSDK.xcframework
    │   └── PlaudWiFiSDK.xcframework
    └── Sources/
        └── PlaudPlugin/
            └── PlaudSdkPlugin.swift
```

3. Link `PlaudPlugin` into the App target in Xcode. 

Open the project (`npx cap open ios`), then **File → Add Package Dependencies… → Add Local**,
   select `ios/PlaudPlugin`, and add the `PlaudPlugin` library product to the **App** target
   (the same way `CapApp-SPM` is already linked).

   While you're there, make sure the App target's **minimum deployment is iOS 15.0 or higher** —
   the Plaud xcframeworks require it (`Package.swift` declares `.iOS(.v15)`), and the
   frameworks are arm64 **device-only** builds, so run on a physical iPhone, not the Simulator.

   > Linking the package (this step) is what lets the code compile; registering the plugin
   > instance in `MainViewController.swift`'s `capacitorDidLoad()` is a separate, runtime step —
   > you need both.

4. Add the Bluetooth entitlement in `ios/App/App/Info.plist`

```xml
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
  ...
	<key>NSBluetoothAlwaysUsageDescription</key>
	<string>Uses Bluetooth to connect and interact with peripheral BLE devices.</string>
	<key>UIBackgroundModes</key>
	<array>
		<string>bluetooth-central</string>
	</array>
</dict>
```

5. Lastly, point the native shell at your web app's URL. Set this in the root
   `capacitor.config.ts` — that's the source of truth, shared by both platforms.
   `npx cap sync` regenerates `ios/App/App/capacitor.config.json` and
   `android/app/src/main/assets/capacitor.config.json` from it, so editing either JSON
   directly gets overwritten on the next sync.

```typescript
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'ai.plaud.capacitordemo',
  appName: 'Plaud Capacitor Demo',
  // Required by Capacitor even when loading a remote URL; its contents are
  // ignored at runtime because `server.url` is set below.
  webDir: 'public',
  server: {
    // The native shell loads your deployed site and Capacitor injects the
    // native bridge, so the plugin can reach iOS CoreBluetooth.
    url: 'https://plaud-capacitor-demo.vercel.app',
    cleartext: false,
  },
};

export default config;
```

After `npx cap sync`, the generated `ios/App/App/capacitor.config.json` will look like this —
note `packageClassList`, which sync injects when it detects the installed `bluetooth-le` plugin:

```json
{
	"appId": "ai.plaud.pwademo",
	"appName": "Plaud PWA Demo",
	"webDir": "public",
	"server": {
		"url": "https://pwa-demo-plaud.vercel.app",
		"cleartext": false
	},
	"packageClassList": [
		"BluetoothLe"
	]
}
```

### Step 2 (Android): Copy PlaudPlugin files

Everything under `android/` in this repo is a stock `npx cap add android` template plus
five deltas. Apply those five to your own `android/` directory:

1. Copy `android/app/libs/plaud-sdk.aar` into your `android/app/libs/` directory. This is the
   precompiled Plaud Android SDK — the counterpart to the three iOS xcframeworks.

2. Copy `android/app/src/main/java/ai/plaud/pwademo/PlaudSdkPlugin.java` into your app's
   package directory, and **change its `package` declaration** to match your `applicationId`.

3. Register the plugin in your `MainActivity`, *before* `super.onCreate()`:

```java
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PlaudSdkPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
```

   > Capacitor auto-discovers plugins from installed npm packages. `PlaudSdk` is app-local,
   > so it isn't in that list and must be registered by hand — otherwise JS calls fail with
   > *"PlaudSdk plugin is not implemented on android"*. This is the Android counterpart to
   > iOS's `MainViewController.capacitorDidLoad()`.

After steps 1–3, your `android/` directory should look like this (★ = files you copied in):

```
android/
├── app/
│   ├── libs/
│   │   └── plaud-sdk.aar                ★
│   ├── build.gradle                     (edited, step 4)
│   └── src/main/java/<your/package>/
│       ├── MainActivity.java            (edited, step 3)
│       └── PlaudSdkPlugin.java          ★
└── variables.gradle                     (edited, step 4)
```

4. Declare the SDK's dependencies in `android/app/build.gradle`. The Plaud SDK ships as a
   bare `.aar`, which (unlike a Maven artifact) carries no POM — so **none** of its
   transitive dependencies come along automatically. Every one has to be declared by hand or
   the app dies at runtime with `NoClassDefFoundError` the first time that code path runs.

   Widen the stock `fileTree` to pick up `.aar` files, then add the dependency block:

```gradle
dependencies {
    // Stock template says ['*.jar'] — '*.aar' is what picks up libs/plaud-sdk.aar
    implementation fileTree(include: ['*.jar', '*.aar'], dir: 'libs')

    implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion"
    implementation "com.squareup.okhttp3:okhttp:$okhttpVersion"
    implementation "com.squareup.okhttp3:logging-interceptor:$okhttpVersion"
    implementation "com.squareup.retrofit2:retrofit:$retrofitVersion"
    implementation "com.squareup.retrofit2:converter-gson:$retrofitVersion"
    implementation "com.google.code.gson:gson:$gsonVersion"
    implementation "com.google.guava:guava:$guavaVersion"
    implementation "org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion"
    implementation "org.java-websocket:Java-WebSocket:$javaWebSocketVersion"
    implementation "org.slf4j:slf4j-api:$slf4jVersion"
    implementation "com.github.tony19:logback-android:$logbackAndroidVersion"
    implementation "com.jakewharton.timber:timber:$timberVersion"

    // ...leave the rest of the generated block (capacitor-android, androidx, tests) as-is
}
```

   And add the matching versions to `android/variables.gradle`:

```gradle
ext {
    // ...the generated Capacitor/AndroidX versions stay as they are

    // Transitive dependencies of libs/plaud-sdk.aar
    kotlinVersion = '1.9.25'
    coroutinesVersion = '1.8.1'
    okhttpVersion = '4.12.0'
    retrofitVersion = '2.11.0'
    gsonVersion = '2.11.0'
    guavaVersion = '33.2.1-android'
    bouncyCastleVersion = '1.78.1'
    javaWebSocketVersion = '1.5.7'
    slf4jVersion = '2.0.13'
    logbackAndroidVersion = '3.0.0'
    timberVersion = '5.0.1'
}
```

   Each group maps to a part of the SDK: Kotlin + coroutines (the SDK is written in Kotlin),
   OkHttp/Retrofit/Gson (its HTTP + REST layer), Guava, BouncyCastle (device handshake
   crypto), Java-WebSocket (WiFi fast transfer), and SLF4J/logback/Timber (logging).

5. **Bluetooth permissions need no manifest changes.** `android/app/src/main/AndroidManifest.xml`
   is byte-for-byte the stock template — `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and
   `ACCESS_FINE_LOCATION` are merged in from `@capacitor-community/bluetooth-le`'s own
   manifest at build time, and `PlaudSdkPlugin` requests them at runtime inside `startScan()`.
   If you skip the `bluetooth-le` package, declare them yourself:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:targetApi="s" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Finally, point the shell at your web app's URL via the root `capacitor.config.ts` — the same
file and the same `server.url` used for iOS (see iOS step 5 above).

#### Android build requirements

| | |
|---|---|
| `minSdkVersion` | 24 |
| `compileSdkVersion` / `targetSdkVersion` | 36 |
| Java source/target compatibility | 21 |
| Gradle / Android Gradle Plugin | 8.14.3 / 8.13.0 |

Run on a **physical Android device** — the emulator has no Bluetooth radio.

### Step 3: Use the Plaud SDK in your Web App

Capacitor acts as a bridge between your web app and the platform's native features. The Plaud Plugin uses Capacitor as the bridge to serialize data between your web app and native functionality like BLE.

**This code is identical on iOS and Android** — the two native plugins implement the same
method names and emit the same events, so there is no platform branching in your web app.
Guard calls with `Capacitor.isNativePlatform()` so they don't run in a plain browser, where
the plugin rejects with "not implemented".

In your web app code, import `PlaudSdk` and `PluginListenerHandle` to use the Plaud SDK in your web app

```typescript
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import {
  PlaudSdk,
  readExportedFile,
  type PlaudScanDevice,
  type PlaudFile,
} from "@/lib/plaud-sdk";

const handleConnect = async (d: PlaudScanDevice) => {
    setError(null);
    if (!ensureNative()) return;
    try {
      setStatus(`connecting to ${d.name || d.serialNumber}…`);
      await PlaudSdk.stopScan();
      setScanning(false);
      await PlaudSdk.connectBleDevice({ uuid: d.uuid, serialNumber: d.serialNumber });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };
```

## How the Capacitor plugin Works

The Capacitor plugin wraps your web app in a Capacitor native shell that serves your web app's URL in a native web view (`WKWebView` on iOS, `WebView` on Android). The plugin includes a bridge where native Swift or Java code can be called via Javascript.

Calling the PlaudSdk pushes data through the Capacitor bridge to the native code. Callbacks push data from native features to the Capacitor bridge to the Capacitor JS plugin via event listeners.

```
              ┌──────────────────────────────────────────┐
              │           Web App (JavaScript)           │
              │               PlaudSdk                   │
              └──────────────────▲───────────────────────┘
                                 │
                          Capacitor Bridge
                        (JavaScript ↔ IPC)
                    ┌────────────┴────────────┐
                    │                         │
┌───────────────────▼──────────┐  ┌───────────▼──────────────────┐
│   PlaudPlugin (Swift/iOS)    │  │  PlaudSdkPlugin (Java/Android)│
│   CoreBluetooth, Files,      │  │  BluetoothAdapter, Files,     │
│   iOS APIs, Events           │  │  Android APIs, Events         │
│   ← 3 × .xcframework         │  │  ← plaud-sdk.aar              │
└──────────────────────────────┘  └───────────────────────────────┘
```

### Where the platforms differ

The JS surface is identical, but three native behaviors are not:

| | iOS | Android |
|---|---|---|
| BLE permissions | Granted via `Info.plist` usage strings | Runtime permission request — `startScan()` handles it internally, so the JS flow is unchanged |
| Device identity | CoreBluetooth UUID | MAC address, emitted as `uuid` on `scanResult` (plus an explicit `macAddress` field) so `connectBleDevice` works unchanged |
| `blePenState` event | 7 values | 4 values — the three iOS-only ones are omitted rather than faked |
