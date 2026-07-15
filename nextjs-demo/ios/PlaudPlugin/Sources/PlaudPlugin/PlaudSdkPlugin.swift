import Foundation
import Capacitor
import PlaudDeviceBasicSDK
import PlaudBleSDK

/// Capacitor bridge over Plaud's native iOS SDK.
///
/// Capacitor auto-registers any class that is `@objc`, subclasses `CAPPlugin`, and
/// conforms to `CAPBridgedPlugin` — but only for npm-installed plugins listed in
/// `capacitor.config.json`'s `packageClassList`. This one lives in a local SwiftPM
/// package, so it is registered by hand in `MainViewController.capacitorDidLoad()`.
/// The JS side reaches it via `registerPlugin('PlaudSdk')`.
///
/// Surface: connection lifecycle (`initSDK`/`startScan`/`stopScan`/`connectBleDevice`/
/// `disconnect`/`isConnected`), file listing (`getFileList`), and on-device audio
/// decode/export (`exportAudio`). SDK results are delivered either through the
/// `PlaudDeviceAgentProtocol` delegate (forwarded as plugin events) or, for export,
/// through a per-call `AudioExportCallback`.
@objc(PlaudSdk)
public class PlaudSdkPlugin: CAPPlugin, CAPBridgedPlugin, PlaudDeviceAgentProtocol {
    public let identifier = "PlaudSdk"
    public let jsName = "PlaudSdk"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "initSDK", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startScan", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopScan", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connectBleDevice", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "depair", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isConnected", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getFileList", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exportAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "readFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "putBinary", returnType: CAPPluginReturnPromise)
    ]

    /// `connectBleDevice` needs the actual `BleDevice` the SDK handed us during a scan —
    /// JS only carries identifiers, so we retain the scanned objects and look them up.
    /// Keyed by `uuid` (the CoreBluetooth peripheral id). Touched only on the main queue.
    private var scannedDevices: [String: BleDevice] = [:]

    /// Retains in-flight export callback bridges so neither they nor their `CAPPluginCall`
    /// are deallocated before the SDK finishes. Touched only on the main queue.
    private var exportCallbacks: Set<ExportCallbackBridge> = []

    /// App-level user identifier, remembered from `initSDK`. The native app passes this
    /// as the `deviceToken` on every connect — it's what binds the device to the user
    /// during the handshake — so we default to it when JS doesn't pass one explicitly.
    private var userId: String?

    /// Poll counter for the Bluetooth power-on gate (see `attemptScanWhenReady`).
    private var scanReadyAttempts = 0
    /// True while a scan is desired. Cleared by `stopScan`/`connectBleDevice` so a pending
    /// power-on poll doesn't fire a stray scan after the user moved on. Main queue only.
    private var isScanning = false

    // MARK: - Connection lifecycle

    @objc func initSDK(_ call: CAPPluginCall) {
        guard let token = call.getString("userAccessToken"), !token.isEmpty else {
            call.reject("userAccessToken is required")
            return
        }
        guard let domain = call.getString("customDomain"), !domain.isEmpty else {
            call.reject("customDomain is required (domain only, no https://)")
            return
        }
        let userId = call.getString("userId")
        DispatchQueue.main.async {
            self.userId = userId
            let agent = PlaudDeviceAgent.shared
            agent.delegate = self
            agent.initSDK(userAccessToken: token, customDomain: domain)
            call.resolve()
        }
    }

    @objc func startScan(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            // CoreBluetooth silently drops scanForPeripherals until the central manager
            // reaches .poweredOn — which happens asynchronously after initSDK and is gated
            // on the first-launch Bluetooth permission prompt. Firing startScan() before
            // then discovers nothing, so gate the real scan on the power-on state (as the
            // native DeviceManager does).
            self.isScanning = true
            self.scanReadyAttempts = 0
            self.attemptScanWhenReady()
            call.resolve()
        }
    }

    /// Fires the SDK scan once Bluetooth is powered on, polling ~18s to cover the
    /// cold-start power-on delay and the first-launch permission prompt. Main queue only.
    private func attemptScanWhenReady() {
        // Bail if scanning was cancelled (stopScan / connect) while we were waiting.
        guard isScanning else { return }
        if BleAgent.shared.isPoweredOn {
            PlaudDeviceAgent.shared.startScan()
            return
        }
        scanReadyAttempts += 1
        if scanReadyAttempts > 60 {
            self.notify("scanTimeout", ["reason": "bluetoothNotPoweredOn"])
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.attemptScanWhenReady()
        }
    }

    @objc func stopScan(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.isScanning = false
            PlaudDeviceAgent.shared.stopScan()
            call.resolve()
        }
    }

    /// Connect to a device surfaced by a prior `scanResult`, identified by `uuid`
    /// (preferred) or `serialNumber`. Connection progress arrives via the `connectState`
    /// and `penState` events. Pass an optional `deviceToken` for a pre-bound device.
    @objc func connectBleDevice(_ call: CAPPluginCall) {
        let uuid = call.getString("uuid")
        let serial = call.getString("serialNumber")
        // The native app always connects with a device token (the app-level userId) so the
        // handshake binds the device to the user. Prefer an explicit token, else fall back
        // to the userId remembered from initSDK.
        let token = call.getString("deviceToken") ?? self.userId
        DispatchQueue.main.async {
            self.isScanning = false
            guard let device = self.lookupDevice(uuid: uuid, serialNumber: serial) else {
                call.reject("Unknown device — scan first, then connect by uuid or serialNumber")
                return
            }
            if let token = token, !token.isEmpty {
                PlaudDeviceAgent.shared.connectBleDevice(bleDevice: device, deviceToken: token)
            } else {
                PlaudDeviceAgent.shared.connectBleDevice(bleDevice: device)
            }
            call.resolve()
        }
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            PlaudDeviceAgent.shared.disconnect()
            call.resolve()
        }
    }

    /// Unpair (depair) the device. With `clear: true` (default) the SDK also clears the
    /// local pairing/binding state, so the next connect starts a fresh handshake. The
    /// result is reported asynchronously via the `depair` event.
    @objc func depair(_ call: CAPPluginCall) {
        let clear = call.getBool("clear") ?? true
        DispatchQueue.main.async {
            PlaudDeviceAgent.shared.depair(clear: clear)
            call.resolve()
        }
    }

    @objc func isConnected(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(["connected": PlaudDeviceAgent.shared.isConnected()])
        }
    }

    // MARK: - Files

    /// Request the on-device recording list starting from `startSessionId` (default 0).
    /// Results arrive asynchronously via the `fileList` event.
    @objc func getFileList(_ call: CAPPluginCall) {
        let start = call.getInt("startSessionId") ?? 0
        DispatchQueue.main.async {
            PlaudDeviceAgent.shared.getFileList(startSessionId: start)
            call.resolve()
        }
    }

    /// Decode a recording and write it to the app's Documents/PlaudExports directory.
    /// Resolves with `{ outputPath, sessionId }` on completion; emits `exportProgress`
    /// events along the way. `format` is one of pcm|mp3|wav|opus (default mp3, which is
    /// playable by AVAudioPlayer and accepted by the transcription upload).
    @objc func exportAudio(_ call: CAPPluginCall) {
        guard let sessionId = call.getInt("sessionId") else {
            call.reject("sessionId is required")
            return
        }
        let format = Self.exportFormat(from: call.getString("format"))
        let channels = call.getInt("channels") ?? 1
        DispatchQueue.main.async {
            let dir = FileManager.default
                .urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("PlaudExports", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

            let bridge = ExportCallbackBridge(sessionId: sessionId, call: call, plugin: self)
            self.exportCallbacks.insert(bridge)
            PlaudDeviceAgent.shared.exportAudio(
                sessionId: sessionId,
                outputDir: dir.path,
                format: format,
                channels: channels,
                callback: bridge
            )
        }
    }

    /// Read a file previously written by `exportAudio` and return its bytes as a base64
    /// string. The WebView loads a remote origin (see `capacitor.config.ts`), so the
    /// `capacitor://localhost/_capacitor_file_/…` URL from `convertFileSrc()` is *not*
    /// fetchable from JS — it's a cross-origin request to a custom scheme and WKWebView's
    /// CORS check blocks it. JS reads export bytes through this bridge instead. Accepts
    /// either a raw filesystem path or a `capacitor://…/_capacitor_file_/…` URL.
    @objc func readFile(_ call: CAPPluginCall) {
        guard let path = call.getString("path"), !path.isEmpty else {
            call.reject("path is required")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let fileURL = Self.resolveFileURL(from: path)
            do {
                let data = try Data(contentsOf: fileURL)
                call.resolve(["data": data.base64EncodedString()])
            } catch {
                call.reject("Failed to read file at \(fileURL.path): \(error.localizedDescription)")
            }
        }
    }

    /// PUT raw bytes (base64-encoded in `data`) to an arbitrary URL via a native
    /// `URLSession` request, resolving with `{ status, etag }`. Used for the S3 presigned
    /// multipart part uploads in the transcription flow: the WebView loads a remote origin,
    /// so a browser `fetch(PUT)` to the S3 presigned URL is blocked by CORS (and the `ETag`
    /// response header wouldn't be readable without the bucket setting `ExposeHeaders`). A
    /// native request has no CORS restrictions and can read every response header.
    @objc func putBinary(_ call: CAPPluginCall) {
        guard let urlString = call.getString("url"), let url = URL(string: urlString) else {
            call.reject("url is required")
            return
        }
        guard let base64 = call.getString("data"), let body = Data(base64Encoded: base64) else {
            call.reject("data (base64-encoded body) is required")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        // Only set Content-Type when the caller asks — S3 presigned signatures often don't
        // include it, and sending an unsigned header can trigger SignatureDoesNotMatch.
        if let contentType = call.getString("contentType") {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        let task = URLSession.shared.uploadTask(with: request, from: body) { _, response, error in
            if let error = error {
                call.reject("PUT failed: \(error.localizedDescription)")
                return
            }
            guard let http = response as? HTTPURLResponse else {
                call.reject("PUT failed: no HTTP response")
                return
            }
            let etag = http.value(forHTTPHeaderField: "ETag")
                ?? http.value(forHTTPHeaderField: "Etag")
            call.resolve(["status": http.statusCode, "etag": etag as Any])
        }
        task.resume()
    }

    // MARK: - PlaudDeviceAgentProtocol

    // Required member. Surfaces the handshake / pen state to JS.
    public func blePenState(state: Int, privacy: Int, keyState: Int, uDisk: Int,
                            findMyToken: Int, hasSndpKey: Int, deviceAccessToken: Int) {
        notify("penState", [
            "state": state,
            "privacy": privacy,
            "keyState": keyState,
            "uDisk": uDisk,
            "findMyToken": findMyToken,
            "hasSndpKey": hasSndpKey,
            "deviceAccessToken": deviceAccessToken
        ])
    }

    public func bleScanResult(bleDevices: [BleDevice]) {
        DispatchQueue.main.async {
            for d in bleDevices { self.scannedDevices[d.uuid] = d }
        }
        let devices = bleDevices.map { d -> [String: Any] in
            [
                "name": d.name,
                "uuid": d.uuid,
                "serialNumber": d.serialNumber,
                "rssi": d.rssi,
                "supportWiFi": d.supportWiFi
            ]
        }
        notify("scanResult", ["devices": devices])
    }

    public func bleScanOverTime() {
        notify("scanTimeout", [:])
    }

    public func bleConnectState(state: Int) {
        // 1 = connected, 0 = disconnected, {2, -1, -2} = connection/handshake failure.
        // Distinguish failure from a normal disconnect so the UI doesn't sit on
        // "connecting…" forever (matches the native DeviceManager mapping).
        let failed = (state == 2 || state == -1 || state == -2)
        notify("connectState", [
            "connected": state == 1,
            "failed": failed,
            "state": state
        ])
    }

    public func bleBind(sn: String?, status: Int, protVersion: Int, timezone: Int) {
        notify("bind", ["sn": sn as Any, "status": status, "protVersion": protVersion])
    }

    // MARK: - Recording (device-initiated)

    // These are driven by the physical device (button press / VAD), not by the app —
    // there are no start/stop record methods on the JS surface. Previously the plugin
    // implemented none of them, so the SDK's `onRecordStart`/`bleRecordStop` internal
    // logs were the *only* trace of recording; nothing reached JS. Forward all four as
    // events so the UI can react (e.g. refresh the file list once a recording stops).

    public func bleRecordStart(sessionId: Int, start: Int, status: Int, scene: Int,
                               startTime: Int, reason: Int) {
        notify("recordStart", [
            "sessionId": sessionId, "start": start, "status": status,
            "scene": scene, "startTime": startTime, "reason": reason
        ])
    }

    public func bleRecordStop(sessionId: Int, reason: Int, fileExist: Bool, fileSize: Int) {
        notify("recordStop", [
            "sessionId": sessionId, "reason": reason,
            "fileExist": fileExist, "fileSize": fileSize
        ])
    }

    public func bleRecordPause(sessionId: Int, reason: Int, fileExist: Bool, fileSize: Int) {
        notify("recordPause", [
            "sessionId": sessionId, "reason": reason,
            "fileExist": fileExist, "fileSize": fileSize
        ])
    }

    public func bleRecordResume(sessionId: Int, start: Int, status: Int, scene: Int,
                                startTime: Int) {
        notify("recordResume", [
            "sessionId": sessionId, "start": start, "status": status,
            "scene": scene, "startTime": startTime
        ])
    }

    public func bleDepair(_ status: Int) {
        notify("depair", ["status": status])
    }

    public func bleFileList(bleFiles: [BleFile]) {
        let files = bleFiles.map { f -> [String: Any] in
            [
                "sn": f.sn,
                "sessionId": f.sessionId,
                "size": f.size,
                "scenes": f.scenes,
                "channels": f.channels,
                "isOgg": f.isOgg,
                "isMusic": f.isMusic,
                "duration": f.duration()
            ]
        }
        notify("fileList", ["files": files])
    }

    // MARK: - Helpers

    private func lookupDevice(uuid: String?, serialNumber: String?) -> BleDevice? {
        if let uuid = uuid, let d = scannedDevices[uuid] { return d }
        if let serial = serialNumber {
            return scannedDevices.values.first { $0.serialNumber == serial }
        }
        return nil
    }

    /// Turn whatever JS passed — a raw path, a `file://` URL, or a
    /// `capacitor://localhost/_capacitor_file_/<path>` URL from `convertFileSrc()` — into a
    /// real on-disk file URL.
    private static func resolveFileURL(from path: String) -> URL {
        if let marker = path.range(of: "_capacitor_file_") {
            let raw = String(path[marker.upperBound...])
            let decoded = raw.removingPercentEncoding ?? raw
            return URL(fileURLWithPath: decoded)
        }
        if let url = URL(string: path), url.isFileURL {
            return url
        }
        return URL(fileURLWithPath: path)
    }

    private static func exportFormat(from raw: String?) -> AudioExportFormat {
        switch (raw ?? "mp3").lowercased() {
        case "pcm": return .pcm
        case "wav": return .wav
        case "opus": return .opus
        default: return .mp3
        }
    }

    fileprivate func notify(_ event: String, _ data: [String: Any]) {
        DispatchQueue.main.async { [weak self] in
            self?.notifyListeners(event, data: data)
        }
    }

    fileprivate func finishExport(_ bridge: ExportCallbackBridge) {
        DispatchQueue.main.async { [weak self] in
            self?.exportCallbacks.remove(bridge)
        }
    }
}

/// Adapts the SDK's per-call `AudioExportCallback` to the plugin: progress becomes an
/// `exportProgress` event, and completion/error resolves/rejects the originating call.
private final class ExportCallbackBridge: NSObject, AudioExportCallback {
    private let sessionId: Int
    private let call: CAPPluginCall
    private weak var plugin: PlaudSdkPlugin?

    init(sessionId: Int, call: CAPPluginCall, plugin: PlaudSdkPlugin) {
        self.sessionId = sessionId
        self.call = call
        self.plugin = plugin
    }

    func onProgress(_ progress: Int, message: String) {
        plugin?.notify("exportProgress", [
            "sessionId": sessionId, "progress": progress, "message": message
        ])
    }

    func onComplete(outputPath: String) {
        call.resolve(["sessionId": sessionId, "outputPath": outputPath])
        plugin?.finishExport(self)
    }

    func onError(_ error: String) {
        call.reject(error)
        plugin?.finishExport(self)
    }
}
