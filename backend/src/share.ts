/**
 * Share links (plan 4.4, "Entry points that address an object other than the caller's own").
 * A share token is key id + AES-GCM over the owner's user id and a 128-bit random share id, with an HKDF subkey of
 * SERVER_KEY under its own info label. A token that does not authenticate is rejected here, before any object is used.
 */
import { base64url, bytes, fromBase64url, hex, hkdfHmacKey } from "./auth/encoding";

const DAY_MS = 86_400_000;
/** Links expire after 30 days; SERVER_KEY_PREVIOUS must stay set this long after a rotation (docs/api.md, M5). */
export const SHARE_TTL_MS = 30 * DAY_MS;
/** Distinct recipients per link; the eleventh gets 410. */
export const SHARE_RECIPIENT_LIMIT = 10;
/** New links per subscriber and UTC day. */
export const SHARES_PER_DAY = 10;
/** Picture checks (vision model calls) per user and UTC day; a failed share creation still uses them. */
export const SCREENS_PER_DAY = 30;
/** A report reference is accepted for this long (plan 4.10); a timestamp check, see reportReference. */
export const REPORT_REFERENCE_TTL_MS = 30 * DAY_MS;

const SHARE_KEY_INFO = "recipes/share-token/v1";
const SHARE_KEY_ID_INFO = "recipes/share-token-key-id/v1";
const REPORT_REF_INFO = "recipes/report-reference/v1";
const AAD = bytes("recipes/share/v1");

type KeyEnv = Pick<Env, "SERVER_KEY"> & { SERVER_KEY_PREVIOUS?: string };
const keys = (env: KeyEnv) => [env.SERVER_KEY, env.SERVER_KEY_PREVIOUS].filter((k): k is string => !!k);

async function aesKey(secret: string, info: string): Promise<CryptoKey> {
	const ikm = await crypto.subtle.importKey("raw", bytes(secret), "HKDF", false, ["deriveKey"]);
	return crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: bytes(info) }, ikm, { name: "AES-GCM", length: 256 }, false, [
		"encrypt",
		"decrypt",
	]);
}

/** 4 bytes that name the key, so a token is opened with the right one of SERVER_KEY and SERVER_KEY_PREVIOUS. */
async function keyId(secret: string): Promise<Uint8Array> {
	return new Uint8Array(await crypto.subtle.sign("HMAC", await hkdfHmacKey(secret, SHARE_KEY_ID_INFO), bytes("key-id"))).slice(0, 4);
}

const concat = (...parts: Uint8Array[]) => {
	const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
	let at = 0;
	for (const p of parts) out.set(p, (at += p.length) - p.length);
	return out;
};

/** A new owner-side handle: 128 random bits as 32 lowercase hex characters. */
export const newShareId = () => hex(crypto.getRandomValues(new Uint8Array(16)).buffer);
export const SHARE_ID = /^[0-9a-f]{32}$/;

/** 4 key id + 12 IV + (32 user id + 16 share id + 16 tag) = 80 bytes, 107 base64url characters. */
const SHARE_TOKEN = /^[A-Za-z0-9_-]{107}$/;

export async function newShareToken(ownerId: string, shareId: string, env: KeyEnv): Promise<string> {
	const owner = fromBase64url(ownerId);
	if (!owner || owner.length !== 32 || !SHARE_ID.test(shareId)) throw new Error("owner id or share id has the wrong format");
	const iv = crypto.getRandomValues(new Uint8Array(12));
	const plain = concat(owner, Uint8Array.from(shareId.match(/../g)!, (h) => parseInt(h, 16)));
	const sealed = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: AAD }, await aesKey(env.SERVER_KEY, SHARE_KEY_INFO), plain);
	return base64url(concat(await keyId(env.SERVER_KEY), iv, new Uint8Array(sealed)));
}

/** The owner and share id, or null for anything that is not a token sealed with SERVER_KEY or SERVER_KEY_PREVIOUS. */
export async function openShareToken(token: string, env: KeyEnv): Promise<{ ownerId: string; shareId: string } | null> {
	const raw = SHARE_TOKEN.test(token) ? fromBase64url(token) : null;
	if (!raw) return null;
	for (const secret of keys(env)) {
		const id = await keyId(secret);
		if (!id.every((b, i) => raw[i] === b)) continue;
		try {
			const plain = new Uint8Array(
				await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.slice(4, 16), additionalData: AAD }, await aesKey(secret, SHARE_KEY_INFO), raw.slice(16)),
			);
			return { ownerId: base64url(plain.slice(0, 32)), shareId: hex(plain.slice(32).buffer) };
		} catch {
			return null;
		}
	}
	return null;
}

/** A lowercase UUID-shaped id derived from `input`, so the same share and recipient always give the same copy. */
export async function derivedId(input: string): Promise<string> {
	const h = hex(await crypto.subtle.digest("SHA-256", bytes(input))).slice(0, 32);
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * The only user reference a share-report metric carries (plan 4.10): AES-GCM over the creation time and the owner's
 * user id, with its own subkey. The 30 days are not enforced by the cryptography: openReportReference compares the
 * sealed timestamp. Whoever holds SERVER_KEY (or SERVER_KEY_PREVIOUS while it is set) can decrypt a reference of any
 * age until that key is rotated out.
 */
export async function reportReference(userId: string, now: number, env: KeyEnv): Promise<string> {
	const iv = crypto.getRandomValues(new Uint8Array(12));
	const sealed = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await aesKey(env.SERVER_KEY, REPORT_REF_INFO), bytes(`${now}.${userId}`));
	return base64url(concat(iv, new Uint8Array(sealed)));
}

export async function openReportReference(reference: string, env: KeyEnv, now: number): Promise<string | null> {
	const raw = fromBase64url(reference);
	if (!raw || raw.length < 29) return null;
	for (const secret of keys(env)) {
		try {
			const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.slice(0, 12) }, await aesKey(secret, REPORT_REF_INFO), raw.slice(12));
			const [, at, userId] = /^(\d{1,15})\.(.+)$/.exec(new TextDecoder().decode(plain)) ?? [];
			return at !== undefined && now - Number(at) < REPORT_REFERENCE_TTL_MS ? userId : null;
		} catch {
			// Not this key; try the next one.
		}
	}
	return null;
}
