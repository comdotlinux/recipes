import { env } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import { deriveUserId, issueNonce, newSessionToken, parseSessionToken, verifyNonce, verifyTurnstile } from "../../src/auth";

const NOW = Date.UTC(2026, 10, 24, 12, 0, 0);
const FIVE_MINUTES = 5 * 60 * 1000;

afterEach(() => {
	vi.restoreAllMocks();
});

describe("nonce", () => {
	it("is accepted while fresh and returns its id and expiry", async () => {
		const nonce = await issueNonce(env, NOW);
		const result = await verifyNonce(nonce, env, NOW + FIVE_MINUTES - 1);
		expect(result).toEqual({ id: nonce.split(".")[0], expiresAt: NOW + FIVE_MINUTES });
		expect(result?.id).toMatch(/^[A-Za-z0-9_-]{22}$/);
	});

	it("has a different id every time", async () => {
		const a = await verifyNonce(await issueNonce(env, NOW), env, NOW);
		const b = await verifyNonce(await issueNonce(env, NOW), env, NOW);
		expect(a?.id).not.toBe(b?.id);
	});

	it("is rejected once expired", async () => {
		const nonce = await issueNonce(env, NOW);
		expect(await verifyNonce(nonce, env, NOW + FIVE_MINUTES)).toBeNull();
	});

	it("is rejected when tampered", async () => {
		const nonce = await issueNonce(env, NOW);
		const [id, expires, sig] = nonce.split(".");
		const flip = (s: string) => (s[0] === "A" ? "B" : "A") + s.slice(1);
		expect(await verifyNonce(`${flip(id)}.${expires}.${sig}`, env, NOW)).toBeNull();
		expect(await verifyNonce(`${id}.${Number(expires) + 1000}.${sig}`, env, NOW)).toBeNull();
		expect(await verifyNonce(`${id}.${expires}.${flip(sig)}`, env, NOW)).toBeNull();
	});

	it("is rejected when signed with an unknown key", async () => {
		const nonce = await issueNonce({ ...env, SERVER_KEY: "some-other-key" }, NOW);
		expect(await verifyNonce(nonce, env, NOW)).toBeNull();
	});

	it("is accepted under the previous key during rotation, and not after the previous key is removed", async () => {
		const nonce = await issueNonce(env, NOW);
		const rotated = { ...env, SERVER_KEY: "new-server-key", SERVER_KEY_PREVIOUS: env.SERVER_KEY };
		expect(await verifyNonce(nonce, rotated, NOW)).not.toBeNull();
		expect(await verifyNonce(await issueNonce(rotated, NOW), rotated, NOW)).not.toBeNull();
		expect(await verifyNonce(nonce, { ...env, SERVER_KEY: "new-server-key" }, NOW)).toBeNull();
	});

	it("rejects an expiry further ahead than the nonce lifetime, even when signed", async () => {
		const fromTheFuture = await issueNonce(env, NOW + 60_000);
		expect(await verifyNonce(fromTheFuture, env, NOW)).toBeNull();
	});

	it.each(["", "abc", "a.b.c", "v1..", `${"A".repeat(22)}.1.${"A".repeat(43)}x`, `${"A".repeat(22)}.-1.${"A".repeat(43)}`])(
		"rejects malformed input %j",
		async (input) => {
			expect(await verifyNonce(input, env, NOW)).toBeNull();
		},
	);
});

describe("verifyTurnstile", () => {
	const SITEVERIFY = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

	it("returns true on success and sends secret, token and IP as JSON", async () => {
		const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ success: true }));
		expect(await verifyTurnstile("token-1", "203.0.113.7", env)).toBe(true);
		const [url, init] = fetchSpy.mock.calls[0];
		expect(url).toBe(SITEVERIFY);
		expect(init?.method).toBe("POST");
		expect(JSON.parse(String(init?.body))).toEqual({ secret: env.TURNSTILE_SECRET, response: "token-1", remoteip: "203.0.113.7" });
	});

	it("omits remoteip when the IP is unknown", async () => {
		const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ success: true }));
		expect(await verifyTurnstile("token-1", null, env)).toBe(true);
		expect(JSON.parse(String(fetchSpy.mock.calls[0][1]?.body))).toEqual({ secret: env.TURNSTILE_SECRET, response: "token-1" });
	});

	it("returns false when Turnstile rejects the token", async () => {
		vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ success: false, "error-codes": ["invalid-input-response"] }));
		expect(await verifyTurnstile("bad", "203.0.113.7", env)).toBe(false);
	});

	it("returns false on a network error", async () => {
		vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("network down"));
		expect(await verifyTurnstile("token-1", null, env)).toBe(false);
	});

	it("returns false on an HTTP error or a body that is not JSON", async () => {
		vi.spyOn(globalThis, "fetch")
			.mockResolvedValueOnce(Response.json({ success: true }, { status: 500 }))
			.mockResolvedValueOnce(new Response("<html>"));
		expect(await verifyTurnstile("token-1", null, env)).toBe(false);
		expect(await verifyTurnstile("token-1", null, env)).toBe(false);
	});

	it("returns false for a truthy success that is not the boolean true", async () => {
		vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({ success: "true" }));
		expect(await verifyTurnstile("token-1", null, env)).toBe(false);
	});

	it("rejects an empty or over-long token without calling siteverify", async () => {
		const fetchSpy = vi.spyOn(globalThis, "fetch");
		expect(await verifyTurnstile("", null, env)).toBe(false);
		expect(await verifyTurnstile("x".repeat(2049), null, env)).toBe(false);
		expect(fetchSpy).not.toHaveBeenCalled();
	});
});

