# Plaud PWA Demo

A Next.js web app that talks to Plaud recording hardware over Bluetooth. Because iOS has
no Web Bluetooth, the web app is wrapped in a thin [Capacitor](https://capacitorjs.com)
native shell, and Plaud's precompiled **native iOS SDK** is exposed to the web layer
through a custom Capacitor plugin.

---

## 1. Why a native shell at all

Web Bluetooth (`navigator.bluetooth`) does not exist in any iOS browser or home-screen
PWA — every iOS web context runs on WebKit, which does not implement it. To get Bluetooth
on iPhone we wrap the web app in a Capacitor native shell that loads the live Vercel URL
in a `WKWebView` and injects a **bridge** so JavaScript can call native code.

Capacitor is configured to load the remote site rather than bundled assets:

```ts
// capacitor.config.ts
const config: CapacitorConfig = {
  appId: 'ai.plaud.pwademo',
  appName: 'Plaud PWA Demo',
  // Required by Capacitor even when loading a remote URL; ignored at runtime
  // because server.url is set.
  webDir: 'public',
  server: {
    url: 'https://plaud-capacitor-demo.vercel.app',
    cleartext: false,
  },
};
```

> **Consequence of `server.url`:** the app runs whatever web build is currently deployed
> to Vercel. **Deploy web changes before testing on device.** Native (Swift) changes, by
> contrast, only take effect when you rebuild the app in Xcode.

---

## 2. How Plaud's native SDK fits into Capacitor

Plaud ships three precompiled Swift/ObjC frameworks (arm64 **device-only** — no Simulator):

| Framework | Role |
|-----------|------|
| `PlaudDeviceBasicSDK` | High-level facade (`PlaudDeviceAgent`) — the entry point we use. |
| `PlaudBleSDK` | Low-level BLE transport, crypto, audio decode, model types. |
| `PlaudWiFiSDK` | WiFi fast-transfer transport. |

These own their own `CBCentralManager` and the entire device conversation. Our job is only
to bridge them to the WebView. The data path:

```
   Web app (React, served from Vercel)
        │  import { PlaudSdk } from "lib/plaudSdk"   (registerPlugin("PlaudSdk"))
        │  PlaudSdk.initSDK({...}); PlaudSdk.startScan()
        ▼
   Capacitor bridge  (injected into the WKWebView, works for remote URLs too)
        │  marshals the call across the JS↔native boundary
        ▼
   PlaudSdkPlugin.swift   (CAPPlugin, in the local PlaudPlugin SwiftPM package)
        │  calls the facade + conforms to PlaudDeviceAgentProtocol
        ▼
   PlaudDeviceAgent.shared   (PlaudDeviceBasicSDK.framework)
        │  scan / handshake / connect / sync / decode over CoreBluetooth
        ▼
   Plaud device
```

Callbacks flow back the other way: the SDK invokes `PlaudDeviceAgentProtocol` delegate
methods on the plugin, which forwards them to JS as Capacitor plugin **events**
(`notifyListeners`), consumed in React via `PlaudSdk.addListener(...)`.

### 2.1 How a Capacitor plugin extends a web app

Capacitor's whole value proposition is: write the UI once in web tech, and for anything the
WebView can't do natively (Bluetooth, filesystem, camera, etc.), expose a small native API
surface that the web code calls like a regular async JS function. The general shape, on both
sides:

- **JS side** — `registerPlugin<T>("PluginName")` (from `@capacitor/core`) returns a proxy
  object typed by an interface you define. Every method call on that proxy is intercepted by
  the Capacitor JS runtime, serialized, and sent across the bridge Capacitor injects into the
  `WKWebView`. That injection happens the same way whether the page is loaded from bundled
  `file://` assets or — as in this app — a remote HTTPS origin, which is exactly what lets
  `PlaudSdk` work even though `capacitor.config.ts` points at the deployed Vercel URL.
- **Native side** — a Swift class subclasses `CAPPlugin` and conforms to `CAPBridgedPlugin`,
  declaring an `identifier`/`jsName` (must match the string passed to `registerPlugin`) and a
  `pluginMethods` array mapping method names to `@objc` functions. When a call arrives from
  JS, the bridge looks up the matching `CAPPluginMethod`, invokes it with a `CAPPluginCall`
  holding the JS arguments, and the Swift code calls `call.resolve(...)` / `call.reject(...)`
  — which settles the Promise that the JS-side proxy call returned.
- **Events flow the other way** — the native class can push data to JS at any time via
  `notifyListeners(eventName, data:)`, independent of any in-flight call. On the JS side,
  `PluginProxy.addListener(eventName, callback)` subscribes. This is how one-shot device SDK
  delegate callbacks (e.g. Plaud's `PlaudDeviceAgentProtocol`) become a stream of events
  (`scanResult`, `connectState`, `fileList`, `exportProgress`, `recordStart`, …) that React
  state can subscribe to.

`PlaudSdk` is one instance of this pattern: `lib/plaud-sdk.ts` defines the JS-side interface
and calls `registerPlugin`, while `PlaudSdkPlugin.swift` (§3.2) is the native `CAPPlugin`
subclass that implements it and forwards Plaud SDK callbacks as events. The same
call/resolve/event mechanism is also reused for two smaller, single-purpose bridge methods —
`readFile` and `putBinary` (§4.1) — that exist purely to route around `WKWebView` CORS
restrictions when the app is loaded from a remote origin, which shows the plugin mechanism is
general-purpose, not just a BLE-specific trick.

### 2.2 Packaging the frameworks — `ios/PlaudPlugin/`

The native code lives in a **local SwiftPM package**, `ios/PlaudPlugin/`, mirroring how
`bluetooth-le` is structured. This is the cleanest, most `cap sync`-safe approach:

- The three `.framework`s were converted to `.xcframework`s and declared as SwiftPM
  **binary targets**. SwiftPM then embeds **and code-signs** them into `App.app/Frameworks/`
  automatically — no fragile hand-maintained "Embed Frameworks" build phase.

  ```bash
  # how the xcframeworks in ios/PlaudPlugin/Frameworks/ were produced:
  xcodebuild -create-xcframework -framework ios/PlaudBleSDK.framework \
    -output ios/PlaudPlugin/Frameworks/PlaudBleSDK.xcframework
  # (repeated for PlaudWiFiSDK and PlaudDeviceBasicSDK)
  ```

- `ios/PlaudPlugin/Sources/PlaudPlugin/PlaudSdkPlugin.swift` is the bridge class. It
  subclasses `CAPPlugin`, conforms to `CAPBridgedPlugin` (declares `jsName`/`identifier`
  and the callable `pluginMethods`), and conforms to `PlaudDeviceAgentProtocol` to receive
  device events. Current surface:
  - **Methods:** `initSDK`, `startScan`, `stopScan`, `connectBleDevice`, `disconnect`,
    `depair`, `isConnected`, `getFileList`, `exportAudio`.
  - **Events:** `scanResult`, `scanTimeout`, `connectState`, `penState`, `bind`,
    `fileList`, `exportProgress`, `depair`.

  Patterns worth noting:
  1. `connectBleDevice` retains the `BleDevice` objects handed to us during a scan and looks
     one up by `uuid`/`serialNumber`, because JS can only pass identifiers, not the native
     object the SDK requires.
  2. `exportAudio` bridges the SDK's per-call `AudioExportCallback` — progress becomes an
     `exportProgress` event and completion/error resolves/rejects the promise with the
     written file path (under `Documents/PlaudExports/`). **`exportAudio` is self-contained:
     it downloads the recording from the connected device, decodes the proprietary
     AVC/OGG-Opus, and converts to your chosen format (mp3/wav/pcm/opus) in one call** — you
     do *not* need a separate `syncFile`/`downloadFile` step. It does require an active,
     handshake-complete connection; if the download comes back empty (e.g. auth incomplete
     or an E2EE recording without the key) it errors instead of writing a file.
  3. `depair(clear: true)` unpairs the device *and* clears local pairing/binding state, so
     the next `connectBleDevice` runs a fresh handshake. The result arrives via the `depair`
     event's `status`.

The package is attached to the App target in `ios/App/App.xcodeproj/project.pbxproj` exactly
the way `CapApp-SPM` is (a local package reference + product dependency). `npx cap sync`
manages only `CapApp-SPM`, so these edits survive syncs.

### 2.3 Registering the plugin — the non-obvious part

**Capacitor 8 does not scan the runtime for plugins.** `CapacitorBridge.registerPlugins()`
reads `capacitor.config.json` → `packageClassList` and registers each class *by name*. The
Capacitor CLI only populates that list from **npm-installed** plugins:

```json
"packageClassList": [ "BluetoothLe" ]
```

Our `PlaudSdk` lives in a **local** package the CLI knows nothing about, so it is absent from
that list. The symptom is a runtime error in JS:

> `"PlaudSdk" plugin is not implemented on ios`

(The class *is* compiled and linked into the app — this is purely a registration gap.)

The fix is to register the instance manually in Capacitor's `capacitorDidLoad()` hook, which
runs right after auto-registration and before the web content loads. We do this with a
`CAPBridgeViewController` subclass:

```swift
// ios/App/App/MainViewController.swift
import Capacitor
import PlaudPlugin

class MainViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(PlaudSdkPlugin())
    }
}
```

`registerPluginInstance` (unlike `registerPluginType`) registers regardless of the config
list. `Main.storyboard`'s Bridge View Controller is pointed at this subclass
(`customClass="MainViewController" customModule="App"`) so it is actually used.

> **Rule of thumb:** an npm Capacitor plugin auto-registers via `packageClassList`; a local
> plugin must be registered by hand in `capacitorDidLoad()`.

---

## 3. The web side

- **`lib/plaud-sdk.ts`** — `registerPlugin<PlaudSdkPlugin>("PlaudSdk")` with typed methods and
  event listeners. In a plain browser (no native shell) these calls reject with
  "not implemented", so guard with `Capacitor.isNativePlatform()`. It also exports two
  helpers, `readExportedFile()` and `putBinaryNative()`, that route file reads and S3 PUTs
  through native plugin methods (`readFile`, `putBinary`) instead of browser `fetch()` —
  see §4.1 for why.
- **`app/page.tsx`** — the full demo flow: mint a per-user JWT from `/api/user-token`, call
  `initSDK({ userAccessToken, customDomain: "platform-us.plaud.ai" })` → `startScan()` → tap a
  device to `connectBleDevice` → on `connectState`, `getFileList` → tap a recording to
  `exportAudio` (with live `exportProgress`), which then automatically kicks off the upload +
  transcription flow below (rendered in `app/FileModal.tsx`). It also listens for
  device-initiated `recordStart`/`recordStop`/`recordPause`/`recordResume` events (recording
  is triggered by the physical device, not the app) and refreshes the file list after a stop.
  An **Unpair** button (confirm-guarded, since it is destructive) calls `depair({ clear: true })`.
- **`app/api/user-token/route.ts` + `lib/plaud-auth.ts`** — mint the per-user access token the
  SDK needs for its handshake (partner OAuth → user token). `customDomain` is **domain-only**, no
  `https://`.

### 3.1 Upload + transcription, after `exportAudio`

`exportAudio` only writes the decoded mp3 to the device's local filesystem
(`Documents/PlaudExports/`) — it isn't reachable by Plaud's Transcription API, which requires a
public download URL. So each export is pushed through Plaud's **File Upload API** to get one,
then handed to the **Transcription API**.

Because `capacitor.config.ts` points the WKWebView at the **remote** Vercel origin (not
`file://` bundled assets), plain browser `fetch()` can't touch these bytes: a `capacitor://…/
_capacitor_file_/…` URL from `Capacitor.convertFileSrc()` is a cross-origin custom scheme that
WKWebView's CORS check blocks, and a browser `PUT` straight to the S3 presigned URL is blocked
the same way (and even if it weren't, reading the `ETag` response header back would need the
bucket's CORS config to expose it). Both problems are solved by routing through **native**
requests instead of `fetch()` — two extra `PlaudSdk` plugin methods, `readFile` and `putBinary`,
issue the read/PUT from Swift and hand the result back to JS:

```
exportAudio() → local file
     │  readExportedFile(outputPath)  — PlaudSdk.readFile() reads the bytes natively,
     │                                   base64-decoded back into an ArrayBuffer in JS
     ▼
app/transcription-runner.ts  transcribeExportedFile()
     │  1. POST /api/transcription/presign   → chunked S3 presigned PUT URLs (5 MB parts)
     │  2. putBinaryNative() per chunk — PlaudSdk.putBinary() PUTs to S3 via URLSession,
     │                                    returning the response status and `ETag` directly
     │  3. POST /api/transcription/complete  → finalizes the multipart upload, returns a
     │                                          `DownloadUrl` (valid 24h)
     │  4. POST /api/transcription/submit    → submits DownloadUrl, returns a transcription_id
     │  5. poll GET /api/transcription/status/[id] every 5s until SUCCESS/FAILURE/REVOKED
     ▼
transcript text, shown in the playback modal (app/FileModal.tsx)
```

- **`lib/plaud-transcription.ts`** — server-only wrapper around Plaud's File Upload API
  (`generatePresignedUploadUrls`, `completeMultipartUpload`) and Transcription API
  (`submitTranscription`, `getTranscriptionTask`).
- **`app/api/transcription/{presign,complete,submit,status/[id]}/route.ts`** — thin proxy routes.
  They exist so the two different Plaud credential types never reach the client: file upload
  uses the same per-user Bearer token as the SDK (`access_token`, passed up from the client,
  which already holds it for `initSDK`), while transcription submit/status use partner client
  credentials (`X-Client-Id` / `X-Client-Api-Key`, from `PLAUD_CLIENT_ID` / `PLAUD_API_KEY`) that
  must stay server-side.
- **`app/transcription-runner.ts`** — client-side orchestration (`"use client"`). It's the only
  piece that touches the raw file bytes (the API routes never see them — multipart PUTs go
  straight from native code to the presigned URLs), and it drives the presign → upload →
  complete → submit → poll sequence. It lives under `app/`, not `lib/`, on purpose: `lib/` is
  reserved for server-only modules (`plaud-auth.ts`, `plaud-transcription.ts`), so mixing this
  client-only orchestrator in there would blur which files are safe to import from a Server
  Component vs. which assume a browser/native runtime.
- Region: currently hardcoded to `https://platform-us.plaud.ai/developer/api` in
  `lib/plaud-auth.ts` (`BASE_URL`, shared by both the OAuth and transcription helpers). Switch
  it if you need the Japan deployment (`platform-jp.plaud.ai`); EU/Singapore aren't available yet.

---

## 4. Build & run

Native SDK frameworks are **arm64 device builds**, so everything must run on a **physical
iPhone** (never the Simulator).

```bash
npx cap sync ios     # after any web/plugin/config change
npx cap open ios     # opens Xcode
```

In Xcode: set a signing **Team**, select your device, and Run. Because `server.url` points at
Vercel, make sure the web changes you want to test are **deployed** first. If you change Swift
code (the plugin, `MainViewController`), you must rebuild the app in Xcode — a Vercel deploy
alone won't pick it up.

Required `Info.plist` keys (already set in `ios/App/App/Info.plist`):

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Uses Bluetooth to connect and interact with peripheral BLE devices.</string>
<key>UIBackgroundModes</key>            <!-- only needed for BLE while backgrounded -->
<array><string>bluetooth-central</string></array>
```

> WiFi fast transfer (`PlaudWiFiAgent`) additionally requires the **Hotspot Configuration**
> entitlement — not enabled yet, since the plugin currently covers BLE scan/connect, file
> listing, on-device export, and unpair (no WiFi path).

---

## 5. File map

| Path | Purpose |
|------|---------|
| `capacitor.config.ts` | Loads remote Vercel URL, injects bridge. |
| `ios/PlaudPlugin/Package.swift` | Local SwiftPM package: Plaud xcframeworks + plugin. |
| `ios/PlaudPlugin/Frameworks/*.xcframework` | Plaud SDK, packaged for SwiftPM embedding. |
| `ios/PlaudPlugin/Sources/PlaudPlugin/PlaudSdkPlugin.swift` | The JS↔SDK bridge (`PlaudSdk`). |
| `ios/App/App/MainViewController.swift` | Registers the local plugin (`capacitorDidLoad`). |
| `ios/App/App/Base.lproj/Main.storyboard` | Points the bridge VC at `MainViewController`. |
| `ios/App/App.xcodeproj/project.pbxproj` | Attaches `PlaudPlugin` to the App target. |
| `lib/plaud-sdk.ts` | Typed JS wrapper (`registerPlugin`) + native-bridge file/upload helpers. |
| `app/page.tsx` | Demo UI: init → scan → connect → list → export, plus unpair. |
| `app/FileModal.tsx` | Playback + transcript modal for a selected recording. |
| `lib/plaud-transcription.ts` | Server-only File Upload API + Transcription API calls. |
| `app/api/transcription/*/route.ts` | Proxy routes so upload/transcription credentials stay server-side. |
| `app/transcription-runner.ts` | Client-side presign → native S3 upload → complete → submit → poll orchestration. |

---

## 6. Extending the plugin

To add device features (e.g. `connectBleDevice`, `getFileList`, `exportAudio`), follow the
same pattern in `PlaudSdkPlugin.swift`:

1. Add a `CAPPluginMethod(name:...)` entry to `pluginMethods` and an `@objc func` handler.
2. For SDK results delivered via `PlaudDeviceAgentProtocol`, implement the delegate method and
   forward it with `notifyListeners(event, data:)`.
3. Mirror the new method/event in `lib/plaud-sdk.ts`.
4. Verify signatures against the frameworks' real `.swiftinterface` files (under each
   `*.framework/Modules/*.swiftmodule/arm64-apple-ios.swiftinterface`), not just
   `ios-sdk-reference.md` — the doc is generated and can drift.

Remember: adding or renaming a plugin method changes the native binary, so it needs an Xcode
rebuild + redeploy to the device, not just a Vercel deploy.
