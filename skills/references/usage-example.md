# End-to-end usage example (React / Next.js)

The full flow: **init → scan → connect → list files → export → (transcribe)**. Recording
itself happens on the physical device; the app observes it and exports the resulting files.
Snippets below are trimmed from the reference app's `app/page.tsx`.

**Every snippet here runs unchanged on iOS and Android.** The two native plugins register
under the same `PlaudSdk` name, expose the same methods and emit the same events, so there is
no `Capacitor.getPlatform()` branching anywhere in this file — not even for Android's runtime
Bluetooth permissions, which `startScan()` requests on your behalf. The handful of values
that genuinely differ per platform are listed in "Platform differences" at the end.

## 1. Subscribe to events (once, on mount)

Register listeners in an effect and remove them on cleanup. Guard on
`Capacitor.isNativePlatform()` so nothing runs in a plain browser / during SSR.

```typescript
useEffect(() => {
  if (!Capacitor.isNativePlatform()) return;
  const handles: PluginListenerHandle[] = [];
  (async () => {
    handles.push(
      await PlaudSdk.addListener("scanResult", ({ devices }) => {
        // fires repeatedly — dedupe by serialNumber
        setDevices((prev) => {
          const map = new Map(prev.map((d) => [d.serialNumber, d]));
          for (const d of devices) map.set(d.serialNumber, d);
          return [...map.values()];
        });
      }),
      await PlaudSdk.addListener("connectState", ({ connected, failed }) => {
        setConnected(connected);
        setStatus(connected ? "connected" : failed ? "connection failed" : "disconnected");
        if (connected) PlaudSdk.getFileList({ startSessionId: 0 }).catch(() => {});
      }),
      await PlaudSdk.addListener("fileList", ({ files }) => setFiles(files)),
      await PlaudSdk.addListener("exportProgress", (p) => {
        // p.progress (0–100), p.message
      }),
      // Recording is device-driven — refresh the file list once it stops.
      await PlaudSdk.addListener("recordStop", () => {
        PlaudSdk.getFileList({ startSessionId: 0 }).catch(() => {});
      }),
    );
  })();
  return () => { handles.forEach((h) => h.remove()); };
}, []);
```

## 2. Init + scan

Initialize once (guard with a ref), then scan. `customDomain` is domain-only.

```typescript
const PLAUD_DOMAIN = "platform-us.plaud.ai";
const USER_ID = "your-app-user-id";

const handleScan = async () => {
  if (!Capacitor.isNativePlatform()) return;
  const token = await getUserToken();          // per-user JWT from your backend
  if (!initedRef.current) {
    await PlaudSdk.initSDK({ userAccessToken: token, customDomain: PLAUD_DOMAIN, userId: USER_ID });
    initedRef.current = true;
  }
  await PlaudSdk.startScan();                   // results arrive on "scanResult"
};
```

## 3. Connect

Stop scanning, then connect by `uuid` (preferred). Treat `uuid` as an opaque token — it's the
CoreBluetooth peripheral UUID on iOS and the MAC address on Android, but the same call works
on both. Connection progress comes back on the `connectState` event registered above.

```typescript
const handleConnect = async (d: PlaudScanDevice) => {
  await PlaudSdk.stopScan();
  await PlaudSdk.connectBleDevice({ uuid: d.uuid, serialNumber: d.serialNumber });
};
```

## 4. Export a recording

Files come from the `fileList` event. Exporting decodes the proprietary on-device format to a
standard file (use `mp3`) and resolves with the on-disk path — inside a private per-app
directory (`Documents/PlaudExports` on iOS, `files/PlaudExports` on Android). Use the
returned `outputPath` rather than constructing a path yourself.

```typescript
const exportRecording = async (f: PlaudFile) => {
  const { outputPath } = await PlaudSdk.exportAudio({ sessionId: f.sessionId, format: "mp3" });
  // For local playback in the WebView:
  const src = Capacitor.convertFileSrc(outputPath);
  return outputPath;
};
```

## 5. Read the bytes / upload (native bridge, not fetch)

Because the WebView loads a remote origin, use the native helpers — **not** `fetch` — for
file bytes and S3 uploads.

```typescript
// Read exported bytes (fetch(convertFileSrc(...)) would be blocked by CORS):
const buffer = await readExportedFile(outputPath);   // ArrayBuffer

// PUT a chunk to a presigned S3 URL (browser PUT is blocked by CORS; ETag unreadable):
const { status, etag } = await putBinaryNative(presignedUrl, chunk, "audio/mpeg");
```

From here, the exported bytes can be pushed through Plaud's File Upload + Transcription APIs
— see the `plaud-embedded-transcription-api-skill`.

## Depair (unpair)

```typescript
const handleDepair = async () => {
  if (!window.confirm("Unpair this device and clear local pairing state?")) return;
  await PlaudSdk.depair({ clear: true });   // result arrives on the "depair" event
};
```

## Platform differences

Only these values differ; the calls above don't change:

| | iOS | Android |
| --- | --- | --- |
| `PlaudScanDevice.uuid` | CoreBluetooth peripheral UUID | MAC address (also in `macAddress`) |
| `PlaudScanDevice.supportWiFi` | from the scan record | always `false` until connected |
| `PlaudPenState` | 7 fields | 4 — `findMyToken`, `hasSndpKey`, `deviceAccessToken` absent |
| `PlaudFile.duration` | exact | slightly long for `isOgg` files (frame arithmetic) |
| Export dir | `Documents/PlaudExports` | `files/PlaudExports` |
| BLE permission prompt | none (Info.plist only) | requested inside `startScan()` / `connectBleDevice()` |

If a value can be absent on one platform, it's optional in the TS type — so TypeScript makes
you handle it. Don't fall back to `0`; check for `undefined`.

## Debugging a failing connect (Android only)

The Android SDK collapses every failure mode into `connectState { failed: true }`, so the
plugin attaches a raw transport listener and re-emits the detail. These events **never fire
on iOS** — subscribing to them unconditionally is harmless, and they're the fastest way to
see why a pairing didn't take.

```typescript
await PlaudSdk.addListener("connectFail", ({ reason }) => {
  // e.g. "USER_REFUSE" (declined on the device), "RECORDING_NOW" (pen busy),
  // "MODE_NOT_MATCH" (pen not in connect mode), "TIME_OUT", "permissionDenied"
  setError(`connect failed: ${reason}`);
});
await PlaudSdk.addListener("connectStage", ({ stage, message }) => {
  // "gatt_connect" → "first_handshake" → "handshake_get_ssn" → … — shows how far it got
});
await PlaudSdk.addListener("handshakeWaitSure", () => {
  setStatus("confirm the pairing on the device");   // the pen is waiting on a physical press
});
```

Native-side logs: `adb logcat -s PlaudSdk:V BleAgentImpl:V`.

## Flow summary

```
initSDK ─▶ startScan ─▶ (scanResult) ─▶ connectBleDevice ─▶ (connectState: connected)
                                                                   │
                                                                   ▼
                                                             getFileList ─▶ (fileList)
                                                                   │
   device records ─▶ (recordStop) ─▶ getFileList again ───────────┤
                                                                   ▼
                                                exportAudio ─▶ (exportProgress) ─▶ { outputPath }
                                                                   │
                                          readExportedFile / putBinaryNative ─▶ upload/transcribe
```
