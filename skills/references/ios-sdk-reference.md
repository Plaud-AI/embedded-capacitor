# Plaud iOS SDK Reference

This document describes the **public API** of the precompiled Plaud iOS SDK frameworks
shipped in `sdk/ios/`. It was generated from the frameworks' `.swiftinterface` files, so
every symbol below is callable from a host app that links these frameworks.

There are three frameworks plus one resource bundle:

| Framework | Module | Role |
|-----------|--------|------|
| `PlaudDeviceBasicSDK.framework` | `PlaudDeviceBasicSDK` | **High-level facade.** The recommended entry point. Wraps BLE + WiFi + cloud workflows behind one agent. |
| `PlaudBleSDK.framework` | `PlaudBleSDK` | Low-level BLE transport, audio decoding, crypto, model types. Re-exported by the basic SDK. |
| `PlaudWiFiSDK.framework` | `PlaudWiFiSDK` | Low-level WiFi fast-transfer transport. |
| `PlaudDeviceBasicSDK.bundle` | — | Localized strings + assets (resource bundle, no code). |

`PlaudDeviceBasicSDK` `@_exported`-imports both `PlaudBleSDK` and `PlaudWiFiSDK`, so
`import PlaudDeviceBasicSDK` brings every public type below into scope.

> **Recommendation:** Build against `PlaudDeviceAgent` (and `PlaudWiFiAgent` /
> `PlaudWorkflowManager`) from `PlaudDeviceBasicSDK`. The lower-level `BleAgent` and
> `WiFiAgent` classes are public but are intended for advanced use; the facade handles the
> handshake, decryption, format conversion, and credential plumbing for you.

---

## Table of contents

