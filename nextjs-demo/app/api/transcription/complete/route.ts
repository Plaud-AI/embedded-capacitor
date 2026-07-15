import { NextResponse } from "next/server";
import { PlaudApiError } from "@/lib/plaud-auth";
import {
  completeMultipartUpload,
  type PlaudUploadFileType,
  type UploadPart,
} from "@/lib/plaud-transcription";

function isUploadPartList(value: unknown): value is UploadPart[] {
  return (
    Array.isArray(value) &&
    value.every(
      (p) =>
        p &&
        typeof p === "object" &&
        typeof (p as UploadPart).PartNumber === "number" &&
        typeof (p as UploadPart).ETag === "string",
    )
  );
}

/**
 * POST /api/transcription/complete
 * Body: {
 *   "access_token": "<user access token>",
 *   "file_id": string, "upload_id": string,
 *   "part_list": [{ "PartNumber": number, "ETag": string }],
 *   "filetype": "mp3" | "opus", "file_md5"?: string
 * }
 * Returns: { FileId, FileType, DownloadUrl, FileMd5 } — DownloadUrl is valid for 24h.
 */
export async function POST(req: Request) {
  console.log("completed upload");
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Request body must be valid JSON" }, { status: 400 });
  }

  const { access_token, file_id, upload_id, part_list, filetype, file_md5 } = body as {
    access_token?: unknown;
    file_id?: unknown;
    upload_id?: unknown;
    part_list?: unknown;
    filetype?: unknown;
    file_md5?: unknown;
  };

  if (typeof access_token !== "string" || access_token.trim() === "") {
    return NextResponse.json({ error: "Missing or invalid 'access_token'" }, { status: 400 });
  }
  if (typeof file_id !== "string" || !file_id) {
    return NextResponse.json({ error: "Missing or invalid 'file_id'" }, { status: 400 });
  }
  if (typeof upload_id !== "string" || !upload_id) {
    return NextResponse.json({ error: "Missing or invalid 'upload_id'" }, { status: 400 });
  }
  if (!isUploadPartList(part_list)) {
    return NextResponse.json(
      { error: "'part_list' must be an array of { PartNumber, ETag }" },
      { status: 400 },
    );
  }
  if (filetype !== "mp3" && filetype !== "opus") {
    return NextResponse.json({ error: "'filetype' must be 'mp3' or 'opus'" }, { status: 400 });
  }

  try {
    const completed = await completeMultipartUpload(access_token, {
      fileId: file_id,
      uploadId: upload_id,
      partList: part_list,
      filetype: filetype as PlaudUploadFileType,
      fileMd5: typeof file_md5 === "string" ? file_md5 : undefined,
    });
    console.log("complete ", completed);
    return NextResponse.json(completed);
  } catch (err) {
    if (err instanceof PlaudApiError) {
      return NextResponse.json({ error: err.message }, { status: 502 });
    }
    console.error("transcription/complete route error:", err);
    return NextResponse.json({ error: "Failed to complete multipart upload" }, { status: 500 });
  }
}
