import { env } from "cloudflare:test";
import { exportJWK, generateKeyPair, SignJWT, UnsecuredJWT, type JWK } from "jose";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { verifyIdToken, type Provider } from "../../src/auth";

const APPLE_KEYS = "https://appleid.apple.com/auth/keys";
const GOOGLE_KEYS = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUER: Record<Provider, string> = { apple: "https://appleid.apple.com", google: "https://accounts.google.com" };
const AUDIENCE: Record<Provider, string> = { apple: env.APPLE_AUDIENCES, google: env.GOOGLE_AUDIENCES };
const OTHER: Record<Provider, Provider> = { apple: "google", google: "apple" };

// Known-answer SHA-256 test vector (FIPS 180-2, "abc"), so the Apple form is not computed the same way as the code.
const NONCE = "abc";
const NONCE_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

type Keys = { rsa: CryptoKey; rogueRsa: CryptoKey; ec: CryptoKey; next: CryptoKey; nextJwk: JWK; published: JWK[] };
// What the fake key endpoint serves right now; reset by tick().
const serving = {} as Record<Provider, JWK[]>;
const keys = {} as Record<Provider, Keys>;
// Each test uses its own time far apart from the others, so the per-isolate key cache never carries over.
let clock = Date.UTC(2027, 0, 1);
let now = clock;


function nonceClaim(provider: Provider): string {
	return provider === "apple" ? NONCE_SHA256 : NONCE;
}

function sign(
	provider: Provider,
	overrides: { alg?: string; kid?: string; key?: CryptoKey; claims?: Record<string, unknown>; exp?: number } = {},
): Promise<string> {
	const k = keys[provider];
	return new SignJWT({
		iss: ISSUER[provider],
		aud: AUDIENCE[provider],
		sub: `${provider}-user-1`,
		iat: Math.floor(now / 1000) - 10,
		exp: overrides.exp ?? Math.floor(now / 1000) + 600,
		nonce: nonceClaim(provider),
		...overrides.claims,
	})
		.setProtectedHeader({ alg: overrides.alg ?? "RS256", kid: overrides.kid ?? `${provider}-rsa` })
		.sign(overrides.key ?? k.rsa);
}

let fetches: string[] = [];
const cacheHeaders: Record<string, string> = {
	[APPLE_KEYS]: "no-store", // what Apple serves today
	[GOOGLE_KEYS]: "public, max-age=19701, must-revalidate, no-transform", // what Google serves today
};

beforeAll(async () => {
	for (const provider of ["apple", "google"] as Provider[]) {
		const rsa = await generateKeyPair("RS256", { extractable: true });
		const rogueRsa = await generateKeyPair("RS256", { extractable: true });
		const ec = await generateKeyPair("ES256", { extractable: true });
		const next = await generateKeyPair("RS256", { extractable: true });
		keys[provider] = {
			next: next.privateKey,
			nextJwk: { ...(await exportJWK(next.publicKey)), kid: `${provider}-next`, alg: "RS256", use: "sig" },
			rsa: rsa.privateKey,
			rogueRsa: rogueRsa.privateKey,
			ec: ec.privateKey,
			published: [
				{ ...(await exportJWK(rsa.publicKey)), kid: `${provider}-rsa`, alg: "RS256", use: "sig" },
				// Published so that only the allow-list, not a missing key, rejects ES256.
				{ ...(await exportJWK(ec.publicKey)), kid: `${provider}-ec`, alg: "ES256", use: "sig" },
			],
		};
	}
});

function serveKeys(): void {
	vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
		const url = input instanceof Request ? input.url : String(input);
		fetches.push(url);
		const provider = url === APPLE_KEYS ? "apple" : url === GOOGLE_KEYS ? "google" : null;
		if (!provider) return new Response("unexpected", { status: 500 });
		return Response.json({ keys: serving[provider] }, { headers: { "cache-control": cacheHeaders[url] } });
	});
}

function tick(): void {
	serving.apple = keys.apple.published;
	serving.google = keys.google.published;
	clock += 30 * 24 * 3600 * 1000;
	now = clock;
	fetches = [];
	serveKeys();
}

afterEach(() => {
	vi.restoreAllMocks();
});

