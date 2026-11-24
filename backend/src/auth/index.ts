import { base64url, bytes, fromBase64url, hkdfHmacKey, hmacKey, randomBase64url, sha256Hex } from "./encoding";
import type { Provider } from "./id-token";

export { verifyIdToken, type Provider } from "./id-token";
export { issueNonce, verifyNonce } from "./nonce";

// https://developers.cloudflare.com/turnstile/get-started/server-side-validation/ (checked 2026-11-24):
// POST JSON { secret, response, remoteip? }; the reply is JSON with a boolean `success`; tokens are at most 2048 chars.
const SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
const TURNSTILE_TOKEN_MAX = 2048;

export async function verifyTurnstile(token: string, ip: string | null, env: Env): Promise<boolean> {
	if (token.length === 0 || token.length > TURNSTILE_TOKEN_MAX) return false;
	try {
		const response = await fetch(SITEVERIFY_URL, {
			method: "POST",
			headers: { "content-type": "application/json" },
			body: JSON.stringify({ secret: env.TURNSTILE_SECRET, response: token, ...(ip ? { remoteip: ip } : {}) }),
		});
		if (!response.ok) return false;
		// The reply's hostname is not checked: the widget's domain list (set by the provisioning script, M6) is the control.
		const result = (await response.json()) as { success?: unknown };
		return result.success === true;
	} catch {
		// A network or parse error must never let a request through.
		return false;
	}
}

export async function deriveUserId(provider: Provider | "test", sub: string, env: Env): Promise<string> {
	const key = await hmacKey(env.USER_ID_PEPPER, ["sign"]);
	return base64url(await crypto.subtle.sign("HMAC", key, bytes(`${provider}:${sub}`)));
}

// Token = `v1.<userId>.<secret>.<tag>`. The tag lets the Worker reject a forged token without touching any object.
const SESSION_TOKEN = /^v1\.([A-Za-z0-9_-]{43})\.([A-Za-z0-9_-]{43})\.([A-Za-z0-9_-]{43})$/;
// HKDF "info" for the session-token tag; distinct from the nonce subkey.
const SESSION_TAG_INFO = "recipes/session-token/v1";

type TokenEnv = Pick<Env, "SERVER_KEY"> & { SERVER_KEY_PREVIOUS?: string };

export async function newSessionToken(userId: string, env: TokenEnv): Promise<{ token: string; secretHash: string }> {
	if (!fromBase64url(userId) || userId.length !== 43) throw new Error("userId must be 43 base64url characters");
	const secret = randomBase64url(32);
	const body = `v1.${userId}.${secret}`;
	const tag = base64url(await crypto.subtle.sign("HMAC", await hkdfHmacKey(env.SERVER_KEY, SESSION_TAG_INFO), bytes(body)));
	return { token: `${body}.${tag}`, secretHash: await sha256Hex(secret) };
}

/** Null when the format or the tag is wrong. Accepts a tag made with SERVER_KEY_PREVIOUS during a rotation. */
export async function parseSessionToken(token: string, env: TokenEnv): Promise<{ userId: string; secretHash: string } | null> {
	const match = SESSION_TOKEN.exec(token);
	if (!match || !fromBase64url(match[1]) || !fromBase64url(match[2])) return null;
	const tag = fromBase64url(match[3]);
	if (!tag) return null;
	const body = bytes(`v1.${match[1]}.${match[2]}`);
	for (const key of [env.SERVER_KEY, env.SERVER_KEY_PREVIOUS].filter((k): k is string => !!k)) {
		// subtle.verify compares the MAC in constant time.
		if (await crypto.subtle.verify("HMAC", await hkdfHmacKey(key, SESSION_TAG_INFO), tag, body)) {
			return { userId: match[1], secretHash: await sha256Hex(match[2]) };
		}
	}
	return null;
}
