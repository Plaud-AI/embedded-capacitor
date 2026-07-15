# Capacitor + PlaudPlugin native setup

This covers Step 1 and Step 2 in detail: standing up the Capacitor iOS shell and dropping in
the `PlaudPlugin` so the web layer can reach the Plaud SDK.

## 1. Install & initialize Capacitor

```bash
npm i @capacitor/core @capacitor/ios @capacitor-community/bluetooth-le
npm i -D @capacitor/cli
npx cap init          # prompts for appId (e.g. ai.plaud.pwademo), appName, webDir
npx cap add ios       # scaffolds ios/App
npx cap sync ios      # copies web assets + native config; run after every web/plugin/config change
```

`@capacitor-community/bluetooth-le` is registered in the app (it appears in
`packageClassList` below). The Plaud plugin itself is a **local** package and is registered
separately (see §4).

## 2. Copy the PlaudPlugin package

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

Add this package to the App target in Xcode the same way `CapApp-SPM` is added (File → Add
Package Dependencies → Add Local → select `ios/PlaudPlugin`). This is a **build-system** link:
without it `import PlaudPlugin` in `MainViewController.swift` fails with "No such module
'PlaudPlugin'". It's distinct from the **runtime** registration in `capacitorDidLoad()` (§4) —
you need both. Also confirm the App target's minimum deployment is **iOS 15.0+**, which the
Plaud xcframeworks require (`Package.swift` declares `.iOS(.v15)`).

## 3. Point the shell at your web app — edit `capacitor.config.ts`

Set `server.url` in the **root `capacitor.config.ts`** (the source of truth), *not* the
native `capacitor.config.json`. `npx cap sync` regenerates the JSON from the TS on every run,
so a manual JSON edit is overwritten. `server.url` makes the native shell load this **remote**
URL in the WebView and inject the Capacitor bridge, rather than serving bundled assets.

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

After `npx cap sync`, the generated `ios/App/App/capacitor.config.json` looks like this:

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
  `PlaudSdk` is **not** here — it's a local package, registered manually (§4).
- Because the origin is remote, **deploy web changes before testing on device**;
  `npx cap sync` does not push your web code.

## 4. Register the plugin manually — `MainViewController.swift`

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

## 5. Build & run

```bash
npx cap sync ios     # after any web / plugin / config change
npx cap open ios     # opens Xcode
```

Build and run on a **physical iPhone** — the Plaud frameworks are arm64 device-only and won't
link for the Simulator. Grant the Bluetooth permission on first launch; the plugin gates the
actual scan on CoreBluetooth reaching `.poweredOn`.

## Info.plist permissions

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