describe.each(["apple", "google"] as Provider[])("verifyIdToken(%s)", (provider) => {
	it("accepts a valid token and returns its subject", async () => {
		tick();
		expect(await verifyIdToken(provider, await sign(provider), NONCE, env, now)).toEqual({ sub: `${provider}-user-1` });
	});

	it.each<[string, () => Promise<string>]>([
		["expired", () => sign(provider, { exp: Math.floor(now / 1000) - 1 })],
		["wrong audience", () => sign(provider, { claims: { aud: "someone.else" } })],
		["wrong issuer", () => sign(provider, { claims: { iss: "https://evil.example" } })],
		["bad signature", () => sign(provider, { key: keys[provider].rogueRsa })],
		["an algorithm outside the allow-list", () => sign(provider, { alg: "ES256", kid: `${provider}-ec`, key: keys[provider].ec })],
		["a key id the provider does not publish", () => sign(provider, { kid: "not-published" })],
		["a nonce claim mismatch", () => sign(provider, { claims: { nonce: "another-nonce" } })],
		["the other provider's nonce form", () => sign(provider, { claims: { nonce: nonceClaim(OTHER[provider]) } })],
		["a missing nonce claim", () => sign(provider, { claims: { nonce: undefined } })],
		["an empty subject", () => sign(provider, { claims: { sub: "" } })],
		["the other provider's issuer", () => sign(provider, { claims: { iss: ISSUER[OTHER[provider]] } })],
	])("rejects %s", async (_, token) => {
		tick();
		expect(await verifyIdToken(provider, await token(), NONCE, env, now)).toBeNull();
	});

	it("rejects alg none", async () => {
		tick();
		const token = new UnsecuredJWT({ nonce: nonceClaim(provider) })
			.setIssuer(ISSUER[provider])
			.setAudience(AUDIENCE[provider])
			.setSubject("x")
			.setExpirationTime(Math.floor(now / 1000) + 600)
			.encode();
		expect(await verifyIdToken(provider, token, NONCE, env, now)).toBeNull();
	});

	it("rejects a token that is not a JWT", async () => {
		tick();
		expect(await verifyIdToken(provider, "not.a.jwt", NONCE, env, now)).toBeNull();
	});

	it("rejects everything when no audience is configured", async () => {
		tick();
		const noAudience: Env = { ...env, APPLE_AUDIENCES: "", GOOGLE_AUDIENCES: "" };
		expect(await verifyIdToken(provider, await sign(provider), NONCE, noAudience, now)).toBeNull();
		expect(fetches).toEqual([]);
	});

	it("accepts any of several comma-separated audiences", async () => {
		tick();
		const several: Env = Object.assign({}, env, { APPLE_AUDIENCES: ` other.app , ${AUDIENCE.apple}`, GOOGLE_AUDIENCES: `other.app,${AUDIENCE.google} ` });
		expect(await verifyIdToken(provider, await sign(provider), NONCE, several, now)).toEqual({ sub: `${provider}-user-1` });
	});

	it("reports unavailable when the key endpoint fails", async () => {
		tick();
		vi.restoreAllMocks();
		vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("down", { status: 503 }));
		expect(await verifyIdToken(provider, await sign(provider), NONCE, env, now)).toBe("unavailable");
	});

	it("reports unavailable when the key endpoint is unreachable or returns no key set", async () => {
		tick();
		vi.restoreAllMocks();
		vi.spyOn(globalThis, "fetch").mockRejectedValueOnce(new TypeError("network")).mockResolvedValueOnce(Response.json({}));
		expect(await verifyIdToken(provider, await sign(provider), NONCE, env, now)).toBe("unavailable");
		expect(await verifyIdToken(provider, await sign(provider), NONCE, env, now)).toBe("unavailable");
	});
});

