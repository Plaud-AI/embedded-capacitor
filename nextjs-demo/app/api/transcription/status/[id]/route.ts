import { NextResponse } from "next/server";
import { PlaudApiError } from "@/lib/plaud-auth";
import { getTranscriptionTask } from "@/lib/plaud-transcription";

/**
 * GET /api/transcription/status/[id]
 * Returns: { transcription_id, status, data } — poll while status is
 * PENDING/RECEIVED/STARTED/PROGRESS; data is populated once status is SUCCESS.
 */
export async function GET(
  _req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  console.log("getting status", id);
  if (!id) {
    return NextResponse.json({ error: "Missing transcription id" }, { status: 400 });
  }

  try {
    const task = await getTranscriptionTask(id);
    console.log("transcription: ", task);
    return NextResponse.json(task);
  } catch (err) {
    if (err instanceof PlaudApiError) {
      return NextResponse.json({ error: err.message }, { status: 502 });
    }
    console.error("transcription/status route error:", err);
    return NextResponse.json({ error: "Failed to fetch transcription task" }, { status: 500 });
  }
}
