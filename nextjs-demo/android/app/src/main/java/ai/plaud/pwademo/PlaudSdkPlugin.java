package ai.plaud.pwademo;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.tinnotech.penblesdk.TntAgent;
import com.tinnotech.penblesdk.core.IBleAgent;
import com.tinnotech.penblesdk.entity.BleDevice;
import com.tinnotech.penblesdk.entity.BleFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import sdk.PlaudDeviceAgent;
import sdk.PlaudDeviceAgentListener;
import sdk.audio.AudioExportFormat;
import sdk.audio.AudioExporter;

/**
 * Capacitor bridge over Plaud's native Android SDK (libs/plaud-sdk.aar) — the Android
 * counterpart to ios/PlaudPlugin/Sources/PlaudPlugin/PlaudSdkPlugin.swift.
 *
 * <p>Capacitor auto-registers plugins it finds in npm packages; this one is app-local, so
 * it is registered by hand in {@link MainActivity#onCreate} (the analogue of iOS's
 * {@code MainViewController.capacitorDidLoad()}). The JS side reaches it via
 * {@code registerPlugin('PlaudSdk')} in lib/plaud-sdk.ts — the same single JS surface
 * serves both platforms.
 *
 * <p>Surface, identical to iOS: connection lifecycle ({@code initSDK}/{@code startScan}/
 * {@code stopScan}/{@code connectBleDevice}/{@code disconnect}/{@code depair}/
 * {@code isConnected}), file listing ({@code getFileList}), on-device audio decode/export
 * ({@code exportAudio}), and the two remote-origin workarounds ({@code readFile}/
 * {@code putBinary}). SDK results arrive either through the
 * {@link PlaudDeviceAgentListener} callbacks (forwarded as plugin events) or, for export,
 * through a per-call {@link AudioExporter.ExportCallback}.
 *
 * <p>Where the two platforms genuinely differ, the difference is noted at the call site.
 * The three substantive ones:
 * <ul>
 *   <li>Android gates BLE behind runtime permissions; iOS does not. {@code startScan}
 *       requests them itself so the JS flow stays identical on both platforms.</li>
 *   <li>Android identifies a scanned device by MAC address, iOS by CoreBluetooth UUID.
 *       {@code scanResult} emits the MAC as {@code uuid} so {@code connectBleDevice}
 *       works unchanged, plus an explicit {@code macAddress} field.</li>
 *   <li>Android's {@code blePenState} carries 4 values where iOS carries 7; the three
 *       iOS-only ones are omitted rather than faked. See {@link #blePenState}.</li>
 * </ul>
 */