describe("deriveUserId", () => {
	it("is 43 base64url characters and stable for the same input", async () => {
		const a = await deriveUserId("google", "1234", env);
		expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
		expect(await deriveUserId("google", "1234", env)).toBe(a);
	});

	it("differs across providers and subjects", async () => {
		const ids = new Set([
			await deriveUserId("google", "1234", env),
			await deriveUserId("apple", "1234", env),
			await deriveUserId("test", "1234", env),
			await deriveUserId("google", "12345", env),
		]);
		expect(ids.size).toBe(4);
	});

	it("depends on the pepper", async () => {
		const other = await deriveUserId("google", "1234", { ...env, USER_ID_PEPPER: "other-pepper" });
		expect(other).not.toBe(await deriveUserId("google", "1234", env));
	});

	it("matches HMAC-SHA256 over provider:sub with the pepper", async () => {
		const key = await crypto.subtle.importKey(
			"raw",
			new TextEncoder().encode(env.USER_ID_PEPPER),
			{ name: "HMAC", hash: "SHA-256" },
			false,
			["sign"],
		);
		const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode("apple:000123.abc")));
		const expected = btoa(String.fromCharCode(...mac)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
		expect(await deriveUserId("apple", "000123.abc", env)).toBe(expected);
	});
});

describe("session token", () => {
	const USER_ID = "A".repeat(42) + "w";

	it("round-trips and stores only the SHA-256 hex of the secret", async () => {
		const { token, secretHash } = await newSessionToken(USER_ID, env);
		const [version, userId, secret, tag] = token.split(".");
		expect(version).toBe("v1");
		expect(tag).toMatch(/^[A-Za-z0-9_-]{43}$/);
		expect(userId).toBe(USER_ID);
		expect(secret).toMatch(/^[A-Za-z0-9_-]{43}$/);
		expect(secretHash).toMatch(/^[0-9a-f]{64}$/);
		expect(secretHash).not.toContain(secret);
		const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret)));
		expect(secretHash).toBe(Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join(""));
		expect(await parseSessionToken(token, env)).toEqual({ userId: USER_ID, secretHash });
	});

	it("uses a new secret every time", async () => {
		const a = await newSessionToken(USER_ID, env);
		const b = await newSessionToken(USER_ID, env);
		expect(a.token).not.toBe(b.token);
		expect(a.secretHash).not.toBe(b.secretHash);
	});

	it("refuses a user id that is not 43 base64url characters", async () => {
		await expect(newSessionToken("short", env)).rejects.toThrow();
	});

	it("works with a real derived user id", async () => {
		const userId = await deriveUserId("google", "1234", env);
		const { token } = await newSessionToken(userId, env);
		expect((await parseSessionToken(token, env))?.userId).toBe(userId);
	});

	it("rejects a token whose tag does not match (forged, other user id, or other key)", async () => {
		const { token } = await newSessionToken(USER_ID, env);
		const [v, , secret, tag] = token.split(".");
		const otherUser = "B".repeat(42) + "w";
		expect(await parseSessionToken(`${v}.${otherUser}.${secret}.${tag}`, env)).toBeNull();
		expect(await parseSessionToken(`${v}.${USER_ID}.${secret}.${"A".repeat(42)}w`, env)).toBeNull();
		expect(await parseSessionToken(token, { ...env, SERVER_KEY: "another-key" })).toBeNull();
	});

	it("accepts a tag made with SERVER_KEY_PREVIOUS during a rotation", async () => {
		const { token, secretHash } = await newSessionToken(USER_ID, env);
		const rotated = { ...env, SERVER_KEY: "new-server-key", SERVER_KEY_PREVIOUS: env.SERVER_KEY };
		expect(await parseSessionToken(token, rotated)).toEqual({ userId: USER_ID, secretHash });
		expect(await parseSessionToken(token, { ...env, SERVER_KEY: "new-server-key" })).toBeNull();
	});

	const valid43 = "A".repeat(42) + "w";
	it.each([
		"",
		"v1",
		`v1.${valid43}`,
		`v2.${valid43}.${valid43}`,
		`v1.${valid43}.${valid43}.`,
		`v1.${valid43}.${valid43}`,
		`v1.${valid43}.${valid43}.${valid43}.${valid43}`,
		`v1.${valid43}.${valid43.slice(1)}`,
		`v1.${valid43}.${valid43}A`,
		`v1.${valid43.slice(0, 42)}+.${valid43}`,
		`v1.${valid43}.${valid43.slice(0, 42)}=`,
		` v1.${valid43}.${valid43}`,
		// Non-canonical last character: its two unused low bits are not zero.
		`v1.${valid43}.${"A".repeat(42)}x`,
	])("returns null for malformed %j", async (input) => {
		expect(await parseSessionToken(input, env)).toBeNull();
	});
});
