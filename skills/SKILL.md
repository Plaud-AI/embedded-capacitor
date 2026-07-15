---
name: plaud-embedded-capacitor-wrapper
description: Wrap an existing web app in a Capacitor iOS shell so it can talk to Plaud recording devices over Bluetooth via the Plaud Embedded SDK. Use this when a user has a web app (React/Next.js/etc.) that implements Plaud Embedded and wants to ship it as an iOS app, or wants to call the native PlaudSdk Capacitor plugin (scan, connect, list files, export/decode audio) from JavaScript.
---

# Plaud Embedded Capacitor Wrapper

iOS has **no Web Bluetooth**, so a browser-based web app can't reach Plaud recording
hardware directly. This skill wraps the web app in a **Capacitor native shell**: a thin iOS
app that loads the web app's URL in a `WKWebView` and exposes Plaud's precompiled iOS SDK to
the web layer through a custom Capacitor plugin (`PlaudSdk`). The web app calls
`PlaudSdk.*` in JavaScript; Capacitor serializes those calls across the bridge to native
Swift, which drives CoreBluetooth and the Plaud SDK.

```
┌──────────────────────────────────────────┐
│           Web App (JavaScript)            │
│                 PlaudSdk                  │
└──────────────────▲────────────────────────┘
                   │  Capacitor Bridge (JS ↔ IPC)
┌──────────────────▼────────────────────────┐
│        PlaudPlugin (Swift / Native)       │
│      BLE, Files, iOS APIs, Events         │
└───────────────────────────────────────────┘
```

## When to use this skill

Use it when the user already has a web app that implements
[Plaud Embedded](https://docs.plaud.ai/plaud-embedded) and wants to run it on iOS with real
device connectivity, or wants to add/extend `PlaudSdk` plugin calls in their web code.

If the user hasn't set up Plaud Embedded credentials or auth yet, they need the
`plaud-embedded-project-setup-skill` first. For pure audio transcription (no wrapper), see
`plaud-embedded-transcription-api-skill`.

### Prerequisites

- [ ] A working web app that implements Plaud Embedded, deployed to a public URL (the shell
      loads a **remote** origin, not bundled assets)
- [ ] Plaud Embedded credentials and a way to mint a per-user token (see the setup skill)
- [ ] macOS with Xcode and a **physical iPhone** — the Plaud frameworks are arm64
      device-only builds and do **not** run on the iOS Simulator

## Setup — three steps

Follow these in order. Each step has a detailed reference file; read the reference before
running the commands.

### Step 1 — Install and initialize Capacitor

```bash
npm i @capacitor/core @capacitor/ios @capacitor-community/bluetooth-le
npm i -D @capacitor/cli
npx cap init          # sets appId / appName / webDir
npx cap add ios       # scaffolds the ios/ project
npx cap sync ios
```

### Step 2 — Add the PlaudPlugin and wire it up

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
5. Point the shell at your deployed web app by setting `server.url` in the **root
   `capacitor.config.ts`** (the source of truth). `npx cap sync` regenerates
   `ios/App/App/capacitor.config.json` from it — editing that JSON directly gets overwritten.

Full details (Package.swift, the manual-registration mechanics, `capacitor.config.json`
fields, and why each piece exists) are in
**[references/capacitor-plugin-setup.md](references/capacitor-plugin-setup.md)**.

### Step 3 — Call the SDK from your web app

Copy **[references/plaud-sdk.ts](references/plaud-sdk.ts)** into your web app's `lib/`. It's
a typed wrapper around `registerPlugin<PlaudSdkPlugin>("PlaudSdk")` plus two CORS-workaround
helpers (`readExportedFile`, `putBinaryNative`). Then import and use it:

```typescript
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import { PlaudSdk, readExportedFile, type PlaudScanDevice, type PlaudFile } from "@/lib/plaud-sdk";

// Native code only exists inside the iOS shell — guard every call:
if (Capacitor.isNativePlatform()) {
  await PlaudSdk.initSDK({ userAccessToken: token, customDomain: "platform-us.plaud.ai", userId });
  await PlaudSdk.startScan();
}
```

The complete JS API (methods + events), the scan → connect → export flow, and the
non-obvious remote-origin / CORS constraints are in
**[references/plaud-sdk-js-api.md](references/plaud-sdk-js-api.md)** and
**[references/usage-example.md](references/usage-example.md)**.

## Key things that trip people up

- **Remote origin, not bundled assets.** `server.url` (set in `capacitor.config.ts`) points
  at the deployed web app. Web changes are only visible on device **after you deploy them** —
  `npx cap sync` doesn't bundle your web code.
- **Config lives in `capacitor.config.ts`.** The native `capacitor.config.json` is generated
  by `npx cap sync`; edit the root `.ts` or your changes get overwritten.
- **Swift changes need an Xcode rebuild.** Editing the plugin or `MainViewController` requires
  `npx cap sync ios` + rebuild on device; a web deploy alone won't pick them up.
- **`fetch()` to file:// or S3 URLs fails.** Because the WebView loads a remote origin,
  browser `fetch` of exported files (`convertFileSrc`) or `PUT` to S3 presigned URLs is
  blocked by CORS. Use the native-bridge helpers `readExportedFile()` / `putBinaryNative()`.
- **`customDomain` is domain-only** — `platform-us.plaud.ai`, no `https://` prefix.
- **Recording is device-driven.** There are no start/stop-record JS methods; recording is
  triggered by the physical device and surfaced as `recordStart`/`recordStop`/`recordPause`/
  `recordResume` events. Refresh the file list after a stop.

## Extending the plugin

To expose a native SDK feature that isn't on the JS surface yet, mirror the existing pattern
end to end: add a `CAPPluginMethod` + `@objc func` in `PlaudSdkPlugin.swift`, forward any
SDK delegate callbacks via `notifyListeners`, and add the matching method/event types in
`plaud-sdk.ts`. The underlying native facade (`PlaudDeviceAgent`) and its delegate callbacks
are summarized in **[references/native-sdk-reference.md](references/native-sdk-reference.md)**.
