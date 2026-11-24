import { base64url, bytes, fromBase64url, hkdfHmacKey, randomBase64url } from "./encoding";

const NONCE_TTL_MS = 5 * 60 * 1000;
// HKDF "info" separates the nonce subkey from the other SERVER_KEY subkeys (share tokens, admin references).
const NONCE_KEY_INFO = "recipes/nonce/v1";

// SERVER_KEY_PREVIOUS is optional and only set during a key rotation (plan 4.2, AC34).
type NonceEnv = Pick<Env, "SERVER_KEY"> & { SERVER_KEY_PREVIOUS?: string };

const nonceKey = (serverKey: string) => hkdfHmacKey(serverKey, NONCE_KEY_INFO);

/** Format: `<id>.<expiresAt ms>.<base64url HMAC-SHA256 over "<id>.<expiresAt>">`. */
export async function issueNonce(env: NonceEnv, now: number): Promise<string> {
	const payload = `${randomBase64url(16)}.${now + NONCE_TTL_MS}`;
	const signature = await crypto.subtle.sign("HMAC", await nonceKey(env.SERVER_KEY), bytes(payload));
	return `${payload}.${base64url(signature)}`;
}

export async function verifyNonce(
	nonce: string,
	env: NonceEnv,
	now: number,
): Promise<{ id: string; expiresAt: number } | null> {
	const match = /^([A-Za-z0-9_-]{22})\.(\d{1,15})\.([A-Za-z0-9_-]{43})$/.exec(nonce);
	if (!match) return null;
	const [, id, expires, sig] = match;
	const signature = fromBase64url(sig);
	const expiresAt = Number(expires);
	if (!signature || expiresAt <= now || expiresAt > now + NONCE_TTL_MS) return null;

	const keys = [env.SERVER_KEY, env.SERVER_KEY_PREVIOUS].filter((k): k is string => !!k);
	for (const key of keys) {
		// subtle.verify compares the MAC in constant time.
		if (await crypto.subtle.verify("HMAC", await nonceKey(key), signature, bytes(`${id}.${expires}`))) {
			return { id, expiresAt };
		}
	}
	return null;
}
