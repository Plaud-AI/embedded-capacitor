import { BASE_URL, requireEnv, PlaudApiError } from "@/lib/plaud-auth";

export type PlaudUploadFileType = "mp3" | "opus";

export interface PresignedPart {
  PartNumber: number;
  PresignedUrl: string;
}

export interface PresignedUploadUrls {
  FileId: string;
  UploadId: string;
  ChunkSize: number;
  Parts: PresignedPart[];
}

export async function generatePresignedUploadUrls(
  userAccessToken: string,
  filesize: number,
  filetype: PlaudUploadFileType,
): Promise<PresignedUploadUrls> {
  const res = await fetch(`${BASE_URL}/open/partner/files/upload/generate-presigned-urls`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${userAccessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ filesize, filetype }),
  });

  if (!res.ok) {
    throw new PlaudApiError(
      `Failed to generate presigned upload URLs (upstream ${res.status})`,
      res.status,
    );
  }

  return res.json();
}

export interface UploadPart {
  PartNumber: number;
  ETag: string;
}

export interface CompletedUpload {
  FileId: string;
  FileType: string;
  DownloadUrl: string;
  FileMd5?: string;
}

export async function completeMultipartUpload(
  userAccessToken: string,
  params: {
    fileId: string;
    uploadId: string;
    partList: UploadPart[];
    filetype: PlaudUploadFileType;
    fileMd5?: string;
  },
): Promise<CompletedUpload> {
  const res = await fetch(`${BASE_URL}/open/partner/files/upload/complete-upload`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${userAccessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      file_id: params.fileId,
      upload_id: params.uploadId,
      part_list: params.partList,
      filetype: params.filetype,
      ...(params.fileMd5 ? { file_md5: params.fileMd5 } : {}),
    }),
  });

  if (!res.ok) {
    throw new PlaudApiError(
      `Failed to complete multipart upload (upstream ${res.status})`,
      res.status,
    );
  }

  return res.json();
}

export type TranscriptionStatus =
  | "PENDING"
  | "RECEIVED"
  | "STARTED"
  | "PROGRESS"
  | "SUCCESS"
  | "FAILURE"
  | "REVOKED";

export interface TranscriptionSegment {
  start: number;
  end: number;
  text: string;
  speaker_id?: string;
  language: string;
  language_probability: number;
}

export interface TranscriptionTask {
  transcription_id: string;
  status: TranscriptionStatus;
  data: {
    text?: string;
    language?: string;
    duration?: number;
    results?: TranscriptionSegment[];
  };
}

export interface TranscriptionParams {
  transcribe?: {
    language?: string;
    model?: "plaud-fast-whisper" | "plaud-omni-3" | "azure-fast-transcribe";
    detection_level?: "segment" | "chapter";
  };
  vad?: { decode_silence?: boolean };
  diarization?: { enabled?: boolean; return_embedding?: boolean };
}

/** The Transcription API uses partner client credentials, not the per-user OAuth token. */
function transcriptionAuthHeaders(): HeadersInit {
  return {
    "X-Client-Id": requireEnv("PLAUD_CLIENT_ID"),
    "X-Client-Api-Key": requireEnv("PLAUD_API_KEY"),
    "Content-Type": "application/json",
  };
}

export async function submitTranscription(
  fileUrl: string,
  params?: TranscriptionParams,
): Promise<TranscriptionTask> {
  const res = await fetch(`${BASE_URL}/open/partner/ai/transcriptions/`, {
    method: "POST",
    headers: transcriptionAuthHeaders(),
    body: JSON.stringify({ file_url: fileUrl, ...(params ? { params } : {}) }),
  });

  if (!res.ok) {
    throw new PlaudApiError(`Failed to submit transcription (upstream ${res.status})`, res.status);
  }

  return res.json();
}

export async function getTranscriptionTask(transcriptionId: string): Promise<TranscriptionTask> {
  const res = await fetch(`${BASE_URL}/open/partner/ai/transcriptions/${transcriptionId}`, {
    headers: transcriptionAuthHeaders(),
  });

  if (!res.ok) {
    throw new PlaudApiError(
      `Failed to fetch transcription task (upstream ${res.status})`,
      res.status,
    );
  }

  return res.json();
}
