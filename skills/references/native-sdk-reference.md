# Native Plaud SDK reference (for extending the plugin)

Read this when you need to expose a Plaud SDK feature that `PlaudSdk` doesn't surface yet.
The plugin (`PlaudSdkPlugin.swift`) is a thin bridge over Plaud's high-level facade,
`PlaudDeviceAgent`. To add a feature you mirror the pattern end to end:

1. add a `CAPPluginMethod` + `@objc func` in `PlaudSdkPlugin.swift` that calls the facade;
2. implement the relevant `PlaudDeviceAgentProtocol` delegate callback and forward it with
   `notifyListeners("<event>", data:)`;
3. add the matching method and `addListener` overload in `plaud-sdk.ts`.

Changing the native binary requires an Xcode rebuild + redeploy to a physical device — a
Vercel web deploy alone won't pick it up.

## The three frameworks

| Framework | Module | Role |
|-----------|--------|------|
| `PlaudDeviceBasicSDK` | `PlaudDeviceBasicSDK` | **High-level facade — the entry point.** Wraps BLE + WiFi + cloud behind `PlaudDeviceAgent`. Handles handshake, decryption, format conversion, credentials. |
| `PlaudBleSDK` | `PlaudBleSDK` | Low-level BLE transport, audio decode, crypto, model types (`BleDevice`, `BleFile`). Re-exported by the basic SDK. |
| `PlaudWiFiSDK` | `PlaudWiFiSDK` | WiFi fast-transfer transport (~10× BLE; needs Hotspot Configuration entitlement). |

`import PlaudDeviceBasicSDK` brings every public type into scope. Build against the facade
(`PlaudDeviceAgent`, `PlaudWiFiAgent`, `PlaudWorkflowManager`), not the low-level `BleAgent`.

## PlaudDeviceAgent — the facade the plugin uses

Singleton `PlaudDeviceAgent.shared`; set `.delegate` to receive events.

```swift
import PlaudDeviceBasicSDK

PlaudDeviceAgent.shared.delegate = self
PlaudDeviceAgent.shared.initSDK(
    userAccessToken: token,                  // per-user JWT
    customDomain: "platform-us.plaud.ai"     // domain only, no https://
)
PlaudDeviceAgent.shared.startScan()
```

### How the current JS methods map to the facade

| JS `PlaudSdk` method | Native facade call |
|----------------------|--------------------|
| `initSDK` | `initSDK(userAccessToken:customDomain:)` |
| `startScan` / `stopScan` | `startScan()` / `stopScan()` (plugin gates start on `BleAgent.shared.isPoweredOn`) |
| `connectBleDevice` | `connectBleDevice(bleDevice:deviceToken:)` (looks up the retained `BleDevice` by uuid) |
| `disconnect` | `disconnect()` |
| `depair` | `depair(clear:)` |
| `isConnected` | `isConnected()` |
| `getFileList` | `getFileList(startSessionId:)` |
| `exportAudio` | `exportAudio(sessionId:outputDir:format:channels:callback:)` |

### Other facade methods you might bridge next

```swift
// Recording (device also drives these itself):
func startRecord(); func stopRecord(); func pauseRecord(); func resumeRecord()
func checkIsRecording() -> Bool; func getCurrentSessionID() -> Int

// Device state & settings:
func getState(); func getStorage(); func getChargingState()
func setMicGain(value: Int); func readMicGain(); func setDeviceName(_ name: String)
func restoreFactory()

// Files:
func getFile(sessionId: Int); func deleteFile(sessionId: Int); func clearAllFiles()
func syncFile(sessionId:start:end:); func stopSyncFile()

// Lower-level download (progress via bleDownloadFile delegate):
func downloadFile(sessionId:desiredOutputPath:format:)   // PlaudDownloadFormat: .pcm/.wav

// WiFi fast transfer (see PlaudWiFiAgent):
func setDeviceWiFi(open: Bool); func endWiFiTransfer()

// Firmware / OTA:
func checkFirmwareUpdate(completion:); func startFirmwareUpdate(progress:completion:)
```

## PlaudDeviceAgentProtocol — delegate callbacks → forward as events

Conform to `PlaudDeviceAgentProtocol`. Only `blePenState` is required; the rest are `@objc
optional`. The plugin already forwards the ones below; add more the same way.

