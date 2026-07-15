export const BASE_URL = "https://platform-us.plaud.ai/developer/api";

/** Error thrown when an upstream Plaud API call fails, carrying its HTTP status. */
export class PlaudApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = "PlaudApiError";
  }
}

export function requireEnv(key: string): string {
  const val = process.env[key];
  if (!val) throw new Error(`Missing required env var: ${key}`);
  return val;
}

interface PartnerTokenResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
}

interface UserTokenResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
}

/**
 * Mints a per-user access token via the Plaud partner OAuth flow.
 *
 * Two-step flow (mirrors token-retrieval-script/user-token-script.ts):
 *   1. Partner access token  — Basic auth (PLAUD_CLIENT_ID:PLAUD_SECRET_KEY)
 *   2. User access token     — Bearer partner token + { user_id, expires_in }
 */
export async function mintUserToken(
  userId: string,
  expiresIn = 86400,
): Promise<{ access_token: string; expires_in: number }> {
  const clientId = requireEnv("PLAUD_CLIENT_ID");
  const secretKey = requireEnv("PLAUD_SECRET_KEY");

  const basicAuth = Buffer.from(`${clientId}:${secretKey}`).toString("base64");

  // Step 1: partner access token.
  const partnerRes = await fetch(`${BASE_URL}/oauth/partner/access-token`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${basicAuth}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
  });

  if (!partnerRes.ok) {
    throw new PlaudApiError(
      `Failed to fetch partner access token (upstream ${partnerRes.status})`,
      partnerRes.status,
    );
  }

  const partnerData = (await partnerRes.json()) as PartnerTokenResponse;

  // Step 2: user access token.
  const userRes = await fetch(`${BASE_URL}/open/partner/users/access-token`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${partnerData.access_token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ user_id: userId, expires_in: expiresIn }),
  });

  if (!userRes.ok) {
    throw new PlaudApiError(
      `Failed to fetch user access token (upstream ${userRes.status})`,
      userRes.status,
    );
  }

  const userData = (await userRes.json()) as UserTokenResponse;

  return { access_token: userData.access_token, expires_in: userData.expires_in };
}