1. [PlaudDeviceAgent — main facade](#1-plauddeviceagent--main-facade)
2. [PlaudDeviceAgentProtocol — delegate callbacks](#2-plauddeviceagentprotocol--delegate-callbacks)
3. [Audio export & download](#3-audio-export--download)
4. [WiFi fast transfer (PlaudWiFiAgent)](#4-wifi-fast-transfer-plaudwifiagent)
5. [Firmware / OTA update](#5-firmware--ota-update)
6. [Cloud AI — PlaudWorkflowManager & transcription](#6-cloud-ai--plaudworkflowmanager--transcription)
6a. [Partner device authentication (sn-sign / gen-key / sn-verify)](#6a-partner-device-authentication-sn-sign--gen-key--sn-verify)
7. [File upload & device binding (PlaudFileUploader)](#7-file-upload--device-binding-plaudfileuploader)
8. [Domain / region & localization](#8-domain--region--localization)
9. [Encryption & end-to-end audio decryption](#9-encryption--end-to-end-audio-decryption)
10. [Model types & enums](#10-model-types--enums)
11. [Low-level: PlaudBleSDK.BleAgent](#11-low-level-plaudblesdkbleagent)
12. [Low-level: PlaudWiFiSDK](#12-low-level-plaudwifisdk)
13. [Audio utilities (decoders, waveform, players)](#13-audio-utilities-decoders-waveform-players)
14. [Logging](#14-logging)

---

## 1. PlaudDeviceAgent — main facade

`PlaudDeviceBasicSDK.PlaudDeviceAgent` is a singleton (`PlaudDeviceAgent.shared`) and the
primary object you interact with. Set its `delegate` to receive device events (see
[section 2](#2-plauddeviceagentprotocol--delegate-callbacks)).

```swift
import PlaudDeviceBasicSDK

PlaudDeviceAgent.shared.delegate = self
PlaudDeviceAgent.shared.initSDK(
    userAccessToken: token,           // per-user JWT
    customDomain: "platform-us.plaud.ai"   // domain only, no https://
)
PlaudDeviceAgent.shared.startScan()
```

### Properties

| Property | Type | Notes |
|----------|------|-------|
| `static let shared` | `PlaudDeviceAgent` | Singleton. |
| `var bleAgent` | `BleAgent?` | Underlying low-level BLE agent (escape hatch). |
| `var recentConnectDevice` | `BleDevice?` | Last device that was connected. |
| `var sceneFlag` | `Int` (get) | Current recording scene. |
| `var isWiFiTransferActive` | `Bool` (get) | True while a WiFi fast transfer is in progress. |
| `var skipPermissionCheck` | `Bool` | Bypass the SDK's permission gate. |
| `weak var delegate` | `PlaudDeviceAgentProtocol?` | Event sink. |

### Initialization & credentials

```swift
// Per-user JWT flow (recommended):
func initSDK(userAccessToken: String, customDomain: String, extra: [String:String] = [:])

// Partner app-key flow:
func initSDK(hostName: String, appKey: String, appSecret: String,
             bindToken: String = "", extra: [String:String] = [:],
             customDomain: String? = nil, partnerToken: String? = nil)

func setUserAccessToken(_ token: String?)
@available(*, deprecated, renamed: "setUserAccessToken")
func setPartnerToken(_ token: String?)

func getPartnerApiManager() -> PlaudPartnerApiManager
func isPartnerDataReady() -> Bool
func clearSDKCredentials()

static func getTestAppKey(_ beta: Bool = false) -> String
static func getTestAppSecret(_ beta: Bool = false) -> String
```

### Scan / connect / bind

```swift
func startScan()
func stopScan()
func isConnected() -> Bool
func connectBleDevice(bleDevice: BleDevice, deviceToken: String)
func connectBleDevice(bleDevice: BleDevice)
func disconnect()
func tryReconnectLastDevice()
func depair(clear: Bool = false)
func setDeviceBinding(token: String)
func setDeviceName(_ name: String)
func setDeviceActive(status: Int)
```

### Device state & settings

```swift
func getState()
func getStorage()
func getChargingState()
func setMicGain(value: Int)
func readMicGain()
func setUDiskMode(onOff: Bool)
func restoreFactory()
func reportDeviceMetadata()
```

### Recording

```swift
func checkIsRecording() -> Bool
func startRecord()
func stopRecord()
func pauseRecord()
func resumeRecord()
func getCurrentSessionID() -> Int
```

### File listing, sync & delete

```swift
func getFileList(startSessionId: Int)
func getFile(sessionId: Int)
func syncFile(sessionId: Int, start: Int, end: Int)
func stopSyncFile()
func checkIsDownloading() -> Bool
func deleteFile(sessionId: Int)
func clearAllFiles()
```

See [section 3](#3-audio-export--download) for `downloadFile` / `exportAudio`.

### WiFi fast-transfer toggling

```swift
func setDeviceWiFi(open: Bool)   // ask device to open its hotspot
func endWiFiTransfer()
```

### "Sync when idle" WiFi config (device-side scheduled sync)

```swift
func getWifiSyncEnable()
func setWifiSyncEnable(value: Int)
func getWifiSyncConfig(wifiIndex: UInt32)
func setWifiSyncConfig(operation: Int, wifiIndex: UInt32, ssid: String, password: String)
func getWifiSyncList()
func deleteWifiSyncConfig(wifiIndices: [UInt32])
func setWifiSyncTest(wifiIndex: UInt32)
func getWifiSyncTestResult(wifiIndex: UInt32)
```

### Misc / advanced

```swift
func sendApiToken(token: String, callback: @escaping (Bool, String) -> Void)
func sendBinaryFile(type: Int, data: Data?, callback: @escaping (Bool, String) -> Void)
func checkSdkResource()
```

---

## 2. PlaudDeviceAgentProtocol — delegate callbacks

Conform to `PlaudDeviceAgentProtocol` and assign to `PlaudDeviceAgent.shared.delegate`.
Only `blePenState` is required; everything else is `@objc optional`.

### Connection / handshake

```swift
func blePenState(state: Int, privacy: Int, keyState: Int, uDisk: Int,
                 findMyToken: Int, hasSndpKey: Int, deviceAccessToken: Int)   // REQUIRED
optional func bleAppKeyState(result: Int)
optional func bleConnectState(state: Int)              // 1 = connected, 0 = disconnected
optional func bleScanResult(bleDevices: [BleDevice])
optional func bleScanOverTime()
optional func bleBind(sn: String?, status: Int, protVersion: Int, timezone: Int)
optional func bleDeviceName(name: String?)
```

### Device status

```swift
optional func bleStorage(total: Int, free: Int, duration: Int)
optional func blePowerChange(power: Int, oldPower: Int)
optional func bleChargingState(isCharging: Bool, level: Int)
optional func bleMicGain(_ value: Int)
optional func bleSetActive(status: Int)
optional func bleCommonSetting(setting: Int)
optional func bleRate(lossRate: Double, rate: Int, instantRate: Int)
```

### Recording

```swift
optional func bleRecordStart(sessionId: Int, start: Int, status: Int, scene: Int, startTime: Int, reason: Int)
optional func bleRecordStop(sessionId: Int, reason: Int, fileExist: Bool, fileSize: Int)
optional func bleRecordPause(sessionId: Int, reason: Int, fileExist: Bool, fileSize: Int)
optional func bleRecordResume(sessionId: Int, start: Int, status: Int, scene: Int, startTime: Int)
optional func blePcmData(sessionId: Int, millsec: Int, pcmData: Data, isMusic: Bool)   // live waveform
```

### File list / sync / download

```swift
optional func bleFileList(bleFiles: [BleFile])
optional func bleSyncFileHead(sessionId: Int, status: Int)
optional func bleSyncFileTail(sessionId: Int, crc: Int)
optional func bleData(sessionId: Int, start: Int, data: Data)
optional func bleDataComplete()
optional func bleDecodeFail(start: Int)
optional func bleSyncFileStop()
optional func bleDownloadFile(sessionId: Int, desiredOutputPath: String, status: Int, progress: Int, tips: String)
optional func bleDownloadFileStop()
optional func bleDeleteFile(sessionId: Int, status: Int)
optional func bleDepair(_ status: Int)
```

### WiFi-sync config callbacks

```swift
optional func onWifiSyncConfigReceived(index: UInt32, ssid: String, password: String)
optional func onWifiSyncConfigSet(result: Int)
optional func onWifiSyncListReceived(list: [UInt32])
optional func onWifiSyncDeleteResult(result: Int)
optional func onWifiSyncTestStarted(index: UInt32)
optional func onWifiSyncWillStart(seconds: Int)
optional func onWifiSyncTestResult(index: UInt32, result: Int, rawCode: Int)
optional func onWifiSyncUrl(url: String)
optional func onWifiSyncEnabled(_ value: Int)
optional func onWifiRssiRequestConfirmed(status: Int)
optional func bleWiFiOpen(_ status: Int, _ wifiName: String, _ wholeName: String, _ wifiPass: String)
```

### Permission / resource checks

```swift
optional func onSdkFetchPermissionResult(pass: Bool, tips: String)
optional func onSdkCheckPermissionResult(pass: Bool, tips: String)
optional func onSdkCheckResourceResult(pass: Bool, tips: String)
optional func onCommonMsgChannel(type: Int, value: Int, tips: String)
```

### OTA callbacks

```swift
optional func bleFotaResult(uid: Int, status: Int, errmsg: String?)
optional func bleFotaPackReq(uid: Int, start: Int, end: Int)
optional func bleFotaPackFin(uid: Int, status: Int, errmsg: String?)
optional func bleOtaDataSendFail()
```

---

## 3. Audio export & download

The device stores audio in a proprietary AVC/OGG-Opus format. These methods decode it on
the phone into a standard file.

```swift
// Decode + write a file, reporting progress through an AudioExportCallback:
func exportAudio(sessionId: Int, outputDir: String,
                 format: AudioExportFormat, channels: Int = 1,
                 callback: any AudioExportCallback)

static func getSupportedExportFormats() -> [AudioExportFormat]

// Lower-level download (status/progress reported via the delegate's bleDownloadFile):
func downloadFile(sessionId: Int, desiredOutputPath: String,
                  format: PlaudDownloadFormat = .wav)
func stopDownloadFile()
```

```swift
@objc enum AudioExportFormat: Int {
    case pcm = 0, mp3 = 1, wav = 2, opus = 3
    var fileExtension: String { get }
}

@objc enum PlaudDownloadFormat: Int {
    case pcm = 0
    case mp3 = 1   // @available(*, unavailable) — MP3 not supported on this path
    case wav = 2
}

@objc protocol AudioExportCallback {
    func onProgress(_ progress: Int, message: String)
    func onComplete(outputPath: String)
    func onError(_ error: String)
}
```

> The template app uses `.mp3` deliberately for `exportAudio` so files are playable by
> `AVAudioPlayer` *and* accepted by the transcription upload.

---

## 4. WiFi fast transfer (PlaudWiFiAgent)

WiFi transfer is ~10× faster than BLE. The high-level `PlaudDeviceBasicSDK.PlaudWiFiAgent`
wraps the low-level transport. Flow: call `PlaudDeviceAgent.setDeviceWiFi(open: true)` →
in the `bleWiFiOpen` callback hand the device to the WiFi agent → `connectWifi` → after
`wifiHandshake(0)`, transfer.

```swift
class PlaudWiFiAgent {              // .shared singleton
    static let shared: PlaudWiFiAgent
    weak var delegate: PlaudWiFiAgentProtocol?
    var bleDevice: BleDevice?
    var isConnected: Bool { get }
    var isDownloading: Bool { get }
    var isDownloadingAll: Bool { get }
    var currentSessionId: Int { get }
    var currentDownloadSpeedKBps: Double { get }
    func getFormattedDownloadSpeed() -> String

    func connectWifi(_ ssid: String, _ passphrase: String, _ overtimeSec: Int = 60)  // iOS 11+
    func listenPort(_ ssid: String, _ overtimeSec: Int = 30)
    func disconnect()
    func isConnectedTo(_ ssid: String) -> Bool
    func getCurrentWiFiName() -> String?
    func getConnectionStatusDescription() -> String
    func isWebSocketConnected() -> Bool

    func getFileList(_ uid: Int, _ sessionId: Int, _ single: Bool = false)
    func syncFile(_ sessionId: Int, _ start: Int, _ end: Int = 0, _ scene: Int = 1)
    func stopSyncFile(_ sessionId: Int, _ scene: Int = 1)
    func deleteFile(_ sessionId: Int, _ scene: Int = 1)

    func exportAudioViaWiFi(sessionId: Int, outputDir: String,
                            format: AudioExportFormat, channels: Int = 1,
                            callback: any AudioExportCallback)
    func startDownloadAll()
    func stopDownloadAll()

    func startRateTest(_ onOff: Bool, _ packSize: Int)
    func getDeviceLogs(_ begin: Bool)
    func openLog(_ opened: Bool, _ backBlock: ((String) -> Void)? = nil)
}
```

`PlaudWiFiAgentProtocol` (all members `@objc optional`):

```swift
func wifiCommonErr(_ cmd: Int, _ status: Int)
func wifiHandshake(_ status: Int)                          // status 0 = handshake done
func wifiConnectionStatus(_ ssid: String, _ connected: Bool)
func wifiPower(_ power: Int, _ voltage: Int)
func wifiFileListFail(_ status: Int)
func wifiFileList(_ files: [BleFile])
func wifiSyncFile(_ sessionId: Int, _ status: Int)
func wifiSyncFileData(_ sessionId: Int, _ offset: Int, _ count: Int, _ binData: Data)
func wifiDataComplete()
func wifiSyncFileStop(_ status: Int)
func wifiFileDelete(_ sessionId: Int, _ status: Int)
func wifiClientFail()
func wifiClose(_ status: Int)
func wifiRateFail(_ status: Int)
func wifiRate(_ instantRate: Int, _ averageRate: Int, _ lossRate: Double)
func wifiLogsFail(_ status: Int)
func wifiLogs(_ logData: Data?)
func wifiTips(_ tips: Int)
func wifiDownloadAllProgress(_ totalFiles: Int, _ currentFileIndex: Int,
                             _ currentFile: BleFile?, _ totalProgress: Double)
func wifiDownloadAllCompleted(_ completedFiles: Int, _ failedFiles: Int)
```

> Requires the Hotspot Configuration entitlement in the host app.

---

## 5. Firmware / OTA update

High-level OTA helpers hang off `PlaudDeviceAgent`:

```swift
func checkFirmwareUpdate(completion: @escaping (PlaudFirmwareCheckResult) -> Void)
func startFirmwareUpdate(progress: @escaping (PlaudFirmwarePhase, Float) -> Void,
                         completion: @escaping (PlaudFirmwareUpdateResult) -> Void)
func pushFirmwareFile(filePath: String, toVersion: String,
                      progress: @escaping (PlaudFirmwarePhase, Float) -> Void,
                      completion: @escaping (PlaudFirmwareUpdateResult) -> Void)
```

```swift
@objc enum PlaudFirmwarePhase: Int { case downloading=0, installing=1, restarting=2, complete=3 }

class PlaudFirmwareCheckResult: NSObject {
    let hasUpdate: Bool
    let currentVersion, latestVersion: String
    let versionCode: Int
    let releaseNotes, downloadUrl, md5: String
    let isForce: Bool
}

class PlaudFirmwareUpdateResult: NSObject {
    let success: Bool
    let version: String
    let errorMessage: String?
}
```

A lower-level version-check/download API is also exposed (`quickUpdateCheck`,
`silentUpdateCheck`, `downloadUpdatePackage`, `checkForceUpdate`,
`getDownloadedUpdatePackages`, `cleanDownloadedUpdatePackages`, `checkLatestVersion`,
`downloadUpdate`, `performUpdateCheck`, plus free functions `PlaudQuickUpdateCheck` /
`PlaudSilentUpdateCheck`) returning `LatestVersionResponse` / `UpdateStatus` /
`UpdateError`.

---

## 6. Cloud AI — PlaudWorkflowManager & transcription

`PlaudWorkflowManager.shared` submits cloud jobs (transcribe, AI summarize, AI ETL, audio
merge) and polls for results.

```swift
class PlaudWorkflowManager {            // .shared
    func submitWorkflow(_ request: WorkflowSubmitRequest,
                        completion: @escaping (WorkflowResult<WorkflowSubmitResponse>) -> Void)
    func getWorkflowStatus(_ workflowId: String,
                           completion: @escaping (WorkflowResult<WorkflowStatusResponse>) -> Void)
    func getWorkflowResults(_ workflowId: String,
                            completion: @escaping (WorkflowResult<WorkflowResultResponse>) -> Void)
    func submitAndWaitForCompletion(_ request: WorkflowSubmitRequest,
                                    timeout: TimeInterval = 3600,
                                    progressHandler: ((WorkflowStatusResponse) -> Void)? = nil,
                                    completion: @escaping (WorkflowResult<WorkflowResultResponse>) -> Void)
    func pollWorkflowStatus(workflowId: String, timeout: TimeInterval,
                            progressHandler: ((WorkflowStatusResponse) -> Void)? = nil,
                            completion: @escaping (WorkflowResult<WorkflowResultResponse>) -> Void)
}
```

Convenience builders / one-shot runners (extension):

```swift
func createAudioTranscribeWorkflow(fileId:language:diarization:transcriptType:completion:)
func createAIEtlWorkflow(etlType:extras:completion:)
func createAudioMergeWorkflow(fileIdList:groupId:completion:)
func createTranscribeAndAnalysisWorkflow(fileId:language:diarization:transcriptType:etlType:completion:)
func createMergeAndAnalysisWorkflow(fileIdList:groupId:etlType:completion:)

// run + poll to completion:
func doAudioTranscribeWorkflow(fileId:language:diarization:transcriptType:timeout:progressHandler:completion:)
func doTranscribeAndAnalysisWorkflow(fileId:language:diarization:transcriptType:etlType:timeout:progressHandler:completion:)
func doTranscribeAndAISummaryWorkflow(fileId:language:diarization:transcriptType:templateId:prompt:model:startTime:timeout:progressHandler:completion:)
func doAudioMergeWorkflow(fileIdList:groupId:timeout:progressHandler:completion:)
func doMergeAndAnalysisWorkflow(fileIdList:groupId:etlType:timeout:progressHandler:completion:)
```

Key request / result types:

- **Requests:** `WorkflowSubmitRequest`, `WorkflowTask`, `WorkflowTaskParams`, `WorkflowMetadata`
- **Status:** `WorkflowStatus` (`pending`/`running`/`progress`/`success`/`failure`/`cancelled`/`timeout`), `WorkflowStatusResponse`, `WorkflowSubmitResponse`
- **Results:** `WorkflowResultResponse` (with rich accessors like `firstTranscriptResult`, `allTranscriptText`, `transcriptBySpeaker`, `aiSummaryText`, `aiSummaryKeyPoints`, …), `WorkflowTaskResult`
- **Domain models:** `TranscriptResult` / `TranscriptSegment`, `AISummaryResult` (+ `AISummaryDetailedResult`, `AISummaryHeader`, `AISummaryForm`, `AISummaryContent`, `AISummaryTopic`, `AISummaryQuestion`), `AIEtlResult` (+ `DealAnalysis`, `CommunicationFeedback`, `DealIntention`, `DealReason`, `NoDealReason`)
- **Task types:** `WorkflowTaskType` (`audioTranscribe`, `aiSummarize`, `aiEtl`, `audioMerge`, `custom`, `unknown`)
- **Errors:** `WorkflowError`, `WorkflowResult<T>` (`.success`/`.failure`), `WorkflowParsingError`

See [section 6a](#6a-partner-device-authentication-sn-sign--gen-key--sn-verify) for
`PlaudPartnerApiManager`, which wraps the partner SDK auth endpoints.

---

## 6a. Partner device authentication (`sn-sign` / `gen-key` / `sn-verify`)

`PlaudPartnerApiManager` (reached via `PlaudDeviceAgent.shared.getPartnerApiManager()` or
`PlaudPartnerApiManager.shared`) wraps three partner-only backend endpoints under
`{customDomain}/developer/api/open/partner/sdk/`. All three are authenticated with the
**per-user JWT** (`USER_ACCESS_TOKEN`) — the same token you pass to `initSDK` — sent as a
Bearer header:

| Endpoint | Purpose | iOS surface |
|----------|---------|-------------|
| `…/sdk/sn-sign` | Sign a device serial number so the device will accept this app as an authorized partner peer during the BLE handshake. | `signDeviceSn(deviceType:sn:)` |
| `…/sdk/gen-key` | Generate an RSA key pair used for **end-to-end (E2EE) audio encryption** — the public key is provisioned to the device, the private key stays on the client to decrypt exported audio. | `generateRsaKeyPair()` |
| `…/sdk/sn-verify` | Verify a signed SN. **Called internally by the SDK** during the handshake; there is no public iOS method for it. | *(internal)* |

```swift
@_hasMissingDesignatedInitializers final public class PlaudPartnerApiManager {
    static let shared: PlaudPartnerApiManager
    func setUserAccessToken(_ token: String?)
    func getUserAccessToken() -> String?

    // → POST …/open/partner/sdk/sn-sign
    func signDeviceSn(deviceType: String, sn: String,
                      completion: @escaping (Result<PlaudPartnerSnSignResponse, Error>) -> Void)

    // → POST …/open/partner/sdk/gen-key
    func generateRsaKeyPair(
        completion: @escaping (Result<PlaudPartnerGenKeyResponse, Error>) -> Void)
}
```

Request / response types (all `Codable`):

```swift
struct PlaudPartnerSnSignRequest  { let type: String; let sn: String }
struct PlaudPartnerSnSignResponse { let signature: String? }          // sn-sign result
struct PlaudPartnerGenKeyResponse { let publicKey: String?            // gen-key result
                                    let privateKey: String? }         //   (PEM strings)
struct PlaudPartnerApiErrorResponse { let detail: String? }

enum PlaudPartnerApiError: Error, LocalizedError {
    case invalidParameter(String), noUserAccessToken, invalidURL(String),
         invalidResponse, unauthorized(detail: String?),
         serverError(code: Int, body: String?),
         requestEncodeFailed(Error), responseDecodeFailed(Error), networkError(Error)
}
```

### How to use them

**You normally don't have to.** When you initialize with the per-user JWT flow
(`initSDK(userAccessToken:customDomain:)`), the facade drives `sn-sign` (and the internal
`sn-verify`) automatically as part of connect/handshake, and it **caches the `sn-sign`
signature per device serial number** so repeat connections don't re-hit the backend. Call
these methods directly only for advanced flows — pre-provisioning a device, custom
handshake orchestration, or generating E2EE keys ahead of time.

The one signature that maps to `type` is the device family; use
[`PlaudFileUploader.calculateSnType(sn:)`](#7-file-upload--device-binding-plaudfileuploader)
to derive it from a serial number (SN prefixes: `881` = NotePro, `883` = NotePinS).

**1. Sign a device SN (`sn-sign`)** — authorize a specific device for this partner/user:

```swift
let api = PlaudDeviceAgent.shared.getPartnerApiManager()
// (setUserAccessToken is already done by initSDK; set it explicitly only if you
//  construct the manager before init.)
let sn = bleDevice.serialNumber
api.signDeviceSn(deviceType: PlaudFileUploader.calculateSnType(sn: sn), sn: sn) { result in
    switch result {
    case .success(let resp):
        guard let signature = resp.signature else { return }   // nil ⇒ backend rejected
        // The SDK caches & replays this during the BLE handshake; you rarely
        // need to hold onto it yourself.
    case .failure(let error):
        // e.g. PlaudPartnerApiError.unauthorized(detail:) if the JWT is bad/expired
        print("sn-sign failed:", error.localizedDescription)
    }
}
```

**2. Generate an RSA key pair (`gen-key`)** — set up end-to-end audio encryption:

```swift
api.generateRsaKeyPair { result in
    switch result {
    case .success(let keys):
        guard let privatePem = keys.privateKey, let _ = keys.publicKey else { return }
        // • publicKey  → provisioned to the device so it can encrypt audio to you.
        // • privateKey → keep it safe on the client; it is required to decrypt
        //   E2EE audio you later export (see section 9):
        //
        //     let out = try AudioFileDecryptor.decryptAudioFile(
        //         inputPath: encryptedPath, privateKeyPem: privatePem)
        //
        //   Store the private key securely (e.g. Keychain). If you lose it,
        //   previously-encrypted recordings become unrecoverable.
    case .failure(let error):
        print("gen-key failed:", error.localizedDescription)
    }
}
```

> **`sn-verify` is internal.** The device-side/verification half of the SN handshake is
> performed by the SDK itself; it is not part of the public iOS API. It surfaces as an
> endpoint path only in the Android build. If a connection fails at the auth stage, look at
> the handshake callbacks (`blePenState` `keyState` / `bleAppKeyState`) rather than calling
> a verify method.

> **Auth model recap.** These endpoints use the **per-user JWT**, *not* the
> `PLAUD_CLIENT_ID` / `PLAUD_API_KEY` pair — those two are only for the transcription API.
> See [section 9](#9-encryption--end-to-end-audio-decryption) for how the `gen-key` private
> key feeds RSA-wrapped (E2EE) audio decryption.

---

## 7. File upload & device binding (PlaudFileUploader)

`PlaudFileUploader.shared` handles S3 multipart uploads of recordings/logs and
device bind/unbind.

```swift
class PlaudFileUploader {            // .shared
    var device: BleDevice?
    func checkRecordingExist(sessionId: Int) -> Bool
    func getDownloadedRecordingPath(sessionId: Int, desiredPath: String) -> String
    func uploadRecording(sn: String, sessionId: Int, duration: Double,
                         onProgress: (Double) -> Void,
                         onSuccess: ([String:Any]) -> Void,
                         onFailure: (Error) -> Void)
    func uploadLogFile(filePath: String, sn: String,
                       onProgress: (Double) -> Void,
                       onSuccess: ([String:Any]) -> Void,
                       onFailure: (Error) -> Void)
    static func calculateSnType(sn: String) -> String
    func bindDevice(ownerId: String, sn: String, completion: (Result<[String:Any], Error>) -> Void)
    func unbindDevice(ownerId: String, sn: String, completion: (Result<[String:Any], Error>) -> Void)
}
```

`PlaudLogUploadManager.shared` automates log collection/upload (`setAutoUploadEnabled`,
`startAutoUpload`/`stopAutoUpload`, `uploadLogFiles`, `uploadLogFilesWithDeviceSN`,
`uploadLogsAfterRecording`, `cleanupLogFiles`, `getUploadStatistics`). Config via
`PlaudLogConfig.shared`.

---

## 8. Domain / region & localization

```swift
class PlaudDomainManager {           // .shared
    enum Region: String, CaseIterable { case cn, us, jp }
    func setCustomDomain(_ domain: String)
    func setRegion(_ region: Region)
    func setRegionForLanguage(_ languageCode: String)
    func getCurrentRegion() -> Region
    func getCurrentDomain() -> String
    func getCurrentBaseURL() -> String
    func getDomain(for: Region) -> String
    func getBaseURL(for: Region) -> String
    func buildAPIURL(path: String) -> String
    func setAutoLanguageAssociation(_ enabled: Bool)
    // …getRegionForCurrentLanguage(), getCurrentLanguageCode(), etc.
}

class PlaudLocalizationManager {     // .shared
    func setCustomBundlePath(_ path: String)
    func setLanguage(_ language: String)
    func getCurrentLanguage() -> String
    func checkSDKBundle() -> Bool
    func localizedString(for key: String) -> String
}
// String.plaudLocalized convenience property is also available.
```

> `customDomain` is **domain-only** (no `https://` prefix), e.g. `platform-us.plaud.ai`.

---

## 9. Encryption & end-to-end audio decryption

Device audio may be end-to-end encrypted. Decryption helpers live as extensions on
`BleAgent` and as standalone classes. The RSA key pair used for E2EE is minted by the
`gen-key` endpoint — see
[section 6a](#6a-partner-device-authentication-sn-sign--gen-key--sn-verify); the
`privateKey` PEM it returns is what you pass as `privateKeyPem` below.

```swift
// On BleAgent — secure channel info:
var isSecureChannelEstablished: Bool { get }
var isEncryptionSupported: Bool { get }
func getEncryptionKey() -> String?
func getEncryptionNonce() -> String?
func getEncryptionAD() -> String?
func getEncryptionParameters() -> [String:String]?
func getEncryptionProtocolInfo() -> [String:Any]

// Decrypt file data / files (ChaCha20-Poly1305 / AES-256 transport encryption):
func decryptFileData(_ encryptedData: Data, key:String?=nil, nonce:String?=nil, ad:String?=nil) throws -> Data
func decryptFile(inputPath:String, outputPath:String, key:String?=nil, nonce:String?=nil, ad:String?=nil) -> Bool
func decryptAndPrepareOggFile(encryptedFilePath:String, channel:Int32, key:String?=nil, nonce:String?=nil, ad:String?=nil) -> String?

// End-to-end (RSA-wrapped) encrypted files:
func decryptE2EEAudioFile(inputPath:String, outputPath:String?=nil, privateKeyPem:String) throws -> String
func isE2EEEncryptedFile(path:String) -> Bool
func getE2EEFileHeader(path:String) -> PlaudEncryptHeader?

// OGG playback of an encrypted file:
func playDecryptedOggFile(encryptedFilePath:String, channel:Int32=1, delegate:JXOggPlayerDelegate?=nil, ...) -> Bool
func stopOggPlayback(); func pauseOggPlayback(); func resumeOggPlayback()
func getOggPlayer() -> JXOggPlayer
```

Standalone decryptors:

```swift
class AudioFileDecryptor {           // all static
    static func decryptAudioFile(inputPath:String, privateKeyPem:String, outputPath:String?=nil) throws -> String
    static func decryptAudioToOgg(inputPath:String, privateKeyPem:String, outputPath:String?=nil) throws -> String?
    static func isFileEncrypted(path:String) -> Bool
    static func getHeader(path:String) -> PlaudEncryptHeader?
}

class ChaCha20 {
    static func decrypt(data:Data, key:Data, nonce:Data, counter:UInt32=0) throws -> Data
}
```

Supporting types: `PlaudEncryptHeader`, `EncryptionError`, `AudioDecryptorError`,
`ChaCha20Error`. RSA primitives (`PublicKey`, `PrivateKey`, `ClearMessage`,
`EncryptedMessage`, `Signature`, `SwiftyRSA`, `RSASecretConfig`, `SecretUtil`) are exposed
from `PlaudBleSDK` for partner key handling.

---

## 10. Model types & enums

### `BleDevice` (`PlaudBleSDK`)

Represents a scanned/connected device. Selected fields:

```swift
class BleDevice {
    var name, uuid, manufacturer, serialNumber: String
    var rssi: Float
    var projectCode, versionCode, bindCode, power: Int
    var isCharging: Bool
    var total, free, duration: Int          // storage
    var timezone, zoneMin, channels: Int
    var supportWiFi, nsAgc, isOgg: Bool
    var state, privacy, keyState, uDisk, findmyToken: Int
    var hasFota: Bool
    var versionTypeStr, ssn: String
    var protVersion: Int
    var wholeName: String { get }            // BLE advertised name
    var wifiName: String { get }             // hotspot SSID
    init(sn: String)
    func wholeVersion() -> String
    func zoneSecond() -> Int
}

// PlaudBleDevice : BleDevice is the PlaudDeviceBasicSDK subclass.
```

> Supported devices by SN prefix: `881` = NotePro, `883` = NotePinS.

### `BleFile` (`PlaudBleSDK`)

A recording on the device:

```swift
class BleFile {
    var sn: String
    var sessionId, size, offset, timezone, zoneMin: Int
    var scenes, penCollect, channels: Int
    var nsAgc, isOgg: Bool
    var isMusic: Bool { get }
    func duration() -> Int
    func oggDuration() -> Int
    static func calculateDuration(_ fileSize:Int, _ channel:Int, _ isOgg:Bool, _ scenes:Int=0) -> Int
}
```

### Device-setting enums (`PlaudBleSDK`)

| Enum | Cases |
|------|-------|
| `RecScene` | `Unknown, Normal, Interview, Classroom, Music, Meeting, Memo` |
| `RecMode` | `Normal, NC` |
| `LanguageType` | `SimpleChinese, TradChinese, English` |
| `BacklightBright` | `Bright1…Bright6` |
| `BacklightDuration` | `Sec10, Sec20, Sec30, SecAlways` |
| `VadSensitivity` | `Quality, lowBitrate, Normal, Aggressive` |
| `VpuGain` | `Low, Medium, High` |
| `SwitchHandlerID` | `CallSceneSwitching, Recording` |
| `WebsocketType` | `url, serToken, devToken` |
| `AutoClear` | `Close, Open` |
| `CommonType` / `CommonAction` | misc setting channels |

Other model types: `GlassData`, `BleRecordMarkingTag`, `UpdateInfo`.

---

## 11. Low-level: PlaudBleSDK.BleAgent

`BleAgent.shared` is the raw BLE transport that `PlaudDeviceAgent` is built on. Use it
directly only for features not surfaced by the facade. It exposes a much wider command set
and the `BleAgentProtocol` delegate (60+ callbacks). Highlights:

**State (read-only):** `isPoweredOn`, `isConnected`, `isBinded`, `isRecording`,
`isDownloading`, `isWiFiOpen`, `isMusic`, `scene`, `sessionId`, `needDecode`.

**Lifecycle / auth:** `initBluetooth()`, `disInitBluetooth()`, `setUserIdentifier(_:_:_:)`,
`checkAppKey(_:)`, `setBinding(_:)`, `setFilter(...)`, `openLog(...)`.

**Scan / connect:** `startScan()`, `startLoopScan()`, `stopScan()`,
`connectBleDevice(bleDevice:_:_:_:)`, `disconnect()`.

**Recording:** `startRecord(_:)`, `stopRecord()`, `pauseRecord(_:)`, `resumeRecord(_:)`.

**Files:** `getFileList(uid:sessionId:onlyOne:)`, `syncFile(...)`, `stopSyncFile()`,
`deleteFile(sessionId:)`, `clearAllFile()`, `getMarking(_:)`,
`getRecordMarkingTags(...)`.

**Device settings (read/set pairs):** backlight duration & brightness, language, rec scene,
rec mode, VAD & sensitivity, VPU/mic gain, battery mode, switch handler, auto power-off,
raw-wave, "recording after disconnect", "sync when idle", find-my, VPU-CLK,
"stop recording after charging", auto-clear, privacy, LED, device name, alarm recording.

**WiFi & OTA:** `operateWiFi(open:isOTA:)`, `setWiFiSsid(...)`, `getWiFiSsid()`,
`pushFotaInfo(...)`, `pushFotaComplete(_:_:)`, `pushFotaPack(_:packData:postDelayUs:)`,
`getUpdateInfo(_:)`.

**Other:** `restoreFactory()`, `setHeartBeat(status:)`, `setDeviceActive(status:)`,
websocket profile config, sound-plus token, common params get/set, device-log sync, SD
flash CID, BLE rate test.

See the framework's `arm64-apple-ios.swiftinterface` for the exhaustive signature list.

---

## 12. Low-level: PlaudWiFiSDK

The standalone WiFi framework. `PlaudDeviceBasicSDK.PlaudWiFiAgent` wraps these; prefer the
facade.

```swift
// PlaudWiFiSDK.Agent — .shared
func isDeviceConnect() -> Bool
func bleDevice() -> BleDevice?
func getFileList(_ uid:Int, _ sessionId:Int, _ single:Bool=false)
func syncFile(_ sessionId:Int, _ start:Int, _ end:Int=0, _ decode:Bool=false, _ scene:Int=1)
func stopSyncFile(_ sessionId:Int, _ scene:Int=1)
func deleteFile(_ sessionId:Int, _ scene:Int=1)
func sessionId() -> Int; func isDownloading() -> Bool; func isRecording() -> Bool

// PlaudWiFiSDK.WiFiAgent — .shared  (lower-level transport + WiFiAgentProtocol)
func connectWifi(_ ssid:String, _ passphrase:String, _ overtimeSec:Int=60, _ needRetry:Bool=true)  // iOS 11+
func cancelConnectWifi()
func listenPort(_ ssid:String, _ overtimeSec:Int=30)
func clearAllWiFiConfigurations()      // iOS 11+
func disconnect()
func getCurrentWiFiName() -> String?
// + app-prefixed file ops (appGetFileList, appSyncFile, appDeleteFile, …) and OTA push.
```

`WiFiAgentProtocol` mirrors the high-level `PlaudWiFiAgentProtocol` (see
[section 4](#4-wifi-fast-transfer-plaudwifiagent)), plus `penRequestOTAData(...)` and
`wifiOTAStatus(_:_:)`.

---

## 13. Audio utilities (decoders, waveform, players)

These live in `PlaudBleSDK` and help turn raw device data into playable/visualizable audio.

**Format conversion — `JXFileDecoder.shared`** (all async with completion handlers; each has
a `has…Task()` / `…Cancel()` pair):

```swift
func avcToMp3(avcPath:mp3Path:clearUnfinished:quality:channels:ns_agc:completionHandler:)
func avcToWav(avcPath:wavPath:channels:ns_agc:clearUnfinished:completionHandler:)
func avcToPcm(avcPath:pcmPath:clearUnfinished:channels:ns_agc:completionHandler:)
func avcToOgg(_:_:clearUnfinished:_:_:_:_:_:callback:)
func avcToNoiseReductionWav(avcPath:wavPath:channels:sound_plus:noiseReductionGain:clearUnfinished:completionHandler:)
func oggToMp3(_:_:_:_:callback:); func oggToPcm(...); func oggToOpus(...)
func pcmToMp3(pcmPath:mp3Path:clearUnfinished:quality:channels:completionHandler:)
func pcmToWav(pcmPath:wavPath:channels:simpleRate:completionHandler:)
func oggMulToSingle(_:_:_:callback:)
```

**Streaming PCM decode:** `JXAvcDecoder` (`decode(_:_:)`), `JXPcmProcess.shared` /
`JXWave2PcmProcess.shared` (deliver PCM via `JXPcmProcessDelegate`).

**Waveform / volume meters:** `JXRecordVolumer.shared`, `JXRecordingVolumer.shared`
(`VolumeProtocol`), `PDRecordVolumer.shared`, `PDRecordingVolumer.shared`
(`PDVolumeProtocol`) — feed `blePcmData` chunks via `append(...)` to get dB levels.

**Waveform images / files:** `JXFileSoundWave.shared`, `PDFileSoundWave.shared`
(`createSoundWave(...)`, `avcToSoundWave(...)`).

**Helpers:** `JXWaveHelper.shared` (PCM↔WAV, channel split), `JXCrcHelper.shared`
(`getCrc(path:)`, `checkCrc(...)`), `OggOpusParser`.

**Playback:** `PlaudPCMPlayer` (`loadFile`, `play`/`pause`/`stop`, `onPlaybackFinished`,
`onError`), `PlaudAudioPlayerViewController(sessionId:)`, and the `Sound` /`Player`/
`Session` abstraction over `AVAudioPlayer`.

**UI helpers (`PlaudDeviceBasicSDK`):** `PlaudWifiSettingPage`, `PlaudWifiAddingPage`,
`PresentBottomVC` — prebuilt view controllers for WiFi-sync configuration.

---

## 14. Logging

```swift
// PlaudBleSDK free functions:
func mlog(_ text: String, data: Data? = nil, maxBytes: Int? = 80)
func wlog(_ text: String, data: Data? = nil, maxBytes: Int? = 80)

// BleLogger.shared.setLog(opened:logBlock:wlogBlock:sync:) routes logs to your callback.
// PlaudSDKLogger.logEvent(_:parameters:) for analytics events.
// PlaudLogConfig.shared / PlaudLogFileRotationManager.shared manage on-disk log rotation.
// PlaudLogEncryption.exportEncryptedLogs() -> NSURL? exports encrypted logs for support.
```

---

### Notes

- The SDK frameworks are **arm64 device builds** — they do not run on the iOS Simulator.
- Most callbacks are delivered on the SDK's dispatch queues, not the main thread; marshal to
  the main queue before touching UIKit.
- This reference is generated from the public `.swiftinterface`; the frameworks also expose
  many `@objc`/Foundation extensions (on `Data`, `String`, `Date`, etc.) and Objective-C
  bridging shims (`_objc_*` classes) that are omitted here for brevity.

---

## Appendix A: Complete BleAgent API (`PlaudBleSDK`)

This appendix transcribes the full public surface of `BleAgent` and its delegate protocols,
for cases where the [`PlaudDeviceAgent`](#1-plauddeviceagent--main-facade) facade doesn't
expose what you need. `BleAgent.shared` is the singleton.

> Many setters come in two forms: an `@objc` variant taking a raw `Int`, and a Swift-only
> variant taking a typed enum (e.g. `setRecScene(value: Int)` vs
> `setRecScene(type: RecScene)`). Prefer the typed variant from Swift.

### A.1 Properties

```swift
static let shared: BleAgent

// Static constants
static let protocolVersionNewBatteryService: Int
static let protocolVersionV20Features: Int

// Core objects
var cbManager: CBCentralManager?
var bleDevice: BleDevice?
weak var delegate: BleAgentProtocol?
weak var glassDelegate: GlassProtocol?
weak var otaDelegate: OtaProtocol?
var bleBlock: Int2Void?                       // typealias Int2Void = (Int) -> Void
let selfSignedHosts: [String]

// State (read-only)
var isPoweredOn: Bool { get }
var isConnected: Bool { get }
var isBinded: Bool { get }
var isOnlyOne: Bool { get }
var isRecording: Bool { get }
var isDownloading: Bool { get }
var isWiFiOpen: Bool { get }
var needDecode: Bool { get }
var isMusic: Bool { get }
var scene: Int { get }
var settingScene: Int { get }
var sessionId: Int { get }
var userToken: String? { get }
var customerToken: String? { get }

// Mutable config / handshake plumbing
var repeatCommondInterval: Int
var cmdDelegateQueue: DispatchQueue
let parseQueue: DispatchQueue
var isUsbState: Bool
var isCharging: Bool
var flutterMapData: [String: Any]
var versionType: String
var versionCode: Int

// ChaCha20 / secure-channel material
var secretPackages: [Data]
var secretIndex: Int
var secretCount: Int
var chacha20Key: Data?
var chacha20Nonce: Data?
var chacha20AD: Data?
var wifiUseAes: Bool
var globalSendSeq: Int
var globalReceiveSeq: Int

// Encryption (extension)
var isSecureChannelEstablished: Bool { get }
var isEncryptionSupported: Bool { get }
```

### A.2 Lifecycle, auth & connection

```swift
func setWiFiState(_ connected: Bool)
func setUserIdentifier(_ appKey: String, _ bindToken: String, _ hkServer: Bool = false)
func initBluetooth()
func disInitBluetooth()
func checkAppKey(_ appKey: String)
func setBinding(_ token: String)
func setFilter(name: String?)
func setFilter(_ names: [String])
func openLog(_ opened: Bool, logBlock: ((String) -> Void)? = nil, wlogBlock: ((String) -> Void)? = nil)

func isDeviceConnect() -> Bool
func startScan()
func startLoopScan()
func stopScan()
func connectBleDevice(bleDevice: BleDevice, _ devToken: String? = nil,
                      _ userName: String? = nil, _ isForceClear: Bool)
func disconnect()
func isSNTempChecked() -> Bool
func reCheckSNIfNeed()
func depair(clear: Bool = false)
func isAuthOk() -> Bool
@available(iOS 11.0, *) func canSendWithoutResponse() -> Bool
```

### A.3 Device status reads

```swift
func readPower()
func getChargingState()
func getState()
func getStorage()
func getDeviceStatus()
func setHeartBeat(status: Int)
func getNewFeature(_ data: Data)
```

### A.4 Recording

```swift
func startRecord(_ scene: Int = 0)
func stopRecord()
func pauseRecord(_ sessionId: Int)
func resumeRecord(_ sessionId: Int)
func setDeviceActive(status: Int)
func setPrivacy(onOff: Int)
func restoreFactory()
```

### A.5 Files, sync & marking

```swift
func getFileList(uid: Int, sessionId: Int, onlyOne: Bool = false)
func syncFile(sessionId: Int, start: Int, end: Int, decode: Bool)
func stopSyncFile()
func deleteFile(sessionId: Int)
func clearAllFile()
func getMarking(_ sessionId: Int)
func getRecordMarkingTags(uid: Int, startTimestamp: Int, endTimestamp: Int)
func dataOfGetRecordMarkingTags(uid: Int, startTimestamp: Int, endTimestamp: Int) -> Data
func toSingleChannel(_ pcmData: Data) -> Data
```

### A.6 Device settings (read / set pairs)

```swift
// Backlight
func readBacklightDuration();  func setBacklightDuration(type: Int);  func setBacklight(duration: BacklightDuration)
func readBacklightBright();    func setBacklightBright(type: Int);    func setBacklight(bright: BacklightBright)

// Language
func readLanguage();           func setLanguage(type: Int);           func setLanguage(type: LanguageType)

// VAD / recording scene & mode
func openVAD(open: Bool)
func readRecScene();           func setRecScene(value: Int);          func setRecScene(type: RecScene)
func readRecMode();            func setRecMode(value: Int);           func setRecMode(type: RecMode)
func readVadSensitivity();     func setVadSensitivity(sensitivity: Int);  func setVadSensitivity(sensitivity: VadSensitivity)

// Gains
func readVpuGain();            func setVpuGain(gain: Int);            func setVpuGain(gain: VpuGain)
func readMicGain();            func setMicGain(value: Int)
func readBatteryMode();        func setBatteryMode(value: Int)

// Misc toggles
func readSwitchHandler();      func setSwitchHandler(id: Int)
func readAutoPowerOff();       func setAutoPowerOff(value: Int)
func readRawWaveEnabled();     func setRawWaveEnabled(value: Int)
func readRecordingAfterDisConnetEnabled();  func setRecordingAfterDisConnetEnabled(value: Int)
func readSyncWhenIdleEnabled();             func setSyncWhenIdleEnabled(value: Int)
func readFindMyState();        func setFindMyState(value: Int)
func readVPUCLK();             func setVPUCLK(value: Int)
func readStopRecordingAfterCharging();      func setStopRecordingAfterCharging(value: Int)
func readAutoClear();          func saveAutoClear(_ open: Bool)

// Naming
func readBleName();            func setBleName(name: String)

// Generic common-params channel
func getCommonParams(dataType: Int)
func setCommonParams(dataType: Int, value: String)
```

### A.7 LED

```swift
func getLedState()
func setLedState(onOff: Int)
```

### A.8 Alarm recording

```swift
func setAlarmRec(start: Int, duration: Int, repeatMode: Int)
func getAlarmRec()
```

### A.9 WiFi (BLE-side control)

```swift
func operateWiFi(open: Bool, isOTA: Bool)
func setWiFiSsid(ssid: String, password: String, isTest: Bool = false)
func getWiFiSsid()

// "Sync when idle" scheduled-WiFi config
func getSyncInIdleWifiConfig(wifiIndex: UInt32)
func setSyncInIdleWifiConfig(operation: Int, wifiIndex: UInt32, ssid: String, password: String)
func deleteSyncInIdleWifiConfig(wifiIndices: [UInt32])
func getSyncInIdleWifiList()
func setSyncInIdleWifiTest(wifiIndex: UInt32)
func getSyncInIdleWifiTestResult(wifiIndex: UInt32)

// Websocket profile (server URL / tokens)
func setWebsocketProfile(type: Int, content: String)
func setWebsocketProfile(type: WebsocketType, content: String)
func getWebsocketProfile(type: Int)
func getWebsocketProfile(type: WebsocketType)
func testWebsocket()
```

### A.10 OTA / firmware push

```swift
func getUpdateInfo(_ callback: @escaping (Int, UpdateInfo?) -> Void)
func pushFotaInfo(_ uid: Int, _ fromVersion: String, _ toVersion: String,
                  _ thirdVersion: Int = 0, _ fileSize: Int, _ crc: Int)
func pushFotaInfo(_ uid: Int, _ fromVersion: Int, _ fromVersionType: Character,
                  _ toVersion: Int, _ toVersionType: Character,
                  _ thirdVersion: Int = 0, _ fileSize: Int, _ crc: Int)
func pushFotaInfo(_ uid: Int, _ fromVersion: Int, _ fromVersionType: String,
                  _ toVersion: Int, _ toVersionType: String,
                  _ thirdVersion: Int = 0, _ fileSize: Int, _ crc: Int)
func pushFotaComplete(_ uid: Int, _ status: Int)
func pushFotaPack(_ offset: Int, packData: Data, postDelayUs: NSNumber?)
```

### A.11 Binary-file transfer

```swift
func sendBinFileInfo(type: Int, totalSize: Int)
func sendBinFileData(type: Int, packageOffset: Int, packageSize: Int, data: Data)
func sendBinFileCheckSumResult(type: Int, crc: Int)
```

### A.12 Device logs

```swift
func getDeviceLogList(logType: Int)
func startSyncDeviceLogFile(logType: Int)
func stopSyncDeviceLogFile()
func deleteDeviceLogFile(logType: Int)
```

### A.13 Find-My, SoundPlus, SD flash, rate test, factory

```swift
func resetFindmy()
func setSoundPlusToken(licenseKey: String)
func getSDFLASHCID()
func appResetPassword()
func startBleRateTest(_ packSize: Int = 80)
func stopBleRateTest()
```

### A.14 Encryption (extension methods)

```swift
func getEncryptionKey() -> String?
func getEncryptionNonce() -> String?
func getEncryptionAD() -> String?
func getEncryptionParameters() -> [String: String]?
func getEncryptionProtocolInfo() -> [String: Any]

func decryptFileData(_ encryptedData: Data, key: String? = nil, nonce: String? = nil, ad: String? = nil) throws -> Data
func decryptFile(inputPath: String, outputPath: String, key: String? = nil, nonce: String? = nil, ad: String? = nil) -> Bool
func decryptAndPrepareOggFile(encryptedFilePath: String, channel: Int32, key: String? = nil, nonce: String? = nil, ad: String? = nil) -> String?

// End-to-end (RSA-wrapped) audio:
func decryptE2EEAudioFile(inputPath: String, outputPath: String? = nil, privateKeyPem: String) throws -> String
func isE2EEEncryptedFile(path: String) -> Bool
func getE2EEFileHeader(path: String) -> PlaudEncryptHeader?

// Encrypted OGG playback:
func playDecryptedOggFile(encryptedFilePath: String, channel: Int32 = 1,
                          delegate: JXOggPlayerDelegate? = nil,
                          key: String? = nil, nonce: String? = nil, ad: String? = nil) -> Bool
func stopOggPlayback()
func pauseOggPlayback()
func resumeOggPlayback()
func getOggPlayer() -> JXOggPlayer
```

### A.15 Glass data (wearable accessory)

```swift
func readGlassData(uid: Int)
func clearGlassData()
```

---

## Appendix B: BleAgentProtocol callbacks (`PlaudBleSDK`)

The delegate for the low-level `BleAgent`. Unlike `PlaudDeviceAgentProtocol`, **all of these
are required** (`@objc func`, not `optional`) — implement every one (even if empty) when
conforming directly. `bleConnectStage` is the only `@objc optional` member.

### B.1 Connection / handshake / bind

```swift
optional func bleConnectStage(sn: String?, stage: String, detail: String?)
func bleConnectState(state: Int)              // 1 = connected, 0 = disconnected
func bleScanResult(bleDevices: [BleDevice])
func bleScanOverTime()
func bleHandshakeWait(timeout: Int)
func bleBind(sn: String?, status: Int, protVersion: Int, timezone: Int)
func bleDeviceName(name: String?)
func bleState(powered: Bool)
func bleAppKeyState(result: Int)
func bleDepair(_ status: Int)
func blePenState(state: Int, privacy: Int, keyState: Int, uDisk: Int,
                 findMyToken: Int, hasSndpKey: Int, deviceAccessToken: Int,
                 versionType: String, versionCode: Int)
func blePenTime(stamp: Int, timezone: Int, zoneMin: Int)
func bleHeartbeat(status: Int)
```

### B.2 Errors

```swift
func bleUpdatePowerLowErr()
func bleDeviceDisconnectErr()
func bleUDiskErr(funcName: String)
func bleVoiceAbnormal(status: Int)
func bleDecodeFail(start: Int)
func bleOtaDataSendFail()
```

### B.3 Power / storage / status

```swift
func blePowerChange(power: Int, oldPower: Int)
func bleChargingState(isCharging: Bool, level: Int)
func bleStorage(total: Int, free: Int, duration: Int)
func bleDeviceStatus(status: [UInt8])
func bleNewFeature(data: Data)
func bleRate(lossRate: Double, rate: Int, instantRate: Int)
func blePrivacy(privacy: Int)
func bleSetActive(status: Int)
func bleAngles(pitchAngle: Float, rollbackAngle: Float, yawAngle: Float)
```

### B.4 Setting-changed callbacks

```swift
func blePasswordReset(password: Int)
func bleBacklightDuration(_ duration: Int)
func bleBacklightBright(_ bright: Int)
func bleLanguage(_ type: Int)
func bleRecScene(_ scene: Int)
func bleRecMode(_ mode: Int)
func bleVadSensitivity(_ value: Int)
func bleBatteryMode(_ value: Int)
func bleVpuGain(_ value: Int)
func bleMicGain(_ value: Int)
func bleSwitchHandler(_ id: Int)
func bleAutoPowerOff(_ value: Int)
func bleRawWaveEnabled(_ value: Int)
func bleRecordingAfterDisConnetEnabled(_ value: Int)
func bleSyncWhenIdleEnabled(_ value: Int)
func bleFindMyState(_ value: Int)
func bleVPUCLKState(_ value: Int)
func bleStopRecordingAfterCharging(_ value: Int)
func bleAutoClear(_ open: Bool)
func bleVad(_ open: Bool)
```

### B.5 Recording

```swift
func bleRecordStart(sessionId: Int, start: Int, status: Int, scene: Int, startTime: Int)
func bleRecordStop(sessionId: Int, reason: Int, fileExist: Bool, fileSize: Int)
func bleRecordPause(sessionId: Int, reason: Int, fileExist: Bool, fileSize: Int)
func bleRecordResume(sessionId: Int, start: Int, status: Int, scene: Int, startTime: Int)
func bleAlarmRec(start: Int, duration: Int, repeatMode: Int)
```

### B.6 LED

```swift
func bleLedState(onOff: Int)
func bleSetLedState(onOff: Int)
```

### B.7 File list / sync / data

```swift
func bleFileList(bleFiles: [BleFile])
func bleSyncFileHead(sessionId: Int, status: Int)
func bleSyncFileTail(sessionId: Int, crc: Int)
func bleSyncFileStop()
func bleData(sessionId: Int, start: Int, data: Data)
func bleDataComplete()
func blePcmData(sessionId: Int, millsec: Int, pcmData: Data, isMusic: Bool)  // live waveform
func bleDeleteFile(sessionId: Int, status: Int)
func bleClearAllFile(status: Int)
func bleMarking(sessionId: Int, status: Int, markList: [UInt32])
func bleGetRecordMarkingTags(uid: Int, totals: Int, index: Int, tags: [BleRecordMarkingTag])
func deviceLogData(start: Int, data: Data, logType: Int)
```

### B.8 WiFi (BLE-side)

```swift
func bleWiFiOpen(_ status: Int, _ wifiName: String, _ wholeName: String, _ wifiPass: String)
func bleWiFiClose(_ status: Int)
func bleSetWiFiSsid(status: Int)
func bleGetWiFiSsid(status: Int, ssid: String?)
func bleWebsocketProfile(_ type: Int, _ conent: String?)
func bleWebsocketTest(_ status: Int)
```

### B.9 OTA

```swift
func bleFotaResult(uid: Int, status: Int, errmsg: String?)
func bleFotaPackReq(uid: Int, start: Int, end: Int)
func bleFotaPackFin(uid: Int, status: Int, errmsg: String?)
```

### B.10 Binary-file transfer

```swift
func onBinaryFileReq(type: Int, packageOffset: Int, packageSize: Int, endStatus: Int)
func onBinaryFileEnd(result: Int)
```

### B.11 Sync-when-idle WiFi

```swift
func onSyncIdleWifiConfigReceived(index: UInt32, ssid: String, password: String)
func onSyncIdleWifiConfigSet(result: Int)
func onSyncIdleWifiListReceived(list: [UInt32])
func onSyncIdleWifiDeleteResult(result: Int)
func onSyncIdleWifiTestStarted(index: UInt32)
func onSyncIdleWillStart(seconds: Int)
func onSyncIdleWifiTestResult(index: UInt32, result: Int, rawCode: Int)
```

### B.12 Find-My / common params / SoundPlus / SD flash

```swift
func onResetFindmyResult(result: Int)
func onCommonParamsSetResult(success: Bool, dataType: Int, value: String?)
func onCommonParamsGetResult(success: Bool, dataType: Int, value: String?)
func onSetSoundPlusTokenResult(licenseKey: String)
func onGetSDFlashCIDResult(cid: String)
```

### B.13 Device-log sync

```swift
func onGetDeviceLogList(data: Data)
func onSyncDeviceLogStart(data: Data)
func onSyncDeviceLogStop()
func onSyncDeviceLogEnd(data: Data)
func onDeviceLogDeleted(data: Data)
```

### B.14 Companion protocols

```swift
// OtaProtocol — a focused subset of the FOTA callbacks (BleAgent.otaDelegate)
protocol OtaProtocol {
    func bleFotaResult(uid: Int, status: Int, errmsg: String?)
    func bleFotaPackReq(uid: Int, start: Int, end: Int)
    func bleFotaPackFin(uid: Int, status: Int, errmsg: String?)
}

// GlassProtocol — wearable-accessory data (BleAgent.glassDelegate)
protocol GlassProtocol {
    func glassData(_ delFlag: Int, _ dataArr: [GlassData])
    func glassDataClear(_ status: Int)
}
```

