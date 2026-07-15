"use client";
import type { PlaudFile } from "@/lib/plaud-sdk";
import { CloseIcon, FileTextIcon } from "./icons";

/** Per-session export + transcription state, cached in the page so a file that has
 * already been exported and transcribed can be re-opened without redoing the work. */
export type FileResult = {
  status: "exporting" | "transcribing" | "ready" | "error";
  /** Playback source (from Capacitor.convertFileSrc) once the audio is exported. */
  src?: string;
  exportInfo?: string;
  transcribeStatus?: string;
  transcript?: string | null;
  error?: string;
};

export function FileModal({
  file,
  result,
  onClose,
  onRetry,
}: {
  file: PlaudFile;
  result: FileResult | undefined;
  onClose: () => void;
  onRetry: () => void;
}) {
  const status = result?.status;
  const busy = status === "exporting" || status === "transcribing";

  return (
    <div className="min-h-36 fixed inset-0 z-30 flex items-end justify-center p-4 sm:items-center"
      style={{ background: "rgba(0,0,0,0.6)" }}
      onClick={onClose}
    >
      <div
        className="dev-card w-full max-w-md p-5"
        style={{ boxShadow: "var(--shadow-lg)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <div>
            <p className="overline">Recording</p>
            <h3 className="mono mt-1 text-[18px]">Session #{file.sessionId}</h3>
            <p className="mono mt-1 text-[12px]" style={{ color: "var(--dev-text-faint)" }}>
              {file.duration}s · {(file.size / 1024).toFixed(0)} KB
            </p>
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{ color: "var(--dev-text-dim)" }}
          >
            <CloseIcon size={22} />
          </button>
        </div>

        {/* Audio — available as soon as the export finishes. */}
        {result?.src ? (
          <audio
            className="w-full"
            src={result.src}
            controls
            autoPlay={status === "ready"}
            style={{ colorScheme: "dark" }}
          />
        ) : (
          <div
            className="rounded px-4 py-6 text-center text-[13px]"
            style={{
              background: "var(--dev-surface-input)",
              border: "1px solid var(--dev-border-subtle)",
              color: "var(--dev-text-dim)",
            }}
          >
            {status === "error" ? "Export failed." : "Exporting audio…"}
          </div>
        )}

        {/* Progress line while exporting/transcribing. */}
        {busy && result?.transcribeStatus && (
          <p
            className="mono mt-4 flex items-center gap-2 text-[12px]"
            style={{ color: "var(--dev-accent-blue)" }}
          >
            {result.transcribeStatus}
          </p>
        )}
        {status === "exporting" && (
          <p
            className="mono mt-4 flex items-center gap-2 text-[12px]"
            style={{ color: "var(--dev-accent-blue)" }}
          >
            {result?.exportInfo ?? "exporting…"}
          </p>
        )}

        {/* Error + retry. */}
        {status === "error" && (
          <div className="mt-4">
            <p
              className="text-[13px]"
              style={{ color: "var(--dev-status-error)" }}
            >
              {result?.error ?? "Something went wrong."}
            </p>
            <button onClick={onRetry} className="btn btn-primary mt-3 w-full">
              Retry
            </button>
          </div>
        )}

        {/* Transcript, once ready. */}
        {result?.status === "ready" && (
          <div className="mt-4">
            <p className="overline mb-2 flex items-center gap-1.5">
              <FileTextIcon size={14} />
              Transcript
            </p>
            <div
              className="max-h-48 overflow-y-auto rounded p-3 text-[13px]"
              style={{
                background: "var(--dev-surface-input)",
                border: "1px solid var(--dev-border-subtle)",
                color: result.transcript
                  ? "var(--dev-text-light)"
                  : "var(--dev-text-dim)",
                lineHeight: 1.5,
              }}
            >
              {result.transcript || "No speech detected."}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
