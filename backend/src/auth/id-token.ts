import { createLocalJWKSet, errors, jwtVerify, type JSONWebKeySet } from "jose";
import { sha256Hex } from "./encoding";

export type Provider = "apple" | "google";

/*
 * Provider facts, checked 2026-11-24 at the providers' own documentation:
 *
 * Apple
 * - Keys: GET https://appleid.apple.com/auth/keys (RS256 keys, select by `kid`); served with `Cache-Control: no-store`.
 *   https://developer.apple.com/documentation/signinwithapplerestapi/fetch-apple's-public-key-for-verifying-token-signature
 * - `iss` is "https://appleid.apple.com", `aud` is our client_id (bundle id or Services ID), `exp` must be in the future.
 *   https://developer.apple.com/documentation/signinwithapple/verifying-a-user
 *   https://developer.apple.com/documentation/signinwithapplejs/authorizationi/id_token
 * - `nonce` echoes the value the app passed in the authorization request
 *   (https://developer.apple.com/documentation/authenticationservices/asauthorizationopenidrequest/nonce).
 *   Our app passes SHA-256 (lowercase hex) of the server nonce, so the claim is that hash (docs/api.md, Session).
 *
 * Google
 * - Keys: https://www.googleapis.com/oauth2/v3/certs; rotate, so cache per the `Cache-Control` header.
 * - `iss` is "accounts.google.com" or "https://accounts.google.com"; `aud` is one of our client ids; `exp` in the future.
 *   https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
 * - `nonce` is "the value of the nonce supplied by your app in the authentication request"; signing alg RS256
 *   (`id_token_signing_alg_values_supported`). https://developers.google.com/identity/openid-connect/openid-connect
 */
const PROVIDERS = {
	apple: {
		jwksUrl: "https://appleid.apple.com/auth/keys",
		issuers: ["https://appleid.apple.com"],
		audiences: (env: Env) => env.APPLE_AUDIENCES,
		nonceClaim: (nonce: string) => sha256Hex(nonce),
	},
	google: {
		jwksUrl: "https://www.googleapis.com/oauth2/v3/certs",
		issuers: ["https://accounts.google.com", "accounts.google.com"],
		audiences: (env: Env) => env.GOOGLE_AUDIENCES,
		nonceClaim: async (nonce: string) => nonce,
	},
} as const;

const ALGORITHMS = ["RS256"];

const HOUR_MS = 60 * 60 * 1000;
const MIN_CACHE_MS = HOUR_MS;
const MAX_CACHE_MS = 24 * HOUR_MS;
const REFRESH_COOLDOWN_MS = 30 * 1000;

/*
 * Key cache. It is module state, so it lives per Worker isolate: each isolate fetches the keys on its own,
 * which is fine because a fetch is cheap and every isolate applies the same rules.
 * - A fetched set is kept for the provider's max-age, but at least 1 hour (Apple sends `no-store`) and at most 24 hours.
 * - A token whose `kid` is not in the set triggers one refetch, at most once per 30 seconds per provider,
 *   so made-up key ids cannot make the Worker hammer the provider.
 * - When a fetch fails, a set fetched less than 24 hours ago is still used.
 * Trade-off: a key the provider withdraws stays trusted for up to the cache time (at most 24 hours).
 */
type CacheEntry = { jwks: JSONWebKeySet; fetchedAt: number; lifetime: number };
const jwksCache = new Map<string, CacheEntry>();
const lastFetchAttempt = new Map<string, number>();

function cacheLifetimeMs(response: Response): number {
	const cacheControl = response.headers.get("cache-control")?.toLowerCase() ?? "";
	const maxAge = /(^|,)\s*max-age=(\d+)/.exec(cacheControl);
	const age = Number(response.headers.get("age") ?? 0) || 0;
	const providerMs = maxAge && !/(^|,)\s*(no-store|no-cache)\b/.test(cacheControl) ? (Number(maxAge[2]) - age) * 1000 : 0;
	return Math.min(Math.max(providerMs, MIN_CACHE_MS), MAX_CACHE_MS);
}

async function fetchKeys(url: string): Promise<{ jwks: JSONWebKeySet; lifetime: number } | null> {
	try {
		const response = await fetch(url);
		if (!response.ok) return null;
		const jwks = (await response.json()) as JSONWebKeySet;
		return Array.isArray(jwks?.keys) ? { jwks, lifetime: cacheLifetimeMs(response) } : null;
	} catch {
		return null;
	}
}

/** The provider's key set; `refresh` asks for a new set because a token named an unknown key id. */
async function providerKeys(url: string, now: number, refresh: boolean): Promise<JSONWebKeySet | null> {
	const cached = jwksCache.get(url);
	if (cached && !refresh && now < cached.fetchedAt + cached.lifetime) return cached.jwks;
	if (refresh && now - (lastFetchAttempt.get(url) ?? -Infinity) < REFRESH_COOLDOWN_MS) return null;

	lastFetchAttempt.set(url, now);
	const fetched = await fetchKeys(url);
	if (fetched) {
		jwksCache.set(url, { ...fetched, fetchedAt: now });
		return fetched.jwks;
	}
	return cached && now - cached.fetchedAt < MAX_CACHE_MS ? cached.jwks : null;
}

export async function verifyIdToken(
	provider: Provider,
	idToken: string,
	nonce: string,
	env: Env,
	now: number,
): Promise<{ sub: string } | null | "unavailable"> {
	const config = PROVIDERS[provider];
	const audiences = config
		.audiences(env)
		.split(",")
		.map((a) => a.trim())
		.filter((a) => a.length > 0);
	if (audiences.length === 0) return null;

	const verify = (jwks: JSONWebKeySet) =>
		jwtVerify(idToken, createLocalJWKSet(jwks), {
			algorithms: ALGORITHMS,
			issuer: [...config.issuers],
			audience: audiences,
			currentDate: new Date(now),
			requiredClaims: ["sub", "exp", "nonce"],
		});

	const jwks = await providerKeys(config.jwksUrl, now, false);
	// No key set at all (endpoint down, nothing cached): not the user's fault, so the route answers 503, not 401.
	if (!jwks) return "unavailable";

	try {
		let result;
		try {
			result = await verify(jwks);
		} catch (error) {
			if (!(error instanceof errors.JWKSNoMatchingKey)) throw error;
			const refreshed = await providerKeys(config.jwksUrl, now, true);
			if (!refreshed) return null;
			result = await verify(refreshed);
		}
		const { payload } = result;
		if (typeof payload.sub !== "string" || payload.sub.length === 0) return null;
		if (payload.nonce !== (await config.nonceClaim(nonce))) return null;
		return { sub: payload.sub };
	} catch {
		// Any jose error (signature, alg, kid, iss, aud, exp, malformed token) means "not valid".
		return null;
	}
}