describe("provider key caching", () => {
	const HOUR = 3600 * 1000;

	function keyEndpointOutage(): void {
		vi.restoreAllMocks();
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
			fetches.push(String(input));
			throw new TypeError("network down");
		});
	}

	it("fetches Apple keys once for two logins inside the hour and again after it, despite no-store", async () => {
		tick();
		const t0 = now;
		const token = await sign("apple", { exp: Math.floor(t0 / 1000) + 2 * 3600 });
		expect(await verifyIdToken("apple", token, NONCE, env, t0)).not.toBeNull();
		expect(await verifyIdToken("apple", token, NONCE, env, t0 + HOUR - 1)).not.toBeNull();
		expect(fetches).toEqual([APPLE_KEYS]);
		expect(await verifyIdToken("apple", token, NONCE, env, t0 + HOUR)).not.toBeNull();
		expect(fetches).toEqual([APPLE_KEYS, APPLE_KEYS]);
	});

	it("caches Google keys for their max-age (19701 s) and refetches after it", async () => {
		tick();
		const t0 = now;
		const token = await sign("google", { exp: Math.floor(t0 / 1000) + 30_000 });
		expect(await verifyIdToken("google", token, NONCE, env, t0)).not.toBeNull();
		expect(await verifyIdToken("google", token, NONCE, env, t0 + 19_700_000)).not.toBeNull();
		expect(fetches).toEqual([GOOGLE_KEYS]);
		expect(await verifyIdToken("google", token, NONCE, env, t0 + 19_702_000)).not.toBeNull();
		expect(fetches).toEqual([GOOGLE_KEYS, GOOGLE_KEYS]);
	});

	it("caps a long max-age at 24 hours and subtracts the Age header", async () => {
		tick();
		vi.restoreAllMocks();
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
			fetches.push(String(input));
			return Response.json({ keys: serving.google }, { headers: { "cache-control": "max-age=999999", age: "10" } });
		});
		const t0 = now;
		const token = await sign("google", { exp: Math.floor(t0 / 1000) + 2 * 24 * 3600 });
		await verifyIdToken("google", token, NONCE, env, t0);
		await verifyIdToken("google", token, NONCE, env, t0 + 24 * HOUR - 1);
		expect(fetches).toEqual([GOOGLE_KEYS]);
		await verifyIdToken("google", token, NONCE, env, t0 + 24 * HOUR);
		expect(fetches).toEqual([GOOGLE_KEYS, GOOGLE_KEYS]);

		tick();
		vi.restoreAllMocks();
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
			fetches.push(String(input));
			return Response.json({ keys: serving.google }, { headers: { "cache-control": "max-age=7300", age: "100" } });
		});
		const t1 = now;
		const token2 = await sign("google", { exp: Math.floor(t1 / 1000) + 3 * 3600 });
		await verifyIdToken("google", token2, NONCE, env, t1);
		await verifyIdToken("google", token2, NONCE, env, t1 + 7_200_000 - 1);
		expect(fetches).toEqual([GOOGLE_KEYS]);
		await verifyIdToken("google", token2, NONCE, env, t1 + 7_200_000);
		expect(fetches).toEqual([GOOGLE_KEYS, GOOGLE_KEYS]);
	});

	it.each(["apple", "google"] as Provider[])(
		"%s: an unknown key id triggers exactly one refetch and succeeds once the provider publishes the key",
		async (provider) => {
			tick();
			const t0 = now;
			expect(await verifyIdToken(provider, await sign(provider), NONCE, env, t0)).not.toBeNull();
			serving[provider] = [...keys[provider].published, keys[provider].nextJwk];
			now = t0 + 60_000;
			const rotated = await sign(provider, { kid: `${provider}-next`, key: keys[provider].next });
			expect(await verifyIdToken(provider, rotated, NONCE, env, now)).toEqual({ sub: `${provider}-user-1` });
			expect(fetches.length).toBe(2);
			expect(await verifyIdToken(provider, rotated, NONCE, env, now + 1)).not.toBeNull();
			expect(fetches.length).toBe(2);
		},
	);

	it("does not refetch for a second unknown key id within 30 seconds", async () => {
		tick();
		const t0 = now;
		await verifyIdToken("google", await sign("google"), NONCE, env, t0);
		now = t0 + 60_000;
		expect(await verifyIdToken("google", await sign("google", { kid: "made-up-1" }), NONCE, env, now)).toBeNull();
		expect(fetches).toEqual([GOOGLE_KEYS, GOOGLE_KEYS]);
		expect(await verifyIdToken("google", await sign("google", { kid: "made-up-2" }), NONCE, env, now + 29_999)).toBeNull();
		expect(fetches).toEqual([GOOGLE_KEYS, GOOGLE_KEYS]);
		expect(await verifyIdToken("google", await sign("google", { kid: "made-up-3" }), NONCE, env, now + 30_000)).toBeNull();
		expect(fetches).toEqual([GOOGLE_KEYS, GOOGLE_KEYS, GOOGLE_KEYS]);
	});

	it("keeps using a cached set younger than 24 hours when a fetch fails", async () => {
		tick();
		const t0 = now;
		const token = await sign("apple", { exp: Math.floor(t0 / 1000) + 25 * 3600 });
		expect(await verifyIdToken("apple", token, NONCE, env, t0)).not.toBeNull();
		keyEndpointOutage();
		// Expired after 1 hour, refetch fails, the 2-hour-old set is still used.
		expect(await verifyIdToken("apple", token, NONCE, env, t0 + 2 * HOUR)).not.toBeNull();
		// An unknown kid's refetch also fails; the cached set does not contain it, so it is rejected.
		expect(await verifyIdToken("apple", await sign("apple", { kid: "unknown" }), NONCE, env, t0 + 3 * HOUR)).toBeNull();
		// At 24 hours the cached set is no longer usable.
		expect(await verifyIdToken("apple", token, NONCE, env, t0 + 24 * HOUR)).toBe("unavailable");
		expect(fetches).toEqual([APPLE_KEYS, APPLE_KEYS, APPLE_KEYS, APPLE_KEYS]);
	});

	it("reports unavailable when there is no cached set and the fetch fails", async () => {
		tick();
		keyEndpointOutage();
		expect(await verifyIdToken("google", await sign("google"), NONCE, env, now)).toBe("unavailable");
		expect(fetches).toEqual([GOOGLE_KEYS]);
	});
});
