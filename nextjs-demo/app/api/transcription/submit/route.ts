import { NextResponse } from "next/server";
import { PlaudApiError } from "@/lib/plaud-auth";
import { submitTranscription, type TranscriptionParams } from "@/lib/plaud-transcription";

/**
 * POST /api/transcription/submit
 * Body: { "file_url": "<public download URL>", "params"?: TranscriptionParams }
 * Returns: { transcription_id, status, data }
 *
 * Requires PLAUD_CLIENT_ID and PLAUD_API_KEY in the environment.
 */
export async function POST(req: Request) {
  console.log("submitted transcription job");
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Request body must be valid JSON" }, { status: 400 });
  }

  const { file_url, params } = body as { file_url?: unknown; params?: unknown };

  if (typeof file_url !== "string" || file_url.trim() === "") {
    return NextResponse.json({ error: "Missing or invalid 'file_url'" }, { status: 400 });
  }

  try {
    const task = await submitTranscription(
      file_url,
      params as TranscriptionParams | undefined,
    );
    console.log("transcription job", task);
    return NextResponse.json(task);
  } catch (err) {
    if (err instanceof PlaudApiError) {
      return NextResponse.json({ error: err.message }, { status: 502 });
    }
    console.error("transcription/submit route error:", err);
    return NextResponse.json({ error: "Failed to submit transcription" }, { status: 500 });
  }
}
