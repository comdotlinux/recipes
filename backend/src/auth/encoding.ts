const utf8 = new TextEncoder();

export function bytes(text: string): Uint8Array<ArrayBuffer> {
	return new Uint8Array(utf8.encode(text));
}

export function base64url(data: ArrayBuffer | Uint8Array<ArrayBuffer>): string {
	return btoa(String.fromCharCode(...new Uint8Array(data)))
		.replaceAll("+", "-")
		.replaceAll("/", "_")
		.replace(/=+$/, "");
}

/** Null unless the text is canonical unpadded base64url (re-encoding gives the same text). */
export function fromBase64url(text: string): Uint8Array<ArrayBuffer> | null {
	if (!/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) return null;
	const decoded = Uint8Array.from(atob(text.replaceAll("-", "+").replaceAll("_", "/")), (c) => c.charCodeAt(0));
	return base64url(decoded) === text ? decoded : null;
}

export function hex(data: ArrayBuffer): string {
	return Array.from(new Uint8Array(data), (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sha256Hex(text: string): Promise<string> {
	return hex(await crypto.subtle.digest("SHA-256", bytes(text)));
}

export function randomBase64url(byteCount: number): string {
	return base64url(crypto.getRandomValues(new Uint8Array(byteCount)));
}

export function hmacKey(secret: string, usage: ("sign" | "verify")[]): Promise<CryptoKey> {
	return crypto.subtle.importKey("raw", bytes(secret), { name: "HMAC", hash: "SHA-256" }, false, usage);
}

/** An HMAC-SHA256 subkey of `secret`, separated from other subkeys by the HKDF `info` label. */
export async function hkdfHmacKey(secret: string, info: string): Promise<CryptoKey> {
	const ikm = await crypto.subtle.importKey("raw", bytes(secret), "HKDF", false, ["deriveKey"]);
	return crypto.subtle.deriveKey(
		{ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: bytes(info) },
		ikm,
		{ name: "HMAC", hash: "SHA-256", length: 256 },
		false,
		["sign", "verify"],
	);
}