```swift
// REQUIRED
func blePenState(state:privacy:keyState:uDisk:findMyToken:hasSndpKey:deviceAccessToken:)

// Connection / scan  → "connectState", "scanResult", "scanTimeout", "bind"
optional func bleConnectState(state: Int)          // 1 = connected, 0 = disconnected, {2,-1,-2} = failed
optional func bleScanResult(bleDevices: [BleDevice])
optional func bleScanOverTime()
optional func bleBind(sn:status:protVersion:timezone:)

// Files  → "fileList"
optional func bleFileList(bleFiles: [BleFile])
optional func bleDownloadFile(sessionId:desiredOutputPath:status:progress:tips:)

// Recording (device-initiated)  → "recordStart"/"recordStop"/"recordPause"/"recordResume"
optional func bleRecordStart(sessionId:start:status:scene:startTime:reason:)
optional func bleRecordStop(sessionId:reason:fileExist:fileSize:)
optional func bleRecordPause(sessionId:reason:fileExist:fileSize:)
optional func bleRecordResume(sessionId:start:status:scene:startTime:)

// Device status  (not yet bridged — candidates for new events)
optional func bleStorage(total:free:duration:)
optional func blePowerChange(power:oldPower:)
optional func bleChargingState(isCharging:level:)
optional func blePcmData(sessionId:millsec:pcmData:isMusic:)   // live waveform

// Unpair  → "depair"
optional func bleDepair(_ status: Int)
```

The plugin's forwarding pattern (from `PlaudSdkPlugin.swift`):

```swift
public func bleFileList(bleFiles: [BleFile]) {
    let files = bleFiles.map { f -> [String: Any] in
        ["sn": f.sn, "sessionId": f.sessionId, "size": f.size, "scenes": f.scenes,
         "channels": f.channels, "isOgg": f.isOgg, "isMusic": f.isMusic, "duration": f.duration()]
    }
    notify("fileList", ["files": files])   // notify() marshals to main + calls notifyListeners
}
```

## Audio export

```swift
func exportAudio(sessionId: Int, outputDir: String,
                 format: AudioExportFormat, channels: Int = 1,
                 callback: any AudioExportCallback)

@objc enum AudioExportFormat: Int { case pcm = 0, mp3 = 1, wav = 2, opus = 3 }

@objc protocol AudioExportCallback {
    func onProgress(_ progress: Int, message: String)
    func onComplete(outputPath: String)
    func onError(_ error: String)
}
```

The plugin adapts `AudioExportCallback` per call: `onProgress` → `exportProgress` event,
`onComplete`/`onError` → resolve/reject the originating `CAPPluginCall`. Use `.mp3` — it's
playable by `AVAudioPlayer` and accepted by the transcription upload.

## Key model types

```swift
class BleDevice {                     // a scanned/connected device
    var name, uuid, serialNumber: String
    var rssi: Float
    var supportWiFi, isCharging: Bool
    var total, free, duration: Int    // storage
    // …
}

class BleFile {                       // a recording on the device
    var sn: String
    var sessionId, size, scenes, channels: Int
    var isOgg, isMusic: Bool
    func duration() -> Int            // seconds
}
```

Supported devices by SN prefix: `881` = NotePro, `883` = NotePinS.

## Notes / gotchas

- Frameworks are **arm64 device builds** — no iOS Simulator.
- SDK callbacks arrive on the SDK's dispatch queues, **not** the main thread. Marshal to main
  before touching UIKit or calling `notifyListeners` (the plugin's `notify()` helper does this).
- With the per-user JWT `initSDK` flow, the facade drives the partner `sn-sign` handshake and
  caches signatures automatically — you don't call the partner auth endpoints yourself for
  normal connect.
- End-to-end (E2EE) audio decryption uses an RSA key pair from the `gen-key` endpoint; the
  facade handles standard transport decryption for you.

For the exhaustive native surface — WiFi fast transfer (`PlaudWiFiAgent`), cloud workflows
(`PlaudWorkflowManager`), partner auth (`PlaudPartnerApiManager`), file upload
(`PlaudFileUploader`), encryption, OTA, and the full low-level `BleAgent` API — see the
complete generated reference in [`ios-sdk-reference.md`](ios-sdk-reference.md). Verify signatures
against the `.swiftinterface` files under each `*.framework/Modules/` before relying on them,
since the generated doc can drift.
