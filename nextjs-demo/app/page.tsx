"use client";
import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import {
  PlaudSdk,
  readExportedFile,
  type PlaudScanDevice,
  type PlaudFile,
} from "@/lib/plaud-sdk";
import { transcribeExportedFile } from "@/app/transcription-runner";
import { FileModal, type FileResult } from "./file-modal";
import useSWR from "swr";
import { RadarIcon ,FileTextIcon, UnlinkIcon, FileAudioIcon, RefreshIcon } from "./icons";

const PLAUD_DOMAIN = "platform-us.plaud.ai";
const USER_ID = "jackmu";

/* Map a freeform status string to a tone + dot color. */
function statusTone(status: string): "ok" | "err" | "live" | "idle" {
  const s = status.toLowerCase();
  if (s.includes("record") || s.includes("scanning") || s.includes("connecting"))
    return "live";
  if (s.includes("fail") || s.includes("error") || s.includes("off")) return "err";
  if (s.includes("connected") || s.includes("depaired")) return "ok";
  return "idle";
}
const TONE_COLOR: Record<string, string> = {
  ok: "var(--status-ok)",
  err: "var(--dev-status-error)",
  live: "var(--dev-accent-blue)",
  idle: "var(--dev-text-faint)",
};

export default function Home() {
  const [devices, setDevices] = useState<PlaudScanDevice[]>([]);
  const [files, setFiles] = useState<PlaudFile[]>([]);
  const [status, setStatus] = useState("idle");
  const [connected, setConnected] = useState(false);
  const [recording, setRecording] = useState<string | null>(null);
  const [isLive, setIsLive] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [results, setResults] = useState<Record<number, FileResult>>({});
  const [openSessionId, setOpenSessionId] = useState<number | null>(null);
  const [scanning, setScanning] = useState(false);
  const initedRef = useRef(false);

  const { data } = useSWR("api/user-token", async () => {
    const tokenRes = await fetch(`${window.location.origin}/api/user-token`, {
      method: "POST",
      body: JSON.stringify({ user_id: USER_ID }),
    });
    const { access_token } = await tokenRes.json();
    return { access_token: access_token as string };
  });

  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return;
    const handles: PluginListenerHandle[] = [];
    (async () => {
      handles.push(
        await PlaudSdk.addListener("scanResult", ({ devices }) => {
          setDevices((prev) => {
            const map = new Map(prev.map((d) => [d.serialNumber, d]));
            for (const d of devices) map.set(d.serialNumber, d);
            return [...map.values()];
          });
        }),
        await PlaudSdk.addListener("scanTimeout", ({ reason }) => {
          setScanning(false);
          setStatus(
            reason === "bluetoothNotPoweredOn"
              ? "Bluetooth is off or permission was denied"
              : "scan timed out",
          );
        }),
        await PlaudSdk.addListener("connectState", ({ connected, failed }) => {
          console.log("[Plaud] connectState", { connected, failed });
          setStatus(connected ? "connected" : failed ? "connection failed" : "disconnected");
          setConnected(connected);
          if (connected) setScanning(false);
          // Once connected, pull the on-device recording list.
          if (connected) PlaudSdk.getFileList({ startSessionId: 0 }).catch(() => {});
        }),
        await PlaudSdk.addListener("penState", (s) =>
          setStatus(`pen state ${s.state} (key ${s.keyState})`),
        ),
        await PlaudSdk.addListener("fileList", ({ files }) => {
          console.log("[Plaud] fileList", files);
          setFiles(files);
        }),
        await PlaudSdk.addListener("exportProgress", (p) => {
          console.log("[Plaud] exportProgress", p);
          setResults((prev) => ({
            ...prev,
            [p.sessionId]: {
              ...prev[p.sessionId],
              status: prev[p.sessionId]?.status ?? "exporting",
              exportInfo: `${p.progress}% ${p.message}`,
            },
          }));
        }),
        // Recording is driven by the physical device, not the app. Surface the
        // start/stop/pause/resume events so they're visible, and refresh the file
        // list once a recording stops so the new file shows up to export.
        await PlaudSdk.addListener("recordStart", (r) => {
          console.log("[Plaud] recordStart", r);
          setIsLive(true);
          setRecording(`Recording · session ${r.sessionId} · scene ${r.scene}`);
        }),
        await PlaudSdk.addListener("recordStop", (r) => {
          console.log("[Plaud] recordStop", r);
          setIsLive(false);
          setRecording(
            `Stopped · session ${r.sessionId} · ${(r.fileSize / 1024).toFixed(0)} KB` +
              (r.fileExist ? "" : " (no file)"),
          );
          // A fresh recording won't be in the list fetched at connect — refresh it.
          PlaudSdk.getFileList({ startSessionId: 0 }).catch(() => {});
        }),
        await PlaudSdk.addListener("recordPause", (r) => {
          console.log("[Plaud] recordPause", r);
          setIsLive(false);
          setRecording(`Paused · session ${r.sessionId}`);
        }),
        await PlaudSdk.addListener("recordResume", (r) => {
          console.log("[Plaud] recordResume", r);
          setIsLive(true);
          setRecording(`Recording · session ${r.sessionId} (resumed)`);
        }),
        await PlaudSdk.addListener("depair", ({ status }) => {
          console.log("[Plaud] depair", status);
          setStatus(`depaired (status ${status})`);
          setConnected(false);
          setFiles([]);
          setRecording(null);
          setIsLive(false);
          setResults({});
          setOpenSessionId(null);
        }),
      );
    })();
    return () => {
      handles.forEach((h) => h.remove());
    };
  }, []);

  const ensureNative = () => {
    if (!Capacitor.isNativePlatform()) {
      setError("Native Plaud SDK is only available inside the iOS app shell.");
      return false;
    }
    return true;
  };

  const handleScan = async () => {
    setError(null);
    if (!ensureNative()) return;
    const token = data?.access_token;
    if (!token) {
      setError("User token not ready yet — try again in a moment.");
      return;
    }
    try {
      if (!initedRef.current) {
        setStatus("initializing SDK…");
        await PlaudSdk.initSDK({
          userAccessToken: token,
          customDomain: PLAUD_DOMAIN,
          userId: USER_ID,
        });
        initedRef.current = true;
      }
      setScanning(true);
      setStatus("scanning…");
      await PlaudSdk.startScan();
    } catch (err) {
      setScanning(false);
      setError(err instanceof Error ? err.message : String(err));
      setStatus("error");
    }
  };

  const handleConnect = async (d: PlaudScanDevice) => {
    setError(null);
    if (!ensureNative()) return;
    try {
      setStatus(`connecting to ${d.name || d.serialNumber}…`);
      await PlaudSdk.stopScan();
      setScanning(false);
      await PlaudSdk.connectBleDevice({ uuid: d.uuid, serialNumber: d.serialNumber });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  const handleDepair = async () => {
    setError(null);
    if (!ensureNative()) return;
    if (!window.confirm("Unpair this device and clear local pairing state?")) return;
    try {
      setStatus("depairing…");
      await PlaudSdk.depair({ clear: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  const updateResult = (sessionId: number, patch: Partial<FileResult>) =>
    setResults((prev) => ({
      ...prev,
      [sessionId]: { ...prev[sessionId], ...patch },
    }));

  const handleFileClick = (f: PlaudFile) => {
    setOpenSessionId(f.sessionId);
    if (results[f.sessionId]?.status === "ready") return;
    void exportAndTranscribe(f);
  };

  const exportAndTranscribe = async (f: PlaudFile) => {
    setError(null);
    if (!ensureNative()) return;
    updateResult(f.sessionId, {
      status: "exporting",
      exportInfo: "starting…",
      error: undefined,
      transcript: null,
      transcribeStatus: undefined,
    });
    console.log("[Plaud] exportAudio →", f.sessionId);
    try {
      const { outputPath } = await PlaudSdk.exportAudio({
        sessionId: f.sessionId,
        format: "mp3",
      });
      console.log("[Plaud] exportAudio done", f.sessionId, outputPath);
      const src = Capacitor.convertFileSrc(outputPath);
      updateResult(f.sessionId, {
        status: "transcribing",
        src,
        exportInfo: `saved → ${outputPath}`,
      });
      await runTranscribe(f.sessionId, outputPath);
    } catch (err) {
      console.error("[Plaud] exportAudio failed", f.sessionId, err);
      updateResult(f.sessionId, {
        status: "error",
        error: err instanceof Error ? err.message : String(err),
      });
    }
  };

  const runTranscribe = async (sessionId: number, outputPath: string) => {
    const token = data?.access_token;
    if (!token) {
      updateResult(sessionId, {
        status: "error",
        error: "User token not ready — can't transcribe yet.",
      });
      return;
    }
    try {
      const buffer = await readExportedFile(outputPath);
      const task = await transcribeExportedFile(buffer, "mp3", token, (p) => {
        updateResult(sessionId, {
          transcribeStatus:
            p.phase === "uploading"
              ? `uploading to Plaud… ${p.percent ?? 0}%`
              : p.phase === "finalizing"
                ? "finalizing upload…"
                : p.phase === "submitting"
                  ? "submitting for transcription…"
                  : `transcribing… (${p.status})`,
        });
      });
      console.log("[Plaud] transcription task", task);
      const text =
        task.data.text?.trim() ||
        task.data.results?.map((s) => s.text).join(" ").trim() ||
        null;
      updateResult(sessionId, {
        status: "ready",
        transcribeStatus: "transcription complete",
        transcript: text,
      });
    } catch (err) {
      console.error("[Plaud] transcription failed", err);
      updateResult(sessionId, {
        status: "error",
        error: err instanceof Error ? err.message : String(err),
      });
    }
  };

  const handleRefreshFiles = async () => {
    setError(null);
    if (!ensureNative()) return;
    try {
      console.log("[Plaud] getFileList refresh");
      await PlaudSdk.getFileList({ startSessionId: 0 });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };

  const tone = statusTone(status);

  return (
    <div className="flex w-full flex-1 flex-col overflow-x-hidden">
      {/* Fixed frosted chrome */}
      <header
        className="bg-background sticky top-20 z-20 flex items-center justify-between border-b px-5 py-3.5"
        style={{
          borderColor: "var(--dev-border-subtle)",
          background: "rgba(15,15,15,0.72)",
          backdropFilter: "blur(var(--dev-blur-chrome))",
          WebkitBackdropFilter: "blur(var(--dev-blur-chrome))",
        }}
      >
        <div className="flex items-center gap-2.5">
          <Image
            src="/brand/logo-wordmark-white.png"
            alt="Plaud"
            width={78}
            height={20}
            priority
            style={{ height: 18, width: "auto" }}
          />
          <span className="overline" style={{ marginTop: 1 }}>
            Developer
          </span>
        </div>
        <span className="pill">
          <span
            className="rec-dot"
            style={{
              animation: tone === "live" ? undefined : "none",
              background: TONE_COLOR[tone],
              boxShadow: `0 0 0 4px color-mix(in srgb, ${TONE_COLOR[tone]} 18%, transparent)`,
            }}
          />
          {status}
        </span>
      </header>

      <main className="mx-auto flex w-full max-w-md flex-1 flex-col gap-6 px-5 py-24">
        {/* Intro */}
        <section className="reveal">
          <p className="overline">Plaud SDK · reference app</p>
          <h1 className="mt-2 text-[32px] leading-none">
            Connect. Record. Transcribe.
          </h1>
          <p
            className="mt-3 text-[15px]"
            style={{ color: "var(--dev-text-dim)", lineHeight: 1.4 }}
          >
            Pair a Plaud recorder over Bluetooth, capture on-device, then export
            and transcribe — straight from the native bridge.
          </p>
        </section>

        {/* Primary actions */}
        <section className="reveal delay-100 flex flex-wrap gap-3">
          <button
            onClick={handleScan}
            disabled={scanning}
            className="btn btn-primary flex-1"
          >
            <RadarIcon size={18} />
            {scanning ? "Scanning…" : connected ? "Rescan" : "Init & scan"}
          </button>
          {connected && (
            <button onClick={handleDepair} className="btn btn-destructive">
              <UnlinkIcon size={18} />
              Unpair
            </button>
          )}
        </section>

        {/* Live recording banner */}
        {recording && (
          <section
            className="dev-card flex items-center gap-3 px-5 py-4"
            style={
              isLive
                ? { borderColor: "rgba(241,80,66,0.45)" }
                : undefined
            }
          >
            {isLive ? (
              <div className="flex items-end gap-1" style={{ height: 22 }}>
                {[0, 1, 2, 3, 4].map((i) => (
                  <span
                    key={i}
                    className="wave-bar"
                    style={{ animationDelay: `${i * 120}ms` }}
                  />
                ))}
              </div>
            ) : (
              <FileAudioIcon size={20} className="shrink-0" />
            )}
            <div className="min-w-0">
              <p className="overline" style={{ marginBottom: 2 }}>
                {isLive ? "Live" : "Last capture"}
              </p>
              <p
                className="mono truncate text-[13px]"
                style={{ color: "var(--dev-text-light)" }}
              >
                {recording}
              </p>
            </div>
          </section>
        )}

        {error && (
          <div
            className="dev-card px-4 py-3 text-[13px]"
            style={{
              color: "var(--dev-status-error)",
              borderColor: "rgba(241,80,66,0.4)",
            }}
          >
            {error}
          </div>
        )}

        {/* Discovered devices — tap to connect. */}
        {devices.length > 0 && !connected && (
          <section className="reveal delay-200 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <h2 className="overline">Devices</h2>
              <span className="caption mono" style={{ color: "var(--dev-text-faint)" }}>
                {devices.length} found
              </span>
            </div>
            <div className="flex flex-col gap-2">
              {devices.map((d) => (
                <button
                  key={d.serialNumber || d.uuid}
                  onClick={() => handleConnect(d)}
                  className="dev-row"
                >
                  <span
                    className="truncate text-[15px]"
                    style={{ color: "var(--dev-text-light)" }}
                  >
                    {d.name || d.serialNumber || d.uuid}
                  </span>
                  <span
                    className="mono shrink-0 text-[12px]"
                    style={{ color: "var(--dev-text-faint)" }}
                  >
                    {d.rssi} dBm
                  </span>
                </button>
              ))}
            </div>
          </section>
        )}

        {/* Recordings on the connected device — tap to export/decode. */}
        {connected && (
          <section className="reveal delay-200 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <h2 className="overline">Recordings</h2>
              <button
                onClick={handleRefreshFiles}
                className="flex items-center gap-1.5 text-[12px]"
                style={{ color: "var(--dev-accent-blue)" }}
              >
                <RefreshIcon size={14} />
                Refresh
              </button>
            </div>
            {files.length === 0 ? (
              <div
                className="dev-card px-4 py-6 text-center text-[13px]"
                style={{ color: "var(--dev-text-dim)" }}
              >
                No recordings yet. Record on the device, then refresh.
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                {files.map((f) => {
                  const r = results[f.sessionId];
                  return (
                    <button
                      key={f.sessionId}
                      onClick={() => handleFileClick(f)}
                      className="dev-row"
                    >
                      <span className="flex items-center gap-3 min-w-0">
                        <FileAudioIcon size={18} className="shrink-0" />
                        <span
                          className="mono text-[14px]"
                          style={{ color: "var(--dev-text-light)" }}
                        >
                          #{f.sessionId}
                        </span>
                        {r?.status === "ready" && (
                          <span
                            className="flex items-center gap-1 text-[11px]"
                            style={{ color: "var(--status-ok)" }}
                          >
                            <FileTextIcon size={12} />
                            transcribed
                          </span>
                        )}
                        {(r?.status === "exporting" || r?.status === "transcribing") && (
                          <span
                            className="text-[11px]"
                            style={{ color: "var(--dev-accent-blue)" }}
                          >
                            processing…
                          </span>
                        )}
                      </span>
                      <span
                        className="mono shrink-0 text-[12px]"
                        style={{ color: "var(--dev-text-faint)" }}
                      >
                        {f.duration}s · {(f.size / 1024).toFixed(0)} KB
                      </span>
                    </button>
                  );
                })}
              </div>
            )}
          </section>
        )}

      </main>

      {/* Footer band */}
      <footer
        className="flex items-center justify-between px-5 py-3"
        style={{
          background: "var(--dev-surface-footer)",
          borderTop: "1px solid var(--dev-border-subtle)",
        }}
      >
        <span className="caption mono" style={{ color: "var(--dev-text-faint)" }}>
          {USER_ID}@{PLAUD_DOMAIN}
        </span>
        <span className="pill">
          <span
            className="rec-dot"
            style={{
              animation: "none",
              background: data?.access_token
                ? "var(--status-ok)"
                : "var(--dev-text-faint)",
              boxShadow: "none",
            }}
          />
          token {data?.access_token ? "ready" : "loading…"}
        </span>
      </footer>

      {/* File modal — playback + transcript for the selected recording. */}
      {openSessionId != null &&
        (() => {
          const f = files.find((x) => x.sessionId === openSessionId);
          if (!f) return null;
          return (
            <FileModal
              file={f}
              result={results[openSessionId]}
              onClose={() => setOpenSessionId(null)}
              onRetry={() => void exportAndTranscribe(f)}
            />
          );
        })()}
    </div>
  );
}
