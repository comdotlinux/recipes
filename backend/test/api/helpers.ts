import { exports } from "cloudflare:workers";
import { env, runInDurableObject } from "cloudflare:test";
import { SignJWT, exportJWK, generateKeyPair } from "jose";
import { vi } from "vitest";
import { testIdToken } from "../../src/testing/test-idp";
import type { UserDO } from "../../src/user-do";

export const BASE = "http://localhost/v1";

let ipCounter = 0;
/** A fresh client IP per call, so per-IP rate limits only trigger where a test wants them. */
export const freshIp = () => `198.51.100.${++ipCounter % 250}:${ipCounter}`;

export function call(path: string, init: RequestInit & { token?: string; ip?: string } = {}): Promise<Response> {
	const headers = new Headers(init.headers);
	if (init.token) headers.set("authorization", `Bearer ${init.token}`);
	headers.set("cf-connecting-ip", init.ip ?? freshIp());
	return exports.default.fetch(`${BASE}${path}`, { ...init, headers });
}

export const json = (body: unknown) => ({ body: JSON.stringify(body), headers: { "content-type": "application/json" } });

// ---- outbound fetch: provider keys and Turnstile ----

export const TURNSTILE_OK = "turnstile-ok";
export const REVENUECAT = "https://api.revenuecat.com/";
const keys = await generateKeyPair("RS256", { extractable: true });
const otherKeys = await generateKeyPair("RS256", { extractable: true });
const jwk = { ...(await exportJWK(keys.publicKey)), kid: "k1", alg: "RS256", use: "sig" };

/** Intercepts the global fetch used by the auth module. Call in beforeEach; vi.restoreAllMocks() undoes it. */
export function interceptOutbound() {
	const real = globalThis.fetch;
	return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
		const url = new Request(input, init).url;
		if (url === "https://challenges.cloudflare.com/turnstile/v0/siteverify") {
			const body = JSON.parse(String(init?.body)) as { response: string };
			return Response.json({ success: body.response === TURNSTILE_OK });
		}
		if (url === "https://appleid.apple.com/auth/keys" || url === "https://www.googleapis.com/oauth2/v3/certs") {
			return Response.json({ keys: [jwk] }, { headers: { "cache-control": "no-store" } });
		}
		// RevenueCat is unreachable unless a test answers for it (test/api/billing.test.ts); never the real service.
		if (url.startsWith(REVENUECAT)) return new Response(null, { status: 503 });
		return real(input, init);
	});
}

const sha256Hex = async (s: string) =>
	[...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)))].map((b) => b.toString(16).padStart(2, "0")).join("");

export const ISSUER = { apple: "https://appleid.apple.com", google: "https://accounts.google.com" } as const;
export const AUDIENCE = { apple: "local.test.apple", google: "local.test.google" } as const;

/** A provider id token; every field can be broken on purpose. */
export async function providerToken(
	provider: "apple" | "google",
	nonce: string,
	o: { sub?: string; aud?: string; iss?: string; expSeconds?: number; nonceClaim?: string; wrongKey?: boolean; algNone?: boolean } = {},
): Promise<string> {
	const claims = { nonce: o.nonceClaim ?? (provider === "apple" ? await sha256Hex(nonce) : nonce) };
	const now = Math.floor(Date.now() / 1000);
	if (o.algNone) {
		const enc = (v: unknown) => btoa(JSON.stringify(v)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
		return `${enc({ alg: "none", typ: "JWT" })}.${enc({ ...claims, sub: o.sub ?? "u", aud: AUDIENCE[provider], iss: ISSUER[provider], iat: now, exp: now + 600 })}.`;
	}
	return new SignJWT(claims)
		.setProtectedHeader({ alg: "RS256", kid: "k1" })
		.setSubject(o.sub ?? "provider-user")
		.setAudience(o.aud ?? AUDIENCE[provider])
		.setIssuer(o.iss ?? ISSUER[provider])
		.setIssuedAt(now - 10)
		.setExpirationTime(now + (o.expSeconds ?? 600))
		.sign(o.wrongKey ? otherKeys.privateKey : keys.privateKey);
}

export async function nonce(ip?: string): Promise<string> {
	const res = await call("/session/nonce", { method: "POST", ip });
	return ((await res.json()) as { nonce: string }).nonce;
}

export type Session = { token: string; userId: string; expiresAt: string };

/** Signs in through the real route with the local test provider. Needs interceptOutbound() for Turnstile. */
export async function signIn(sub: string = crypto.randomUUID()): Promise<Session> {
	const n = await nonce();
	const res = await call("/session", {
		method: "POST",
		...json({ provider: "test", idToken: testIdToken(sub, n), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true }),
	});
	if (res.status !== 200) throw new Error(`sign-in failed: ${res.status} ${await res.text()}`);
	return (await res.json()) as Session;
}

export const userStub = (userId: string) => {
	const ns = env.DO_JURISDICTION ? env.USER_DO.jurisdiction(env.DO_JURISDICTION) : env.USER_DO;
	return ns.get(ns.idFromName(userId)) as DurableObjectStub<UserDO>;
};

export const inObject = <R>(userId: string, fn: (instance: UserDO, state: DurableObjectState) => R | Promise<R>) =>
	runInDurableObject(userStub(userId), fn);

export const uuid = () => crypto.randomUUID();
