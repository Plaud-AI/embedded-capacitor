# Plaud Embedded's Capacitor Wrapper

This wrapper helps you convert **web apps to iOS** that implement [Plaud Embedded](https://docs.plaud.ai/plaud-embedded) to integrate with Plaud devices.

## How to use

### Step 0: Install the skill from this repo

The Skill has context on the Plaud Embedded Wrapper to help you implement this 
wrapper for your web app.

```bash
npx skills add jackmuva/plaud-embedded-capacitor-wrapper
```

### Step 1: Setup Capacitor

Start by installing Capacitor in your js project

```bash
npm i @capacitor/core @capacitor/ios @capacitor-community/bluetooth-le
npm i -D @capacitor/cli
```

Then initialize Capacitor to setup your Capactior configs

```bash
npx cap init
```

Lastly add ios to your capacitor project and sync your web app

```bash
npx cap add ios
npx cap sync ios
```


### Step 2: Copy PlaudPlugin files

1. Copy the `ios/PlaudPlugin/` framework and paste into the `ios/` directory.

2. Copy the `ios/App/App/MainViewController.swift` into your `ios/App/App` directory to register the PlaudPlugin

After steps 1–2, your `ios/` directory should look like this (★ = files/folders you copied in):

```
ios/
├── App/
│   ├── App/
│   │   ├── AppDelegate.swift
│   │   ├── Assets.xcassets/
│   │   ├── Base.lproj/
│   │   │   ├── LaunchScreen.storyboard
│   │   │   └── Main.storyboard          # set MainViewController as the Bridge VC's custom class
└── PlaudPlugin/                        ★ # local SwiftPM plugin package (the whole folder)
    ├── Package.swift                     # declares the 3 xcframeworks as binary targets
    ├── Frameworks/                     ★
    │   ├── PlaudBleSDK.xcframework
    │   ├── PlaudDeviceBasicSDK.xcframework
    │   └── PlaudWiFiSDK.xcframework
    └── Sources/
        └── PlaudPlugin/
            └── PlaudSdkPlugin.swift      # the CAPPlugin bridge (JS ↔ native SDK)
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
   `capacitor.config.ts` — that's the source of truth. `npx cap sync` regenerates
   `ios/App/App/capacitor.config.json` from it, so editing the JSON directly gets
   overwritten on the next sync.

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

### Step 3: Use the Plaud SDK in your Web App

Capacitor acts as a bridge between your web app and iOS's native features. The Plaud Plugin uses Capacitor as the bridge to serialize data between your web app and iOS native functionality like BLE.

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

## How the Capacitor Wrapper Works

The Capacitor Wrapper wraps your web app in a Capacitor native shell that serves your web app's URL in a webkit view (WKWebView). The wrapper includes a bridge where native Swift code can be called via Javascript.

Calling the PlaudSdk pushes data through the Capacitor bridge to the native swift code. Callbacks push data from native features to the Capacitor bridge to the Capacitor JS plugin via event listeners.

```
┌──────────────────────────────────────────┐
│           Web App (JavaScript)           │
│               PlaudSdk                   │
└──────────────────▲───────────────────────┘
                   │
            Capacitor Bridge
          (JavaScript ↔ IPC)
                   │
┌──────────────────▼───────────────────────┐
│       PlaudPlugin (Swift/Native)         │
│    BLE, Files, iOS APIs, Events          │
└──────────────────────────────────────────┘
```