@CapacitorPlugin(
    name = "PlaudSdk",
    permissions = {
        @Permission(alias = PlaudSdkPlugin.PERM_SCAN, strings = { Manifest.permission.BLUETOOTH_SCAN }),
        @Permission(alias = PlaudSdkPlugin.PERM_CONNECT, strings = { Manifest.permission.BLUETOOTH_CONNECT }),
        @Permission(alias = PlaudSdkPlugin.PERM_LOCATION, strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public class PlaudSdkPlugin extends Plugin implements PlaudDeviceAgentListener {

    static final String PERM_SCAN = "bluetoothScan";
    static final String PERM_CONNECT = "bluetoothConnect";
    static final String PERM_LOCATION = "location";

    /**
     * {@code connectBleDevice} needs the actual {@link BleDevice} the SDK handed us during a
     * scan — JS only carries identifiers, so we retain the scanned objects and look them up.
     * Keyed by MAC address. Touched only on the main thread.
     */
    private final Map<String, BleDevice> scannedDevices = new LinkedHashMap<>();

    /**
     * Retains in-flight export bridges so neither they nor their {@link PluginCall} are
     * collected before the SDK finishes. Touched only on the main thread.
     */
    private final Set<ExportCallbackBridge> exportCallbacks = new HashSet<>();

    /**
     * App-level user identifier, remembered from {@code initSDK}. The native app passes this
     * as the {@code deviceToken} on every connect — it's what binds the device to the user
     * during the handshake — so we default to it when JS doesn't pass one explicitly.
     */
    private String userId;

    /** Poll counter for the Bluetooth power-on gate (see {@link #attemptScanWhenReady}). */
    private int scanReadyAttempts = 0;

    /**
     * True while a scan is desired. Cleared by {@code stopScan}/{@code connectBleDevice} so a
     * pending power-on poll doesn't fire a stray scan after the user moved on. Main thread only.
     */
    private boolean isScanning = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    // MARK: - Connection lifecycle

    @PluginMethod
    public void initSDK(PluginCall call) {
        String token = call.getString("userAccessToken");
        if (token == null || token.isEmpty()) {
            call.reject("userAccessToken is required");
            return;
        }
        String domain = call.getString("customDomain");
        if (domain == null || domain.isEmpty()) {
            call.reject("customDomain is required (domain only, no https://)");
            return;
        }
        final String uid = call.getString("userId");
        main.post(() -> {
            this.userId = uid;
            PlaudDeviceAgent.setListener(this);
            // The SDK prefixes customDomain with "https://" itself, so it takes the domain
            // only — the same contract as iOS. The Context is Android-only; the SDK keeps
            // the application context internally.
            PlaudDeviceAgent.initSDK(getContext(), token, domain);
            call.resolve();
        });
    }

    @PluginMethod
    public void startScan(PluginCall call) {
        // Android-only step with no iOS counterpart: BLE scanning is behind runtime
        // permissions (BLUETOOTH_SCAN/CONNECT on API 31+, fine location below that). We
        // request them here rather than exposing a separate JS method, so app/page.tsx's
        // flow is byte-identical across platforms.
        if (missingPermissionAliases().isEmpty()) {
            beginScan(call);
            return;
        }
        String[] aliases = missingPermissionAliases().toArray(new String[0]);
        requestPermissionForAliases(aliases, call, "scanPermissionCallback");
    }

    @PermissionCallback
    private void scanPermissionCallback(PluginCall call) {
        if (!missingPermissionAliases().isEmpty()) {
            // Mirrors the iOS timeout path so the UI doesn't sit on "scanning…" forever.
            emit("scanTimeout", jsObject("reason", "permissionDenied"));
            call.reject("Bluetooth permissions denied");
            return;
        }
        beginScan(call);
    }

    private void beginScan(PluginCall call) {
        main.post(() -> {
            // A BLE scan started before the adapter is on silently discovers nothing, and
            // the adapter turns on asynchronously. Gate the real scan on the powered-on
            // state, exactly as the iOS plugin gates on CoreBluetooth's .poweredOn.
            isScanning = true;
            scanReadyAttempts = 0;
            attemptScanWhenReady();
            call.resolve();
        });
    }

    /**
     * Fires the SDK scan once Bluetooth is powered on, polling ~18s to cover the adapter
     * power-on delay. Main thread only.
     */
    private void attemptScanWhenReady() {
        // Bail if scanning was cancelled (stopScan / connect) while we were waiting.
        if (!isScanning) return;
        if (isBluetoothPoweredOn()) {
            PlaudDeviceAgent.startScan();
            return;
        }
        scanReadyAttempts += 1;
        if (scanReadyAttempts > 60) {
            emit("scanTimeout", jsObject("reason", "bluetoothNotPoweredOn"));
            return;
        }
        main.postDelayed(this::attemptScanWhenReady, 300);
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        main.post(() -> {
            isScanning = false;
            PlaudDeviceAgent.stopScan();
            call.resolve();
        });
    }

    /**
     * Connect to a device surfaced by a prior {@code scanResult}, identified by {@code uuid}
     * (the MAC address on Android) or {@code serialNumber}. Connection progress arrives via
     * the {@code connectState} and {@code penState} events. Pass an optional
     * {@code deviceToken} for a pre-bound device.
     */
    @PluginMethod
    public void connectBleDevice(PluginCall call) {
        final String uuid = call.getString("uuid");
        final String serial = call.getString("serialNumber");
        // The native app always connects with a device token (the app-level userId) so the
        // handshake binds the device to the user. Prefer an explicit token, else fall back
        // to the userId remembered from initSDK.
        String explicit = call.getString("deviceToken");
        final String token = explicit != null ? explicit : this.userId;
        main.post(() -> {
            isScanning = false;
            BleDevice device = lookupDevice(uuid, serial);
            if (device == null) {
                call.reject("Unknown device — scan first, then connect by uuid or serialNumber");
                return;
            }
            if (token != null && !token.isEmpty()) {
                PlaudDeviceAgent.connectBleDevice(device, token);
            } else {
                PlaudDeviceAgent.connectBleDevice(device);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        main.post(() -> {
            PlaudDeviceAgent.disconnect();
            call.resolve();
        });
    }

    /**
     * Unpair (depair) the device. With {@code clear: true} (default) the SDK also clears the
     * local pairing/binding state, so the next connect starts a fresh handshake. The result
     * is reported asynchronously via the {@code depair} event.
     */
    @PluginMethod
    public void depair(PluginCall call) {
        final boolean clear = Boolean.TRUE.equals(call.getBoolean("clear", true));
        main.post(() -> {
            PlaudDeviceAgent.depair(clear);
            call.resolve();
        });
    }

    @PluginMethod
    public void isConnected(PluginCall call) {
        main.post(() -> call.resolve(jsObject("connected", PlaudDeviceAgent.isConnected())));
    }

    // MARK: - Files

    /**
     * Request the on-device recording list starting from {@code startSessionId} (default 0).
     * Results arrive asynchronously via the {@code fileList} event.
     */
    @PluginMethod
    public void getFileList(PluginCall call) {
        // Read as long, not via call.getInt(): every sessionId in the Android SDK is a long
        // (getFileList/exportAudio/BleFile.getSessionId), so a 32-bit read would silently
        // truncate an id that doesn't fit.
        final long start = call.getData().optLong("startSessionId", 0L);
        main.post(() -> {
            PlaudDeviceAgent.getFileList(start);
            call.resolve();
        });
    }

    /**
     * Decode a recording and write it to the app's files/PlaudExports directory (the
     * analogue of iOS's Documents/PlaudExports). Resolves with {@code {outputPath, sessionId}}
     * on completion; emits {@code exportProgress} events along the way. {@code format} is one
     * of pcm|mp3|wav|opus (default mp3, which is playable and accepted by the transcription
     * upload).
     */
    @PluginMethod
    public void exportAudio(PluginCall call) {
        // See getFileList: sessionId is a long on the SDK side, so it's read as one here.
        if (!call.getData().has("sessionId")) {
            call.reject("sessionId is required");
            return;
        }
        final long session = call.getData().optLong("sessionId");
        final AudioExportFormat format = exportFormat(call.getString("format"));
        final int channels = call.getInt("channels", 1);
        main.post(() -> {
            File dir = new File(getContext().getFilesDir(), "PlaudExports");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

            ExportCallbackBridge bridge = new ExportCallbackBridge(session, call);
            exportCallbacks.add(bridge);
            PlaudDeviceAgent.exportAudio(session, dir, format, channels, bridge);
        });
    }

    /**
     * Read a file previously written by {@code exportAudio} and return its bytes as a base64
     * string. The WebView loads a remote origin (see capacitor.config.ts), so the
     * {@code https://localhost/_capacitor_file_/…} URL from {@code convertFileSrc()} is *not*
     * fetchable from JS — it's a cross-origin request and the WebView's CORS check blocks it.
     * JS reads export bytes through this bridge instead. Accepts either a raw filesystem path
     * or a {@code …/_capacitor_file_/…} URL.
     */
    @PluginMethod
    public void readFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null || path.isEmpty()) {
            call.reject("path is required");
            return;
        }
        File file = resolveFile(path);
        try {
            byte[] data = readAllBytes(file);
            // NO_WRAP: the platform encoder inserts line breaks by default, and the JS side
            // feeds this straight into atob().
            call.resolve(jsObject("data", Base64.encodeToString(data, Base64.NO_WRAP)));
        } catch (Exception e) {
            call.reject("Failed to read file at " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static byte[] readAllBytes(File file) throws java.io.IOException {
        // java.nio.file.Files and java.util.Base64 are both API 26+, above this app's
        // minSdkVersion of 24, so the whole file path here sticks to the platform APIs.
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * PUT raw bytes (base64-encoded in {@code data}) to an arbitrary URL via a native
     * request, resolving with {@code {status, etag}}. Used for the S3 presigned multipart part
     * uploads in the transcription flow: the WebView loads a remote origin, so a browser
     * {@code fetch(PUT)} to the S3 presigned URL is blocked by CORS (and the {@code ETag}
     * response header wouldn't be readable without the bucket setting {@code ExposeHeaders}).
     * A native request has no CORS restrictions and can read every response header.
     */
    @PluginMethod
    public void putBinary(PluginCall call) {
        String urlString = call.getString("url");
        if (urlString == null || urlString.isEmpty()) {
            call.reject("url is required");
            return;
        }
        String base64 = call.getString("data");
        if (base64 == null) {
            call.reject("data (base64-encoded body) is required");
            return;
        }
        final byte[] body;
        try {
            body = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("data (base64-encoded body) is required");
            return;
        }
        final String contentType = call.getString("contentType");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            // Only set Content-Type when the caller asks — S3 presigned signatures often
            // don't include it, and sending an unsigned header can trigger
            // SignatureDoesNotMatch. Android's HttpURLConnection would otherwise supply a
            // default, so clear it explicitly when the caller passes nothing.
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            } else {
                conn.setRequestProperty("Content-Type", "");
            }
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int status = conn.getResponseCode();
            String etag = conn.getHeaderField("ETag");
            if (etag == null) etag = conn.getHeaderField("Etag");

            JSObject result = new JSObject();
            result.put("status", status);
            result.put("etag", etag);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("PUT failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // MARK: - PlaudDeviceAgentListener

    /**
     * Surfaces the handshake / pen state to JS.
     *
     * <p>Platform difference: iOS's {@code blePenState} delivers seven values — the four here
     * plus {@code findMyToken}, {@code hasSndpKey} and {@code deviceAccessToken}. The Android
     * SDK's callback carries only these four, so the event omits the other three rather than
     * emitting invented zeros; they are optional in the shared TS type.
     */
    @Override
    public void blePenState(int state, int privacy, int keyState, int uDisk) {
        JSObject data = new JSObject();
        data.put("state", state);
        data.put("privacy", privacy);
        data.put("keyState", keyState);
        data.put("uDisk", uDisk);
        emit("penState", data);
    }

    @Override
    public void bleScanResult(List<? extends BleDevice> bleDevices) {
        if (bleDevices == null) return;
        final List<BleDevice> snapshot = new ArrayList<>(bleDevices);
        main.post(() -> {
            for (BleDevice d : snapshot) scannedDevices.put(d.getMacAddress(), d);
        });

        JSArray devices = new JSArray();
        for (BleDevice d : snapshot) {
            JSObject o = new JSObject();
            o.put("name", d.getName());
            // Android has no CoreBluetooth peripheral UUID; the MAC address is the stable
            // scan-time identifier. It is emitted as `uuid` as well so the shared JS
            // connectBleDevice({ uuid }) path is identical on both platforms.
            o.put("uuid", d.getMacAddress());
            o.put("macAddress", d.getMacAddress());
            o.put("serialNumber", d.getSerialNumber());
            o.put("rssi", d.getRssi());
            // The Android scan record carries no WiFi-capability flag (iOS's BleDevice does);
            // the SDK only knows it once connected, via IBleAgent.isSupportWifi().
            o.put("supportWiFi", supportsWiFi());
            devices.put(o);
        }
        emit("scanResult", jsObject("devices", devices));
    }

    @Override
    public void bleScanOverTime() {
        emit("scanTimeout", new JSObject());
    }

    @Override
    public void bleConnectState(int state) {
        // 1 = connected, 0 = disconnected, {2, -1, -2} = connection/handshake failure.
        // Distinguish failure from a normal disconnect so the UI doesn't sit on
        // "connecting…" forever (matches the iOS plugin and the native DeviceManager).
        boolean failed = (state == 2 || state == -1 || state == -2);
        JSObject data = new JSObject();
        data.put("connected", state == 1);
        data.put("failed", failed);
        data.put("state", state);
        emit("connectState", data);
    }

    @Override
    public void bleBind(String sn, int status, int protVersion, int timezone) {
        JSObject data = new JSObject();
        data.put("sn", sn);
        data.put("status", status);
        data.put("protVersion", protVersion);
        emit("bind", data);
    }

    // MARK: - Recording (device-initiated)

    // These are driven by the physical device (button press / VAD), not by the app — there
    // are no start/stop record methods on the JS surface, matching iOS. Forwarded as events
    // so the UI can react (e.g. refresh the file list once a recording stops).

    @Override
    public void bleRecordStart(long sessionId, long start, int status, int scene, long startTime, int reason) {
        JSObject data = new JSObject();
        data.put("sessionId", sessionId);
        data.put("start", start);
        data.put("status", status);
        data.put("scene", scene);
        data.put("startTime", startTime);
        data.put("reason", reason);
        emit("recordStart", data);
    }

    @Override
    public void bleRecordStop(long sessionId, int reason, boolean fileExist, long fileSize) {
        emit("recordStop", recordStopPayload(sessionId, reason, fileExist, fileSize));
    }

    @Override
    public void bleRecordPause(long sessionId, int reason, boolean fileExist, long fileSize) {
        emit("recordPause", recordStopPayload(sessionId, reason, fileExist, fileSize));
    }

    @Override
    public void bleRecordResume(long sessionId, long start, int status, int scene, long startTime) {
        JSObject data = new JSObject();
        data.put("sessionId", sessionId);
        data.put("start", start);
        data.put("status", status);
        data.put("scene", scene);
        data.put("startTime", startTime);
        emit("recordResume", data);
    }

    @Override
    public void bleDepair(int status) {
        emit("depair", jsObject("status", status));
    }

    @Override
    public void bleFileList(List<? extends BleFile> bleFiles) {
        if (bleFiles == null) return;
        // iOS's BleFile carries sn/channels/isOgg per file; Android's carries only
        // sessionId/fileSize/attribute/scene, so those three come from the connected
        // device's stream geometry, which the SDK maintains on TntAgent during the
        // handshake. Same JS shape either way.
        final String sn = connectedSerialNumber();
        final int channels = audioChannels();
        final boolean isOgg = TntAgent.isOggAudio;

        JSArray files = new JSArray();
        for (BleFile f : bleFiles) {
            JSObject o = new JSObject();
            o.put("sn", sn);
            o.put("sessionId", f.getSessionId());
            o.put("size", f.getFileSize());
            o.put("scenes", f.getScene());
            o.put("channels", channels);
            o.put("isOgg", isOgg);
            o.put("isMusic", f.isMusic());
            o.put("duration", durationSeconds(f.getFileSize(), channels));
            files.put(o);
        }
        emit("fileList", jsObject("files", files));
    }

    // MARK: - Helpers

    private JSObject recordStopPayload(long sessionId, int reason, boolean fileExist, long fileSize) {
        JSObject data = new JSObject();
        data.put("sessionId", sessionId);
        data.put("reason", reason);
        data.put("fileExist", fileExist);
        data.put("fileSize", fileSize);
        return data;
    }

    private BleDevice lookupDevice(String uuid, String serialNumber) {
        if (uuid != null) {
            BleDevice d = scannedDevices.get(uuid);
            if (d != null) return d;
        }
        if (serialNumber != null) {
            for (BleDevice d : scannedDevices.values()) {
                if (serialNumber.equals(d.getSerialNumber())) return d;
            }
        }
        return null;
    }

    /**
     * Recording length in seconds, to match the {@code duration} field iOS's
     * {@code BleFile.duration()} fills in. The Android SDK exposes the raw-opus arithmetic
     * as a static helper returning milliseconds: one 20ms frame per
     * {@code 80 * channels} bytes.
     *
     * <p>Note: for ogg-wrapped streams this over-reports slightly, because it doesn't
     * subtract ogg page headers. The SDK's {@code BleFile.calculateOggDuration} does account
     * for them, but takes undocumented page-geometry arguments (one of its five parameters is
     * unused) with no caller in the AAR to copy, so we don't guess at them.
     */
    private static long durationSeconds(long fileSize, int channels) {
        int safeChannels = channels > 0 ? channels : 1;
        long millis = BleFile.calculateOpusDuration(fileSize, safeChannels);
        return millis / 1000;
    }

    /** The connected device's channel count, as the SDK tracks it during the handshake. */
    private static int audioChannels() {
        int channels = TntAgent.OPUS_CHANNEL;
        return channels > 0 ? channels : 1;
    }

    private static String connectedSerialNumber() {
        IBleAgent agent = bleAgent();
        if (agent == null) return "";
        String sn = agent.getSerialNumber();
        return sn != null ? sn : "";
    }

    private static boolean supportsWiFi() {
        IBleAgent agent = bleAgent();
        return agent != null && agent.isSupportWifi();
    }

    private static IBleAgent bleAgent() {
        // TntAgent is only available once the SDK has been initialised and has built its
        // agent, so every accessor above tolerates a null.
        try {
            TntAgent tnt = TntAgent.getInstant();
            return tnt != null ? tnt.getBleAgent() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isBluetoothPoweredOn() {
        BluetoothManager manager =
            (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) return false;
        BluetoothAdapter adapter = manager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /**
     * The BLE permissions this device actually needs, which changed in API 31: from that
     * level on it's BLUETOOTH_SCAN/BLUETOOTH_CONNECT, and below it a location permission
     * (BLUETOOTH/BLUETOOTH_ADMIN are install-time grants there, declared by the AAR's
     * manifest). Requesting the wrong set for the running level would never be granted, so
     * only the applicable aliases are ever requested.
     */
    private List<String> missingPermissionAliases() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed.add(PERM_SCAN);
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(PERM_CONNECT);
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed.add(PERM_LOCATION);
        }
        return needed;
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(getContext(), permission)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Turn whatever JS passed — a raw path, a {@code file://} URL, or a
     * {@code https://localhost/_capacitor_file_/<path>} URL from {@code convertFileSrc()} —
     * into a real on-disk file.
     */
    private static File resolveFile(String path) {
        int marker = path.indexOf("_capacitor_file_");
        if (marker >= 0) {
            String raw = path.substring(marker + "_capacitor_file_".length());
            return new File(Uri.decode(raw));
        }
        if (path.startsWith("file://")) {
            return new File(Uri.parse(path).getPath());
        }
        return new File(path);
    }

    private static AudioExportFormat exportFormat(String raw) {
        // Locale.ROOT, not the default locale: in a Turkish locale "MP3".toLowerCase() yields
        // "mp3" with a dotless ı, which would silently fall through to the default branch.
        String value = raw == null ? "mp3" : raw.toLowerCase(Locale.ROOT);
        switch (value) {
            case "pcm": return AudioExportFormat.PCM;
            case "wav": return AudioExportFormat.WAV;
            case "opus": return AudioExportFormat.OPUS;
            default: return AudioExportFormat.MP3;
        }
    }

    private static JSObject jsObject(String key, Object value) {
        JSObject o = new JSObject();
        o.put(key, value);
        return o;
    }

    private void emit(String event, JSObject data) {
        main.post(() -> notifyListeners(event, data));
    }

    private void finishExport(ExportCallbackBridge bridge) {
        main.post(() -> exportCallbacks.remove(bridge));
    }

    /**
     * Adapts the SDK's per-call {@link AudioExporter.ExportCallback} to the plugin: progress
     * becomes an {@code exportProgress} event, and completion/error resolves/rejects the
     * originating call.
     */
    private final class ExportCallbackBridge implements AudioExporter.ExportCallback {
        private final long sessionId;
        private final PluginCall call;

        ExportCallbackBridge(long sessionId, PluginCall call) {
            this.sessionId = sessionId;
            this.call = call;
        }

        @Override
        public void onProgress(int progress, String message) {
            JSObject data = new JSObject();
            data.put("sessionId", sessionId);
            data.put("progress", progress);
            data.put("message", message);
            emit("exportProgress", data);
        }

        @Override
        public void onComplete(File output) {
            JSObject result = new JSObject();
            result.put("sessionId", sessionId);
            result.put("outputPath", output.getAbsolutePath());
            call.resolve(result);
            finishExport(this);
        }

        @Override
        public void onError(String error) {
            call.reject(error);
            finishExport(this);
        }
    }
}
