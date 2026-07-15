import { NextResponse } from "next/server";
import { mintUserToken, PlaudApiError } from "@/lib/plaud-auth";

// Uses Buffer for Basic auth — requires the Node.js runtime.
export const runtime = "nodejs";

/**
 * POST /api/user-token
 * Body: { "user_id": "<plaud user id>" }
 * Returns: { "access_token": "...", "expires_in": 86400 }
 *
 * Requires PLAUD_CLIENT_ID and PLAUD_SECRET_KEY in the environment
 * (e.g. next-backend/.env.local).
 */
export async function POST(req: Request) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json(
      { error: "Request body must be valid JSON" },
      { status: 400 },
    );
  }

  const userId = (body as { user_id?: unknown })?.user_id;
  if (typeof userId !== "string" || userId.trim() === "") {
    return NextResponse.json(
      { error: "Missing or invalid 'user_id' in request body" },
      { status: 400 },
    );
  }

  try {
    const token = await mintUserToken(userId);
    console.log("successfully retrieved token for ", userId);
    return NextResponse.json(token);
  } catch (err) {
    if (err instanceof PlaudApiError) {
      return NextResponse.json({ error: err.message }, { status: 502 });
    }
    // Missing env vars / unexpected failures — don't leak details to the caller.
    console.error("user-token route error:", err);
    return NextResponse.json(
      { error: "Failed to mint user token" },
      { status: 500 },
    );
  }
}
