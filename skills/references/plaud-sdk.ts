import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

/** A device surfaced by the SDK's `bleScanResult` callback. */
export interface PlaudScanDevice {
  name: string;
  uuid: string;
  serialNumber: string;
  rssi: number;
  supportWiFi: boolean;
}

export interface PlaudScanResult {
  devices: PlaudScanDevice[];
}

export interface PlaudConnectState {
  connected: boolean;
  /** True for connection/handshake failure (state 2/-1/-2), vs. a normal disconnect. */
  failed: boolean;
  state: number;
}

export interface PlaudPenState {
  state: number;
  privacy: number;
  keyState: number;
  uDisk: number;
  findMyToken: number;
  hasSndpKey: number;
  deviceAccessToken: number;
}

/** A recording stored on the device, from the `fileList` event. */
export interface PlaudFile {
  sn: string;
  sessionId: number;
  size: number;
  scenes: number;
  channels: number;
  isOgg: boolean;
  isMusic: boolean;
  /** Duration in seconds. */
  duration: number;
}

export interface PlaudFileList {
  files: PlaudFile[];
}

export interface PlaudExportProgress {
  sessionId: number;
  progress: number;
  message: string;
}

/** Device-initiated recording started (physical button / VAD). */
export interface PlaudRecordStart {
  sessionId: number;
  start: number;
  status: number;
  scene: number;
  startTime: number;
  reason: number;
}

/** Device-initiated recording stopped/paused, with the resulting file info. */
export interface PlaudRecordStop {
  sessionId: number;
  reason: number;
  fileExist: boolean;
  fileSize: number;
}

/** Device-initiated recording resumed. */
export interface PlaudRecordResume {
  sessionId: number;
  start: number;
  status: number;
  scene: number;
  startTime: number;
}

export type PlaudAudioFormat = "pcm" | "mp3" | "wav" | "opus";

/**
 * JS interface for the native `PlaudSdk` Capacitor plugin
 * (see ios/PlaudPlugin/Sources/PlaudPlugin/PlaudSdkPlugin.swift).
 *
 * The native side is only present inside the Capacitor iOS shell; in a plain
 * browser these calls reject with "not implemented". Guard with
 * `Capacitor.isNativePlatform()` at the call site.
 */
export interface PlaudSdkPlugin {
  /**
   * Initialise the SDK with a per-user JWT. `customDomain` is domain-only (no https://).
   * `userId` is the app-level user identifier used as the default connect `deviceToken`
   * (the native app passes it on every connect to bind the device to the user).
   */
  initSDK(options: {
    userAccessToken: string;
    customDomain: string;
    userId?: string;
  }): Promise<void>;
  startScan(): Promise<void>;
  stopScan(): Promise<void>;
  /**
   * Connect to a device from a prior `scanResult`, identified by `uuid` (preferred) or
   * `serialNumber`. Progress arrives via the `connectState` and `penState` events.
   */
  connectBleDevice(options: {
    uuid?: string;
    serialNumber?: string;
    deviceToken?: string;
  }): Promise<void>;
  disconnect(): Promise<void>;
  /**
   * Unpair the device. With `clear: true` (default) the SDK also clears local pairing
   * state so the next connect re-runs the handshake. Result arrives via the `depair` event.
   */
  depair(options?: { clear?: boolean }): Promise<void>;
  isConnected(): Promise<{ connected: boolean }>;
  /** Request the recording list; results arrive via the `fileList` event. */
  getFileList(options?: { startSessionId?: number }): Promise<void>;
  /**
   * Decode a recording to a file in the app's Documents/PlaudExports dir. Resolves with
   * the written path; emits `exportProgress` events. `format` defaults to "mp3".
   */
  exportAudio(options: {
    sessionId: number;
    format?: PlaudAudioFormat;
    channels?: number;
  }): Promise<{ sessionId: number; outputPath: string }>;
  /**
   * Read a file written by `exportAudio` and return its bytes base64-encoded. Needed
   * because the WebView loads a remote origin, so `Capacitor.convertFileSrc()` URLs
   * aren't fetchable from JS (cross-origin custom scheme, blocked by CORS). Accepts the
   * raw `outputPath` from `exportAudio` or a `convertFileSrc()` URL.
   */
  readFile(options: { path: string }): Promise<{ data: string }>;
  /**
   * PUT raw bytes (base64-encoded in `data`) to `url` via a native request, returning the
   * response status and `ETag` header. Used for S3 presigned multipart uploads: a browser
   * `fetch(PUT)` from the remote-loaded WebView is blocked by CORS, while a native request
   * isn't and can read the `ETag` directly.
   */
  putBinary(options: {
    url: string;
    data: string;
    contentType?: string;
  }): Promise<{ status: number; etag: string | null }>;

  addListener(
    eventName: "scanResult",
    listener: (data: PlaudScanResult) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "scanTimeout",
    listener: (data: { reason?: string }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "connectState",
    listener: (data: PlaudConnectState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "penState",
    listener: (data: PlaudPenState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "bind",
    listener: (data: { sn: string | null; status: number; protVersion: number }) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "fileList",
    listener: (data: PlaudFileList) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "exportProgress",
    listener: (data: PlaudExportProgress) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "recordStart",
    listener: (data: PlaudRecordStart) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "recordStop",
    listener: (data: PlaudRecordStop) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "recordPause",
    listener: (data: PlaudRecordStop) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "recordResume",
    listener: (data: PlaudRecordResume) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "depair",
    listener: (data: { status: number }) => void,
  ): Promise<PluginListenerHandle>;
}

export const PlaudSdk = registerPlugin<PlaudSdkPlugin>("PlaudSdk");

/**
 * Read an exported file's raw bytes through the native bridge. Use this instead of
 * `fetch(Capacitor.convertFileSrc(path))`, which fails when the WebView loads a remote
 * origin (the `capacitor://…/_capacitor_file_/…` URL is a cross-origin custom scheme and
 * WKWebView's CORS check blocks the fetch).
 */
export async function readExportedFile(path: string): Promise<ArrayBuffer> {
  const { data } = await PlaudSdk.readFile({ path });
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  // Chunk the fromCharCode calls so a large buffer doesn't blow the call-stack argument limit.
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

/**
 * PUT a chunk of bytes to a presigned URL through the native bridge (no CORS), returning
 * the response status and `ETag`. Use this instead of `fetch(presignedUrl, {method:"PUT"})`,
 * which the remote-loaded WebView blocks with a CORS error.
 */
export async function putBinaryNative(
  url: string,
  chunk: ArrayBuffer,
  contentType?: string,
): Promise<{ status: number; etag: string | null }> {
  return PlaudSdk.putBinary({
    url,
    data: arrayBufferToBase64(chunk),
    ...(contentType ? { contentType } : {}),
  });
}
