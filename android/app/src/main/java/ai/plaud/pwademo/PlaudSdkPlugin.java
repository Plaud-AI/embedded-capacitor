package ai.plaud.pwademo;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.tinnotech.penblesdk.Constants;
import com.tinnotech.penblesdk.TntAgent;
import com.tinnotech.penblesdk.core.IBleAgent;
import com.tinnotech.penblesdk.entity.BleDevice;
import com.tinnotech.penblesdk.entity.BleFile;
import com.tinnotech.penblesdk.entity.BluetoothStatus;
import com.tinnotech.penblesdk.entity.bean.blepkg.response.*;
import com.tinnotech.penblesdk.impl.ble.BleAgentListener;

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
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;

import sdk.NiceBuildSdk;
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

    /** Logcat tag for the whole bridge — {@code adb logcat -s PlaudSdk:V BleAgentImpl:V}. */
    private static final String TAG = "PlaudSdk";

    /**
     * How long {@link #prepareHandshake} waits for the partner key fetch and SN signing before
     * connecting anyway. Same 10s cap the Expo module uses: long enough for the HTTP round
     * trips on a cold start, short enough that a hung fetch doesn't look like a frozen UI.
     */
    private static final long HANDSHAKE_PREP_TIMEOUT_MS = 10_000L;

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

    /**
     * Raw transport-level listener, attached once after {@code initSDK}. See
     * {@link BleAgentDiagnostics} — it exists because {@code PlaudDeviceAgentListener} collapses
     * every connection failure into {@code bleConnectState(2)} with no reason attached.
     */
    private final BleAgentDiagnostics diagnostics = new BleAgentDiagnostics();
    private boolean diagnosticsAttached = false;

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
            // Handshake prerequisite 1 of 3 (see prepareHandshake for 2 and 3). The SDK's
            // Partner API — the gen-key / sn-sign endpoints the device handshake depends on —
            // lives on its own Retrofit client, which hardcodes https://platform-jp.plaud.ai
            // and does *not* follow customDomain. Left alone, a platform-us token 401s on
            // gen-key, the partner RSA key pair never arrives, and every handshake after it
            // fails: the scan still finds the device, connectBleDevice() still resolves, and
            // then connectState reports failed. Repoint it before initSDK kicks off the fetch.
            try {
                NiceBuildSdk.INSTANCE.getPartnerApiManager()
                    .updateBaseUrl("https://" + domain);
            } catch (Throwable t) {
                // Best-effort, matching the Expo module: a connect attempt against the
                // default host is still better than failing initSDK outright.
                Log.w(TAG, "could not repoint the Partner API base URL — handshakes will "
                    + "fail unless this token is valid on platform-jp", t);
            }
            // The SDK prefixes customDomain with "https://" itself, so it takes the domain
            // only — the same contract as iOS. The Context is Android-only; the SDK keeps
            // the application context internally.
            PlaudDeviceAgent.initSDK(getContext(), token, domain);
            attachDiagnostics();
            Log.i(TAG, "initSDK domain=" + domain + " userId=" + uid);
            call.resolve();
        });
    }

    /**
     * Attach {@link BleAgentDiagnostics} to the SDK's transport agent. Only possible once
     * {@code initSDK} has built {@code TntAgent}, and only worth doing once.
     */
    private void attachDiagnostics() {
        if (diagnosticsAttached) return;
        TntAgent tnt = TntAgent.getInstant();
        if (tnt == null) {
            Log.w(TAG, "TntAgent unavailable after initSDK — connection diagnostics disabled");
            return;
        }
        tnt.addBleAgentListeners(diagnostics);
        diagnosticsAttached = true;
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
            Log.i(TAG, "startScan");
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
            Log.i(TAG, "stopScan");
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
        // Android-only step with no iOS counterpart: opening a GATT connection needs
        // BLUETOOTH_CONNECT on API 31+, and the SDK swallows the SecurityException it would
        // otherwise throw (BleAgentImpl wraps the whole connect block in a bare `catch
        // (Exception)` that only logs) — the connect would then fail completely silently, with
        // no connectState event at all. Request it here instead. Normally it was already
        // granted alongside BLUETOOTH_SCAN in startScan, so this is a no-op.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissionForAlias(PERM_CONNECT, call, "connectPermissionCallback");
            return;
        }
        beginConnect(call);
    }

    @PermissionCallback
    private void connectPermissionCallback(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            emit("connectFail", jsObject("reason", "permissionDenied"));
            call.reject("BLUETOOTH_CONNECT permission denied — cannot open a connection");
            return;
        }
        beginConnect(call);
    }

    private void beginConnect(PluginCall call) {
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
            if (bleAgent() == null) {
                call.reject("SDK not initialised — call initSDK before connecting");
                return;
            }
            if (!isBluetoothPoweredOn()) {
                call.reject("Bluetooth is off");
                return;
            }
            // Always stop the scan natively rather than trusting the caller to have done it.
            // An LE scan running concurrently with connectGatt() is the classic source of
            // Android's GATT error 133, and the SDK's connectBleDevice does not stop it
            // itself. Harmless when the scan is already stopped.
            PlaudDeviceAgent.stopScan();
            // Handshake prerequisites 2 and 3 are asynchronous, so the actual connect happens
            // in the callback rather than inline here.
            prepareHandshake(device, () -> performConnect(call, device, token));
        });
    }

    /**
     * Handshake prerequisites 2 and 3 of 3, which the iOS SDK performs internally but the
     * Android one leaves to the caller (prerequisite 1 is the Partner API base URL, set in
     * {@link #initSDK}). Skipping either looks exactly like a Bluetooth problem from JS: the
     * scan finds the device, {@code connectBleDevice()} resolves, then {@code connectState}
     * reports {@code failed}.
     *
     * <ol>
     *   <li>Wait for the partner RSA key pair. {@code initSDK} fetches it over HTTP
     *       asynchronously, so on a cold start it is usually still in flight by the time the
     *       user taps a scan result. {@code ensurePartnerDataReady} returns immediately when
     *       the data is already there.</li>
     *   <li>Sign and store the device's serial number — the handshake reads the stored
     *       {@code snSignature}, and sends an empty one without this.</li>
     * </ol>
     *
     * <p>Both steps are best-effort: on failure we connect anyway and let the SDK report the
     * real error through {@code bleConnectFail}, which matches the Expo module and Plaud's own
     * reference apps. {@code done} always runs, exactly once, on the main thread — a hung
     * partner fetch must not strand the {@link PluginCall} unresolved forever, so a
     * {@link #HANDSHAKE_PREP_TIMEOUT_MS} deadline races the callbacks.
     */
    private void prepareHandshake(BleDevice device, Runnable done) {
        // Set on the main thread only, so the timeout and the callback path can't both win.
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable proceed = () -> main.post(() -> {
            if (fired.compareAndSet(false, true)) done.run();
        });
        main.postDelayed(proceed, HANDSHAKE_PREP_TIMEOUT_MS);

        // signAndStoreDeviceSn is a Kotlin suspend function, uncallable from Java; the SDK
        // ships these two callback wrappers over it and over the partner-data fetch, both of
        // which hop to Dispatchers.IO themselves.
        try {
            NiceBuildSdk.ensurePartnerDataReady(ready -> {
                if (!Boolean.TRUE.equals(ready)) {
                    Log.w(TAG, "partner data (RSA key pair) is not ready — the handshake will "
                        + "likely fail; check that the userAccessToken is valid for the "
                        + "customDomain passed to initSDK");
                }
                String sn = device.getSerialNumber();
                if (sn == null || sn.isEmpty()) {
                    // Nothing to sign — a scan result without an SN can't be bound anyway.
                    proceed.run();
                    return Unit.INSTANCE;
                }
                try {
                    NiceBuildSdk.signDeviceSnAsync(deviceType(sn), sn, signed -> {
                        Log.i(TAG, "snSign sn=" + sn + " type=" + deviceType(sn)
                            + " ok=" + signed);
                        proceed.run();
                        return Unit.INSTANCE;
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "signDeviceSnAsync failed — connecting without an snSignature", t);
                    proceed.run();
                }
                return Unit.INSTANCE;
            });
        } catch (Throwable t) {
            Log.w(TAG, "ensurePartnerDataReady failed — connecting without partner data", t);
            proceed.run();
        }
    }

    /**
     * The connect itself, once {@link #prepareHandshake} has satisfied its prerequisites.
     * Main thread only.
     */
    private void performConnect(PluginCall call, BleDevice device, String token) {
        // The handshake's bind token is not the deviceToken we pass in — the SDK derives
        // it from the `sub` claim of the initSDK JWT. When that comes back empty (token
        // isn't a 3-part JWT, or carries no `sub`), the pen handshake aborts immediately
        // with the SDK's misleading UUID_IS_EMPTY code. Surface it here so that failure
        // reads as "wrong access token" rather than "Bluetooth problem".
        String bindToken = handshakeToken();
        if (bindToken == null || bindToken.isEmpty()) {
            Log.w(TAG, "handshake bind token is empty — the SDK could not read a `sub` "
                + "claim from the initSDK userAccessToken; the pen handshake will fail "
                + "with UUID_IS_EMPTY");
        }
        Log.i(TAG, "connect mac=" + device.getMacAddress()
            + " sn=" + device.getSerialNumber()
            + " version=" + device.getVersionName()
            + " bond=" + bondState(device.getMacAddress())
            + " partnerReady=" + isPartnerDataReady()
            + " bindToken=" + (bindToken == null || bindToken.isEmpty() ? "<EMPTY>" : bindToken)
            + " deviceToken=" + (token == null || token.isEmpty() ? "<none>" : token));
        if (token != null && !token.isEmpty()) {
            PlaudDeviceAgent.connectBleDevice(device, token);
        } else {
            PlaudDeviceAgent.connectBleDevice(device);
        }
        call.resolve();
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
        Log.i(TAG, "connectState state=" + state + " failed=" + failed);
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

    /**
     * The bind token the SDK will actually put on the wire during the pen handshake — the
     * {@code sub} claim of the {@code initSDK} JWT. Tolerates a null because it depends on
     * SDK state that may not be populated yet.
     */
    private static String handshakeToken() {
        try {
            return NiceBuildSdk.parseUserIdFromJWT();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether the partner RSA key pair {@code initSDK} fetches has landed. Logged on every
     * connect: a {@code false} here is the single most likely cause of a handshake that fails
     * after a successful scan.
     */
    private static boolean isPartnerDataReady() {
        try {
            return NiceBuildSdk.INSTANCE.isPartnerDataReady();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Device type for {@code signDeviceSnAsync}, derived from the serial number prefix. The
     * SDK matches on these exact strings and silently signs nothing useful for an unknown
     * type, so the fallback mirrors the Expo module's.
     */
    private static String deviceType(String sn) {
        if (sn.startsWith("881")) return "notepro";
        if (sn.startsWith("880")) return "notepin";
        if (sn.startsWith("882")) return "notepins";
        return "note";
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

    /**
     * The OS-level bond state for a MAC, as a name. Worth logging on every connect: a stale
     * BONDED entry (the pen was paired to this phone by another app, then forgot the link on
     * its side) makes Android's GATT service discovery hang silently — {@code
     * discoverServices()} returns true and {@code onServicesDiscovered} never fires — which
     * the SDK can only report, 10s later, as an undifferentiated {@code TIME_OUT}.
     */
    private String bondState(String mac) {
        try {
            BluetoothManager manager =
                (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            if (adapter == null || mac == null) return "unknown";
            switch (adapter.getRemoteDevice(mac).getBondState()) {
                case BluetoothDevice.BOND_BONDED: return "BONDED";
                case BluetoothDevice.BOND_BONDING: return "BONDING";
                case BluetoothDevice.BOND_NONE: return "NONE";
                default: return "unknown";
            }
        } catch (Throwable t) {
            // getBondState needs BLUETOOTH_CONNECT, and getRemoteDevice rejects a malformed
            // MAC; neither is worth failing a connect over.
            return "unknown";
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
     * Transport-level listener attached straight to {@link TntAgent}, alongside the facade's
     * own internal one.
     *
     * <p>It exists because {@code PlaudDeviceAgentListener} — the facade-level callback
     * interface the plugin implements — is lossy about failures: every distinct connection
     * error (handshake rejected, SN check failed, token mismatch, GATT timeout, pen busy
     * recording, user declined on the device) is collapsed into a single
     * {@code bleConnectState(2)}, and the facade drops {@code bleConnectStage} and
     * {@code handshakeWaitSure} on the floor entirely. That is what makes a failed connect
     * look like "nothing happened" from JS.
     *
     * <p>Listening one layer down recovers the reason: {@code connectFail} carries the SDK's
     * error code/message, {@code connectStage} traces the handshake step by step, and
     * {@code handshakeWaitSure} says the pen is waiting for the user to confirm pairing on
     * the device itself — a state the UI otherwise can't distinguish from a hang. Everything
     * is logged under {@link #TAG} as well, so {@code adb logcat -s PlaudSdk:V BleAgentImpl:V}
     * shows the full connect trace.
     *
     * <p>The interface is wide and mostly about device features the facade already forwards,
     * so the rest of the methods are deliberately empty.
     */
    private final class BleAgentDiagnostics implements BleAgentListener {

        @Override
        public void bleConnectFail(String mac, Constants.ConnectBleFailed failed) {
            Log.w(TAG, "connectFail mac=" + mac + " reason=" + failed);
            JSObject data = new JSObject();
            data.put("mac", mac);
            data.put("reason", failed != null ? failed.name() : null);
            data.put("code", failed != null ? failed.getErrCode() : 0);
            data.put("message", failed != null ? failed.getErrMsg() : null);
            emit("connectFail", data);
        }

        @Override
        public void bleConnectStage(String mac, String stage, String message) {
            Log.i(TAG, "connectStage mac=" + mac + " stage=" + stage + " msg=" + message);
            JSObject data = new JSObject();
            data.put("mac", mac);
            data.put("stage", stage);
            data.put("message", message);
            emit("connectStage", data);
        }

        @Override
        public void btStatusChange(String mac, BluetoothStatus status) {
            Log.i(TAG, "btStatus mac=" + mac + " status=" + status);
            JSObject data = new JSObject();
            data.put("mac", mac);
            data.put("status", status != null ? status.name() : null);
            emit("btStatus", data);
        }

        /**
         * The pen is asking the user to confirm pairing with a press on the device; nothing
         * else happens until they do, or until {@code timeoutMs} elapses.
         */
        @Override
        public void handshakeWaitSure(String mac, long timeoutMs) {
            Log.i(TAG, "handshakeWaitSure mac=" + mac + " timeoutMs=" + timeoutMs);
            JSObject data = new JSObject();
            data.put("mac", mac);
            data.put("timeoutMs", timeoutMs);
            emit("handshakeWaitSure", data);
        }

        @Override
        public void scanFail(Constants.ScanFailed failed) {
            Log.w(TAG, "scanFail reason=" + failed);
            emit("scanFail", jsObject("reason", failed != null ? failed.name() : null));
        }

        @Override
        public void sendMoreFailDisconnect(String mac) {
            Log.w(TAG, "sendMoreFailDisconnect mac=" + mac);
        }

        @Override
        public void mtuChange(String mac, int mtu, boolean success) {
            Log.i(TAG, "mtuChange mac=" + mac + " mtu=" + mtu + " success=" + success);
        }

        // Everything below is either already forwarded by the facade or irrelevant to
        // diagnosing a connect, and is implemented only to satisfy the interface.

        @Override public void scanBleDeviceReceiver(BleDevice device) {}
        @Override public void rssiChange(String mac, int rssi) {}
        @Override public void batteryLevelUpdate(String mac, int level) {}
        @Override public void chargingStatusChange(String mac, boolean charging) {}
        @Override public void deviceOpRecordStart(String mac, RecordStartRsp rsp) {}
        @Override public void deviceOpRecordStop(String mac, RecordStopRsp rsp) {}
        @Override public void deviceOpRecordPause(String mac, RecordPauseRsp rsp) {}
        @Override public void deviceOpRecordResume(String mac, RecordResumeRsp rsp) {}
        @Override public void deviceOpStorageRsp(String mac, StorageRsp rsp) {}
        @Override public void deviceStatusRsp(String mac, GetStateRsp rsp) {}
        @Override public void deviceStatusDetailed(String mac, byte[] data) {}
        @Override public void deviceLogSyncStop(String mac, StopSyncDeviceLogFileRsp rsp) {}
        @Override public void deviceLogSyncEnd(String mac, SyncDeviceLogFileEndRsp rsp) {}
        @Override public void deviceLogSyncData(String mac, int a, int b, long c, byte[] data) {}
        @Override public void deviceFotaResult(String mac, AppFotaPushRsp rsp) {}
        @Override public void deviceFotaThirdVersion(String mac, GetThirdVersionRsp rsp) {}
        @Override public void deviceWifiSyncStartRsp(String mac, int status) {}
        @Override public void deviceSwitchWifiMode(String mac, BtCloseRsp rsp) {}
        @Override public void motorStatus(String mac, int status) {}
        @Override public void stickAngles(String mac, AnglesRsp rsp) {}
        @Override public void deviceNewFeature(byte[] data) {}
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
