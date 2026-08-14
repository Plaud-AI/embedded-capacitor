---
name: plaud-embedded-capacitor-skill
description: Wrap an existing web app in a Capacitor iOS and/or Android shell so it can talk to Plaud recording devices over Bluetooth via the Plaud Embedded SDK. Use this when a user has a web app (React/Next.js/etc.) that implements Plaud Embedded and wants to ship it as an iOS or Android app, or wants to call the native PlaudSdk Capacitor plugin (scan, connect, list files, export/decode audio) from JavaScript.
---

# Plaud Embedded Capacitor Wrapper

iOS has **no Web Bluetooth**, and mobile browsers that do expose it can't reach Plaud's
proprietary handshake and audio codec — so a browser-based web app can't drive Plaud
recording hardware directly. This skill wraps the web app in a **Capacitor native shell**: a
thin iOS and/or Android app that loads the web app's URL in a native web view (`WKWebView` /
`WebView`) and exposes Plaud's precompiled native SDK to the web layer through a custom
Capacitor plugin (`PlaudSdk`). The web app calls `PlaudSdk.*` in JavaScript; Capacitor
serializes those calls across the bridge to native Swift or Java, which drives the platform
Bluetooth stack and the Plaud SDK.

**One JS surface, two native implementations.** The two plugins expose the same method names
and emit the same events, so the web app has no platform branching. Where the platforms
genuinely differ, the difference is documented on the type in `references/plaud-sdk.ts`
(see "Platform differences" below).

## When to use this skill

