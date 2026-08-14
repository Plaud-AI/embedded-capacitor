# Capacitor + PlaudSdk native setup (iOS and Android)

This covers Step 1 and Step 2 in detail: standing up the Capacitor shell and dropping in the
native Plaud plugin so the web layer can reach the Plaud SDK.

Sections §1–§2 are shared by both platforms. §3 is iOS-only, §4 is Android-only — read only
the one(s) you're shipping.

---

## 1. Install & initialize Capacitor (shared)

```bash
npm i @capacitor/core @capacitor/ios @capacitor/android @capacitor-community/bluetooth-le
npm i -D @capacitor/cli
npx cap init          # prompts for appId (e.g. ai.plaud.pwademo), appName, webDir
npx cap add ios       # scaffolds ios/App
npx cap add android   # scaffolds android/
npx cap sync ios
npx cap sync android  # copies web assets + native config; run after every web/plugin/config change
```

Install only the platform packages you need — `@capacitor/ios`, `@capacitor/android`, or both.
The reference app is on Capacitor 8 (`@capacitor/core` 8.4.x).

`@capacitor-community/bluetooth-le` is registered in the app (it appears in
`packageClassList` below). The Plaud plugin itself is **app-local** on both platforms and is
registered by hand (§3.3 for iOS, §4.3 for Android).

## 2. Point the shell at your web app — `capacitor.config.ts` (shared)

Set `server.url` in the **root `capacitor.config.ts`** (the source of truth), *not* the
per-platform `capacitor.config.json`. `npx cap sync` regenerates
`ios/App/App/capacitor.config.json` and `android/app/src/main/assets/capacitor.config.json`
from the TS on every run, so a manual JSON edit is overwritten. `server.url` makes the native
shell load this **remote** URL in the WebView and inject the Capacitor bridge, rather than
serving bundled assets.

```typescript
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'ai.plaud.pwademo',
  appName: 'Plaud PWA Demo',
  webDir: 'public',                              // required, but ignored at runtime when server.url is set
  server: {
    url: 'https://pwa-demo-plaud.vercel.app',
    cleartext: false,
  },
};

export default config;
```

After `npx cap sync`, the generated native `capacitor.config.json` (same content on both
platforms) looks like this:

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

- `webDir` is required by Capacitor even though its contents are ignored at runtime when
  `server.url` is set.
- `packageClassList` is what Capacitor auto-registers, and **sync injects it** into the JSON
  when it detects an installed plugin (hence `BluetoothLe`) — it isn't in `capacitor.config.ts`.
  `PlaudSdk` is **not** here on either platform — it's app-local, registered manually.
  (On Android the same list is also mirrored into
  `android/app/src/main/assets/capacitor.plugins.json`.)
- Because the origin is remote, **deploy web changes before testing on device**;
  `npx cap sync` does not push your web code.

---

# iOS

## 3.1 Copy the PlaudPlugin package

Copy the whole `ios/PlaudPlugin/` directory into your project's `ios/`. It is a local
SwiftPM package that wraps the three precompiled Plaud xcframeworks as **binary targets**, so
SwiftPM embeds and code-signs them automatically (no manual "Embed Frameworks" build phase),
and it survives `npx cap sync`.

```
ios/PlaudPlugin/
├── Package.swift
├── Frameworks/
│   ├── PlaudBleSDK.xcframework          # low-level BLE transport, audio decode, crypto
│   ├── PlaudWiFiSDK.xcframework          # WiFi fast-transfer transport
│   └── PlaudDeviceBasicSDK.xcframework   # high-level facade (recommended entry point)
└── Sources/PlaudPlugin/
    └── PlaudSdkPlugin.swift              # the CAPPlugin bridge class
```

