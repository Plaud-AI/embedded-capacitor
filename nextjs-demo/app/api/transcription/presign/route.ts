import { NextResponse } from "next/server";
import { PlaudApiError } from "@/lib/plaud-auth";
import { generatePresignedUploadUrls, type PlaudUploadFileType } from "@/lib/plaud-transcription";

/**
 * POST /api/transcription/presign
 * Body: { "access_token": "<user access token>", "filesize": number, "filetype": "mp3" | "opus" }
 * Returns: { FileId, UploadId, ChunkSize, Parts: [{ PartNumber, PresignedUrl }] }
 */
export async function POST(req: Request) {
  console.log("generating urls");
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Request body must be valid JSON" }, { status: 400 });
  }

  const { access_token, filesize, filetype } = body as {
    access_token?: unknown;
    filesize?: unknown;
    filetype?: unknown;
  };

  if (typeof access_token !== "string" || access_token.trim() === "") {
    return NextResponse.json({ error: "Missing or invalid 'access_token'" }, { status: 400 });
  }
  if (typeof filesize !== "number" || filesize <= 0) {
    return NextResponse.json({ error: "Missing or invalid 'filesize'" }, { status: 400 });
  }
  if (filetype !== "mp3" && filetype !== "opus") {
    return NextResponse.json({ error: "'filetype' must be 'mp3' or 'opus'" }, { status: 400 });
  }

  try {
    const presigned = await generatePresignedUploadUrls(
      access_token,
      filesize,
      filetype as PlaudUploadFileType,
    );
    console.log("presigned: ", presigned);
    return NextResponse.json(presigned);
  } catch (err) {
    if (err instanceof PlaudApiError) {
      return NextResponse.json({ error: err.message }, { status: 502 });
    }
    console.error("transcription/presign route error:", err);
    return NextResponse.json(
      { error: "Failed to generate presigned upload URLs" },
      { status: 500 },
    );
  }
}