Use it when the user wants to use a web app stack to create a mobile app (iOS and/or Android)
that can integrate with Plaud devices via
[Plaud Embedded](https://docs.plaud.ai/plaud-embedded)

If the user hasn't set up Plaud Embedded credentials or auth yet, they need the
`plaud-embedded-project-setup-skill` first. For pure audio transcription (no wrapper), see
`plaud-embedded-transcription-api-skill`.

### Prerequisites

- [ ] Plaud Embedded credentials and a way to mint a per-user token (see the setup skill)
- [ ] For iOS: macOS with Xcode and a **physical iPhone** (the frameworks are arm64
      device-only, and the Simulator has no Bluetooth radio)
- [ ] For Android: Android Studio and a **physical Android phone** (the emulator has no
      Bluetooth radio, so scanning finds nothing there)

## Setup — three steps

Follow these in order. Each step has a detailed reference file; read the reference before
running the commands.

### Step 0 — If the Plaud-AI/embedded-capacitor repo is not cloned

Clone Plaud-AI/embedded-capacitor if not done so. The native plugins for both platforms, plus
a sample Next.js app, live in this repo.

```bash
git clone https://github.com/Plaud-AI/embedded-capacitor.git
```

The pieces you copy out of it:

| Platform | Copy from | Into |
| --- | --- | --- |
| iOS | `ios/PlaudPlugin/` and `ios/App/App/MainViewController.swift` | your `ios/` |
| Android | `android/app/libs/plaud-sdk.aar` and `android/app/src/main/java/ai/plaud/pwademo/PlaudSdkPlugin.java` | your `android/` |
| Both | `nextjs-demo/lib/plaud-sdk.ts` (same file as `references/plaud-sdk.ts`) | your web app's `lib/` |

### Step 1 — Install and initialize Capacitor

Install only the platform packages the user actually needs.

```bash
npm i @capacitor/core @capacitor/ios @capacitor/android @capacitor-community/bluetooth-le
npm i -D @capacitor/cli
npx cap init            # sets appId / appName / webDir
npx cap add ios         # scaffolds the ios/ project
npx cap add android     # scaffolds the android/ project
npx cap sync ios
npx cap sync android
```

Then point the shell at the deployed web app by setting `server.url` in the **root
`capacitor.config.ts`** — one file, shared by both platforms. `npx cap sync` regenerates
`ios/App/App/capacitor.config.json` and `android/app/src/main/assets/capacitor.config.json`
from it, so editing either JSON directly gets overwritten on the next sync.

### Step 2a — iOS: add the PlaudPlugin and wire it up

The plugin is a **local SwiftPM package** that bundles the three precompiled Plaud
xcframeworks. Because it isn't an npm-installed plugin, Capacitor won't auto-register it —
you register it by hand.

1. Copy `ios/PlaudPlugin/` into your project's `ios/` directory.
2. Copy `ios/App/App/MainViewController.swift` into `ios/App/App/` — it registers the plugin
   instance in `capacitorDidLoad()` (a **runtime** step).
3. Link `PlaudPlugin` into the App target in Xcode (a separate **build** step — copying the
   folder alone won't compile). `npx cap open ios`, then **File → Add Package Dependencies →
   Add Local**, select `ios/PlaudPlugin`, and add its library product to the App target the
   way `CapApp-SPM` already is. Without this, `import PlaudPlugin` fails with "No such module
   'PlaudPlugin'"; without step 2 it compiles but throws "PlaudSdk plugin is not implemented
   on iOS" at runtime. You need both.
   - Ensure the App target's **minimum deployment is iOS 15.0+** — the Plaud xcframeworks
     require it (`Package.swift` declares `.iOS(.v15)`).
4. Declare the Bluetooth entitlement in `ios/App/App/Info.plist` — without it iOS kills the
   app the moment it touches CoreBluetooth:

   ```xml
   <key>NSBluetoothAlwaysUsageDescription</key>
   <string>Uses Bluetooth to connect and interact with peripheral BLE devices.</string>
   <key>UIBackgroundModes</key>
   <array>
     <string>bluetooth-central</string>
   </array>
   ```

### Step 2b — Android: add the PlaudSdkPlugin and wire it up

The Android SDK ships as a bare `.aar` dropped into the app module, and the plugin class is
app-local, so — as on iOS — Capacitor won't auto-register it.

1. Copy `android/app/libs/plaud-sdk.aar` into your `android/app/libs/`.
2. Copy `android/app/src/main/java/ai/plaud/pwademo/PlaudSdkPlugin.java` into your app's
   package directory and **change its `package` declaration** to match your `applicationId`.
3. Register it in `MainActivity`, **before** `super.onCreate()` (the analogue of iOS's
   `capacitorDidLoad()`); without this it surfaces as "PlaudSdk plugin is not implemented on
   android":

   ```java
   public class MainActivity extends BridgeActivity {
       @Override
       public void onCreate(Bundle savedInstanceState) {
           registerPlugin(PlaudSdkPlugin.class);
           super.onCreate(savedInstanceState);
       }
   }
   ```
4. Declare the `.aar`'s transitive dependencies in `android/app/build.gradle` and their
   versions in `android/variables.gradle`. **This is the step that is easy to miss**: a bare
   `.aar` carries no POM, so nothing comes along automatically and the app dies at runtime
   with `NoClassDefFoundError` the first time a missing code path runs. The full dependency
   block is in the reference. Note also that the stock template's
   `fileTree(include: ['*.jar'], dir: 'libs')` must become `['*.jar', '*.aar']` or the
   `.aar` is never picked up at all.

**No AndroidManifest edits are needed for Bluetooth.** `plaud-sdk.aar` ships its own manifest
declaring `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE/COARSE_LOCATION` and the WiFi
permissions, and the Gradle manifest merger folds them into the app. The *runtime* permission
prompt is handled inside `startScan()` by the plugin itself.

Full details for both platforms — `Package.swift`, the manual-registration mechanics on each
side, `capacitor.config.json` fields, the complete Gradle dependency block, and why each
piece exists — are in
**[references/capacitor-plugin-setup.md](references/capacitor-plugin-setup.md)**.

### Step 3 — Call the SDK from your web app

Copy **[references/plaud-sdk.ts](references/plaud-sdk.ts)** into your web app's `lib/`. It's
a typed wrapper around `registerPlugin<PlaudSdkPlugin>("PlaudSdk")` plus two CORS-workaround
helpers (`readExportedFile`, `putBinaryNative`). Then import and use it:

```typescript
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import { PlaudSdk, readExportedFile, type PlaudScanDevice, type PlaudFile } from "@/lib/plaud-sdk";

// Native code only exists inside the Capacitor shell — guard every call:
if (Capacitor.isNativePlatform()) {
  await PlaudSdk.initSDK({ userAccessToken: token, customDomain: "platform-us.plaud.ai", userId });
  await PlaudSdk.startScan();
}
```

**This code is identical on iOS and Android** — no `Capacitor.getPlatform()` branching. Even
Android's runtime Bluetooth permissions are requested by `startScan()` itself, so the JS flow
is the same on both.

The complete JS API (methods + events), the scan → connect → export flow, and the
non-obvious remote-origin / CORS constraints are in
**[references/plaud-sdk.ts](references/plaud-sdk.ts)** and
**[references/usage-example.md](references/usage-example.md)**.

## Key things that trip people up

- **Remote origin, not bundled assets.** `server.url` (set in `capacitor.config.ts`) points
  at the deployed web app. Web changes are only visible on device **after you deploy them** —
  `npx cap sync` doesn't bundle your web code. Same on both platforms.
- **Config lives in `capacitor.config.ts`.** The native `capacitor.config.json` files (one
  per platform) are generated by `npx cap sync`; edit the root `.ts` or your changes get
  overwritten.
- **Native changes need a native rebuild.** Editing `PlaudSdkPlugin.swift` /
  `MainViewController.swift` requires `npx cap sync ios` + rebuild in Xcode; editing
  `PlaudSdkPlugin.java` / `MainActivity.java` / Gradle files requires `npx cap sync android`
  + rebuild in Android Studio. A web deploy alone won't pick either up.
- **Run on physical hardware.** The iOS frameworks are arm64 device-only and won't link for
  the Simulator; the Android emulator has no Bluetooth radio, so a scan there finds nothing.
- **`fetch()` to file:// or S3 URLs fails on both platforms.** Because the WebView loads a
  remote origin, browser `fetch` of exported files (`convertFileSrc`) or `PUT` to S3
  presigned URLs is blocked by CORS. Use the native-bridge helpers `readExportedFile()` /
  `putBinaryNative()`.
- **`customDomain` is domain-only** — `platform-us.plaud.ai`, no `https://` prefix.
- **Recording is device-driven.** There are no start/stop-record JS methods; recording is
  triggered by the physical device and surfaced as `recordStart`/`recordStop`/`recordPause`/
  `recordResume` events. Refresh the file list after a stop.
- **Android: the missing Gradle dependencies.** By far the most common Android failure is a
  runtime `NoClassDefFoundError` from skipping step 2b.4, or an unresolved `PlaudSdkPlugin`
  because `fileTree` still says `['*.jar']`.

## Platform differences

The JS surface is the same, but a few values can't be made identical. All of these are
documented on the types in `references/plaud-sdk.ts`:

- **Device identity.** iOS reports a CoreBluetooth peripheral UUID; Android has no such
  handle and reports the MAC address. Android emits the MAC as `uuid` *and* as an extra
  `macAddress` field, so `connectBleDevice({ uuid })` works unchanged on both. Treat `uuid`
  as an opaque token.
- **`supportWiFi`.** Comes from the iOS scan record; Android's scan record has no such flag,
  so it stays `false` there until a device is connected.
- **`penState`.** iOS delivers seven values, Android four — `findMyToken`, `hasSndpKey` and
  `deviceAccessToken` are simply absent on Android (optional in the shared type) rather than
  faked as zeros.
- **`PlaudFile.duration`.** On Android it's derived from raw-opus frame arithmetic, so for
  `isOgg` recordings it reads slightly long (ogg page headers aren't subtracted).
- **Export directory.** iOS writes to `Documents/PlaudExports`, Android to
  `files/PlaudExports`. Either way, use the `outputPath` returned by `exportAudio`.
- **Android-only diagnostic events.** `connectFail`, `connectStage`, `handshakeWaitSure`,
  `btStatus` and `scanFail` exist only on Android — the Android SDK collapses every failure
  into `connectState(failed: true)`, so the plugin attaches a raw transport listener to
  recover the reason. They never fire on iOS; treat them as optional debugging detail.

## Extending the plugin

To expose a native SDK feature that isn't on the JS surface yet, mirror the existing pattern
end to end **on both platforms**, so the shared JS surface stays at parity:

- **iOS** — add a `CAPPluginMethod` entry + `@objc func` in
  `ios/PlaudPlugin/Sources/PlaudPlugin/PlaudSdkPlugin.swift`, and forward SDK delegate
  callbacks via `notifyListeners`.
- **Android** — add an `@PluginMethod public void` in
  `android/app/src/main/java/…/PlaudSdkPlugin.java`, and forward
  `PlaudDeviceAgentListener` callbacks via `notifyListeners` (the plugin's `emit()` helper
  hops to the main thread first). If the feature needs a new runtime permission, add it to
  the `@CapacitorPlugin(permissions = …)` annotation and request it in the method.
- **JS** — add the matching method signature / `addListener` overload and payload type in
  `plaud-sdk.ts`.

Both native plugins wrap the same facade — `PlaudDeviceAgent` and its listener callbacks —
so a feature that exists on one side almost always has a same-named counterpart on the other.
Read the two plugin sources side by side; each documents its platform-specific deviations
inline at the call site.