`Package.swift` declares the frameworks as binary targets and depends on
`capacitor-swift-pm` (pin the version to your Capacitor version, e.g. `8.4.1`):

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PlaudPlugin",
    platforms: [.iOS(.v15)],
    products: [ .library(name: "PlaudPlugin", targets: ["PlaudPlugin"]) ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.4.1")
    ],
    targets: [
        .binaryTarget(name: "PlaudBleSDK", path: "Frameworks/PlaudBleSDK.xcframework"),
        .binaryTarget(name: "PlaudWiFiSDK", path: "Frameworks/PlaudWiFiSDK.xcframework"),
        .binaryTarget(name: "PlaudDeviceBasicSDK", path: "Frameworks/PlaudDeviceBasicSDK.xcframework"),
        .target(
            name: "PlaudPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                "PlaudBleSDK", "PlaudWiFiSDK", "PlaudDeviceBasicSDK"
            ]
        )
    ]
)
```

## 3.2 Link the package into the App target

Add this package to the App target in Xcode the same way `CapApp-SPM` is added (File → Add
Package Dependencies → Add Local → select `ios/PlaudPlugin`). This is a **build-system** link:
without it `import PlaudPlugin` in `MainViewController.swift` fails with "No such module
'PlaudPlugin'". It's distinct from the **runtime** registration in `capacitorDidLoad()` (§3.3) —
you need both. Also confirm the App target's minimum deployment is **iOS 15.0+**, which the
Plaud xcframeworks require (`Package.swift` declares `.iOS(.v15)`).

## 3.3 Register the plugin manually — `MainViewController.swift`

Capacitor 8 auto-registers plugins listed in `packageClassList`, which the CLI only fills in
for npm-installed plugins. `PlaudSdk` lives in the local SwiftPM package, so without this
step it surfaces as **"PlaudSdk plugin is not implemented on iOS"**. Register the instance in
`capacitorDidLoad()`:

```swift
import Capacitor
import PlaudPlugin

class MainViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(PlaudSdkPlugin())
    }
}
```

Wire `MainViewController` as the `customClass` on the Bridge View Controller in
`Main.storyboard` (set the class in the Identity Inspector). `capacitorDidLoad()` runs right
after the bridge finishes auto-registration and before the web content loads, so `PlaudSdk`
is available to JS by the time the page runs.

## 3.4 Info.plist permissions

Declare the Bluetooth entitlement in `ios/App/App/Info.plist`. Without it iOS terminates the
app the moment it touches CoreBluetooth.

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Uses Bluetooth to connect and interact with peripheral BLE devices.</string>
<key>UIBackgroundModes</key>
<array>
  <string>bluetooth-central</string>
</array>
```

- `NSBluetoothAlwaysUsageDescription` — required; the string shown in the permission prompt.
- `UIBackgroundModes` → `bluetooth-central` — keeps the BLE connection alive when the app is
  backgrounded (e.g. mid file sync / export).
- Hotspot Configuration entitlement — only if using `PlaudWiFiAgent` fast transfer.

## 3.5 Build & run (iOS)

```bash
npx cap sync ios     # after any web / plugin / config change
npx cap open ios     # opens Xcode
```

Build and run on a **physical iPhone** — the Plaud frameworks are arm64 device-only and won't
link for the Simulator. Grant the Bluetooth permission on first launch; the plugin gates the
actual scan on CoreBluetooth reaching `.poweredOn`.

---

# Android

The Android SDK ships as a bare `.aar` in the app module rather than as a package, and the
plugin class lives in your app's own source tree. After the copy steps your `android/` looks
like this (★ = you touched it):

```
android/
├── app/
│   ├── libs/
│   │   └── plaud-sdk.aar                ★ copied (§4.1)
│   ├── build.gradle                     ★ edited (§4.4)
│   └── src/main/java/<your/package>/
│       ├── MainActivity.java            ★ edited (§4.3)
│       └── PlaudSdkPlugin.java          ★ copied (§4.2)
└── variables.gradle                     ★ edited (§4.4)
```

## 4.1 Copy the SDK

Copy `android/app/libs/plaud-sdk.aar` into your project's `android/app/libs/`. This is the
precompiled Plaud Android SDK — the counterpart to the three iOS xcframeworks. Unlike the
iOS package it is not self-describing; see §4.4.

## 4.2 Copy the plugin class

Copy `android/app/src/main/java/ai/plaud/pwademo/PlaudSdkPlugin.java` into your app's package
directory and **change its `package` declaration** to match your `applicationId`. Nothing
else in the file is app-specific.

It's annotated as:

```java
@CapacitorPlugin(
    name = "PlaudSdk",
    permissions = {
        @Permission(alias = "bluetoothScan",    strings = { Manifest.permission.BLUETOOTH_SCAN }),
        @Permission(alias = "bluetoothConnect", strings = { Manifest.permission.BLUETOOTH_CONNECT }),
        @Permission(alias = "location",         strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public class PlaudSdkPlugin extends Plugin implements PlaudDeviceAgentListener { … }
```

