import { SignJWT, importPKCS8 } from "jose";

/*
 * Sign in with Apple REST calls needed for account deletion (plan 4.7; docs/versions/backend.md, "Apple token revocation").
 * - The authorization code the app (or the web page) receives is exchanged once for a refresh token:
 *   POST https://appleid.apple.com/auth/token, form grant_type=authorization_code, code, client_id, client_secret.
 * - At deletion the refresh token is revoked: POST https://appleid.apple.com/auth/revoke, form client_id, client_secret,
 *   token, token_type_hint=refresh_token. 200 means revoked.
 * - client_secret is a JWT signed ES256 with the Sign in with Apple key (secret APPLE_P8, key id APPLE_KEY_ID):
 *   iss = team id, sub = client id, aud = https://appleid.apple.com, at most 6 months valid.
 */
export const APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";
export const APPLE_REVOKE_URL = "https://appleid.apple.com/auth/revoke";
const TIMEOUT_MS = 5_000;
const SECRET_TTL_S = 300;

type AppleEnv = Pick<Env, "APPLE_P8" | "APPLE_KEY_ID"> & { APPLE_TEAM_ID?: string };

/** Null when the key, its id or the team id is not configured. */
async function clientSecret(env: AppleEnv, clientId: string, now: number): Promise<string | null> {
	if (!env.APPLE_P8 || !env.APPLE_KEY_ID || !env.APPLE_TEAM_ID) return null;
	const key = await importPKCS8(env.APPLE_P8, "ES256");
	const iat = Math.floor(now / 1000);
	return new SignJWT({})
		.setProtectedHeader({ alg: "ES256", kid: env.APPLE_KEY_ID })
		.setIssuer(env.APPLE_TEAM_ID)
		.setSubject(clientId)
		.setAudience("https://appleid.apple.com")
		.setIssuedAt(iat)
		.setExpirationTime(iat + SECRET_TTL_S)
		.sign(key);
}

async function post(url: string, form: Record<string, string>): Promise<Response | null> {
	try {
		return await fetch(url, {
			method: "POST",
			headers: { "content-type": "application/x-www-form-urlencoded" },
			body: new URLSearchParams(form),
			signal: AbortSignal.timeout(TIMEOUT_MS),
		});
	} catch {
		return null;
	}
}

/**
 * The refresh token for an authorization code; "invalid" when Apple refuses the code for good (used or expired, which
 * leaves nothing to revoke); null when Apple or the key is unavailable, so the caller retries.
 */
export async function exchangeAppleCode(
	env: AppleEnv,
	clientId: string,
	code: string,
	now: number,
	redirectUri?: string,
): Promise<string | "invalid" | null> {
	const secret = await clientSecret(env, clientId, now);
	if (!secret) return null;
	// redirect_uri only when the authorization request had one (the web page); the app's native request has none.
	const form = { client_id: clientId, client_secret: secret, code, grant_type: "authorization_code", ...(redirectUri ? { redirect_uri: redirectUri } : {}) };
	const res = await post(APPLE_TOKEN_URL, form);
	if (!res) return null;
	const body = (await res.json().catch(() => null)) as { refresh_token?: unknown; error?: unknown } | null;
	if (res.ok && typeof body?.refresh_token === "string") return body.refresh_token;
	return res.status === 400 && body?.error === "invalid_grant" ? "invalid" : null;
}

/** True once Apple confirmed the revocation; false means try again later. Never logs the token. */
export async function revokeAppleToken(env: AppleEnv, clientId: string, refreshToken: string, now: number): Promise<boolean> {
	const secret = await clientSecret(env, clientId, now);
	if (!secret) return false;
	const res = await post(APPLE_REVOKE_URL, { client_id: clientId, client_secret: secret, token: refreshToken, token_type_hint: "refresh_token" });
	await res?.body?.cancel();
	return res?.status === 200;
}
