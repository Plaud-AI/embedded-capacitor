# PlaudSdk JavaScript API

The `PlaudSdk` object (from `registerPlugin<PlaudSdkPlugin>("PlaudSdk")`, exported by
`plaud-sdk.ts`) is the JS surface of the native plugin. It has two halves: **methods** you
call (promises) and **events** you subscribe to with `addListener`. Most results are
asynchronous — you *call* `startScan()` / `getFileList()` / `connectBleDevice()` but the data
comes back on an event.

> The native side only exists inside the Capacitor iOS shell. Outside it (plain browser,
> SSR) every call rejects with "not implemented". Guard with `Capacitor.isNativePlatform()`.

## Methods

| Method | Signature | Notes |
|--------|-----------|-------|
| `initSDK` | `({ userAccessToken, customDomain, userId? }) => Promise<void>` | Call once before anything else. `customDomain` is domain-only, no `https://` (e.g. `platform-us.plaud.ai`). `userId` becomes the default connect `deviceToken` that binds the device to the user. |
| `startScan` | `() => Promise<void>` | Begins BLE scan. Results arrive on the `scanResult` event. Gated internally on Bluetooth `.poweredOn`; emits `scanTimeout` if it never powers on. |
| `stopScan` | `() => Promise<void>` | Stops scanning. Call before connecting. |
| `connectBleDevice` | `({ uuid?, serialNumber?, deviceToken? }) => Promise<void>` | Connect to a device from a prior `scanResult`. Prefer `uuid`. Progress arrives on `connectState` + `penState`. |
| `disconnect` | `() => Promise<void>` | Disconnect the current device. |
| `depair` | `({ clear? }) => Promise<void>` | Unpair. `clear` defaults to `true` (also clears local pairing state so the next connect re-handshakes). Result on the `depair` event. |
| `isConnected` | `() => Promise<{ connected: boolean }>` | Synchronous-style connection check. |
| `getFileList` | `({ startSessionId? }) => Promise<void>` | Request the on-device recording list (default `startSessionId: 0`). Results on the `fileList` event. |
| `exportAudio` | `({ sessionId, format?, channels? }) => Promise<{ sessionId, outputPath }>` | Decode a recording to a file in `Documents/PlaudExports`. `format` is `pcm`\|`mp3`\|`wav`\|`opus` (default `mp3`). Resolves with the written path; emits `exportProgress` along the way. Use `mp3` — it's playable by AVAudioPlayer and accepted by the transcription upload. |
| `readFile` | `({ path }) => Promise<{ data: string }>` | Read an exported file's bytes as base64 **through native code**. Needed because the WebView loads a remote origin, so `fetch(convertFileSrc(path))` is blocked by CORS. Prefer the `readExportedFile()` helper. |
| `putBinary` | `({ url, data, contentType? }) => Promise<{ status, etag }>` | PUT base64 bytes to a URL via a native request (no CORS), returning status + readable `ETag`. Used for S3 presigned multipart uploads. Prefer the `putBinaryNative()` helper. |

## Events (`addListener`)

`addListener` returns a `Promise<PluginListenerHandle>`; keep the handles and call
`.remove()` on cleanup.

| Event | Payload | Fired when |
|-------|---------|-----------|
| `scanResult` | `{ devices: PlaudScanDevice[] }` | Devices discovered during scan (fires repeatedly; dedupe by `serialNumber`/`uuid`). |
| `scanTimeout` | `{ reason?: string }` | Scan ended without success. `reason: "bluetoothNotPoweredOn"` = BT off / permission denied. |
| `connectState` | `{ connected, failed, state }` | Connection state changed. `failed` distinguishes a handshake failure from a normal disconnect. |
| `penState` | `PlaudPenState` | Handshake / device state (`state`, `privacy`, `keyState`, …). |
| `bind` | `{ sn, status, protVersion }` | Device binding result. |
| `fileList` | `{ files: PlaudFile[] }` | Response to `getFileList`. |
| `exportProgress` | `{ sessionId, progress, message }` | Progress during `exportAudio`. |
| `recordStart` | `PlaudRecordStart` | Device started recording (physical button / VAD). |
| `recordStop` | `PlaudRecordStop` | Device stopped; includes `fileExist` + `fileSize`. Refresh the file list here. |
| `recordPause` | `PlaudRecordStop` | Device paused recording. |
| `recordResume` | `PlaudRecordResume` | Device resumed recording. |
| `depair` | `{ status }` | Unpair completed. |

### Recording is device-driven

There are **no** start/stop-record methods on the JS surface — recording is triggered by the
physical Plaud device (button press / voice activity). The app only *observes* it via the
four `record*` events. A recording made after connect won't be in the list fetched at connect
time, so call `getFileList({ startSessionId: 0 })` again when you receive `recordStop`.

## Types

See [`plaud-sdk.ts`](plaud-sdk.ts) for the full definitions. The important shapes:

```typescript
interface PlaudScanDevice { name: string; uuid: string; serialNumber: string; rssi: number; supportWiFi: boolean; }
interface PlaudConnectState { connected: boolean; failed: boolean; state: number; }
interface PlaudFile { sn: string; sessionId: number; size: number; scenes: number; channels: number; isOgg: boolean; isMusic: boolean; duration: number; }
interface PlaudExportProgress { sessionId: number; progress: number; message: string; }
type PlaudAudioFormat = "pcm" | "mp3" | "wav" | "opus";
```

## The remote-origin / CORS constraint (important)

The WebView loads a **remote** origin (your deployed URL), so two things a browser normally
does are blocked, and the plugin routes around them natively:

1. **Reading an exported file** — `fetch(Capacitor.convertFileSrc(outputPath))` fails because
   the `capacitor://…/_capacitor_file_/…` URL is a cross-origin custom scheme. Use
   `readExportedFile(outputPath)` → `ArrayBuffer` (calls `PlaudSdk.readFile` under the hood).
2. **Uploading to S3** — `fetch(presignedUrl, { method: "PUT" })` is blocked by CORS and the
   `ETag` response header wouldn't be readable anyway. Use `putBinaryNative(url, chunk,
   contentType)` (calls `PlaudSdk.putBinary`), which returns `{ status, etag }`.

Both helpers are exported from `plaud-sdk.ts` — call them instead of `fetch` for file/S3 I/O.