`name = "PlaudSdk"` is what `registerPlugin("PlaudSdk")` on the JS side resolves to — it must
match iOS's plugin identifier exactly, which is what lets one `plaud-sdk.ts` serve both.

## 4.3 Register the plugin manually — `MainActivity`

Same reason as iOS: Capacitor only auto-registers plugins that come from npm packages, and
this one is app-local. Without this it surfaces as **"PlaudSdk plugin is not implemented on
android"**. Register it **before** `super.onCreate()` so it's live by the time the bridge
comes up:

```java
package ai.plaud.pwademo;   // your applicationId

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PlaudSdkPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
```

## 4.4 Declare the SDK's dependencies — the step people miss

The `.aar` is a bare archive with **no POM**, so — unlike a Maven artifact — none of its
transitive dependencies resolve automatically. Every third-party package its bytecode
references has to be declared by hand, or the app compiles fine and then dies at runtime with
`NoClassDefFoundError` the first time that code path runs.

In `android/app/build.gradle`:

```gradle
repositories {
    flatDir {
        dirs '../capacitor-cordova-android-plugins/src/main/libs', 'libs'
    }
}

dependencies {
    // The stock Capacitor template says ['*.jar'] — '*.aar' is what picks up libs/plaud-sdk.aar.
    implementation fileTree(include: ['*.jar', '*.aar'], dir: 'libs')

    //   kotlin/*, kotlinx/coroutines/*      -> the SDK is written in Kotlin
    //   okhttp3/*, okio/*, retrofit2/*      -> its HTTP + REST layer
    //   com/google/gson, com/google/common  -> JSON + Guava
    //   org/bouncycastle/crypto             -> device handshake crypto
    //   org/java_websocket/*                -> WiFi fast-transfer websocket
    //   org/slf4j, ch/qos/logback           -> its logging facade (res/raw/logback.xml)
    //   timber/log                          -> its logging frontend
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

The "Conscrypt" reference inside the SDK is a JCE *provider name* string resolved from the
platform provider inside a try/catch — it needs no dependency here.

And the matching versions in `android/variables.gradle`:

```gradle
ext {
    // ...the generated Capacitor/AndroidX versions stay as they are (minSdkVersion 24 is fine)

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

## 4.5 Permissions — nothing to add to AndroidManifest.xml

Unlike iOS's `Info.plist`, the app manifest needs **no Bluetooth edits**. `plaud-sdk.aar`
carries its own `AndroidManifest.xml` declaring `BLUETOOTH`, `BLUETOOTH_ADMIN`,
`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` and
the WiFi permissions, and the Gradle manifest merger folds all of them into the packaged app.
The generated app manifest keeps just `INTERNET`.

Runtime permissions (Android 6+) are handled by the plugin itself. `startScan()` checks what
is actually needed for the running API level — `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` on API
31+, `ACCESS_FINE_LOCATION` below that — and calls `requestPermissionForAliases(...)` before
scanning, so **the JS caller never has to request anything**. A denial emits
`scanTimeout { reason: "permissionDenied" }` and rejects the call. `connectBleDevice()` does
the same for `BLUETOOTH_CONNECT`, emitting `connectFail { reason: "permissionDenied" }`.

## 4.6 Build & run (Android)

```bash
npx cap sync android     # after any web / plugin / config / gradle change
npx cap open android     # opens Android Studio
```

Run on a **physical Android phone** — the emulator has no Bluetooth radio, so a scan there
finds nothing. Grant the Bluetooth prompt when it appears on the first `startScan()`; the
plugin then waits for `BluetoothAdapter` to be enabled before handing the scan to the SDK,
polling for ~18s and giving up with `scanTimeout { reason: "bluetoothNotPoweredOn" }` if the
user never switches Bluetooth on.

To debug the native side:

```bash
adb logcat -s PlaudSdk:V BleAgentImpl:V
```

The plugin also mirrors transport-level detail to JS via the Android-only `connectFail`,
`connectStage`, `handshakeWaitSure`, `btStatus` and `scanFail` events — useful because the
SDK collapses every connection failure into a single `connectState(failed: true)`.
