/**
 * Cloudflare Access check for the admin host (plan 4.8 "Admin", AC41). Access already guards the host at the edge;
 * the Worker verifies the `Cf-Access-Jwt-Assertion` header on every request as well:
 * https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/authorization-cookie/validating-json/
 * (modified 2026-11-17): RS256 keys at https://<team>.cloudflareaccess.com/cdn-cgi/access/certs, `iss` is
 * https://<team>.cloudflareaccess.com, `aud` contains the application's AUD tag, the user is the `email` claim.
 * The keys are cached like the sign-in providers' keys (auth/id-token.ts providerKeys).
 */
import { createLocalJWKSet, errors, jwtVerify, type JSONWebKeySet } from "jose";
import { providerKeys } from "../auth/id-token";
import { audiences } from "../web";

export type AccessEnv = Pick<Env, "ACCESS_TEAM_DOMAIN" | "ACCESS_AUD" | "ADMIN_EMAILS">;

const TEAM_DOMAIN = /^[a-z0-9-]+\.cloudflareaccess\.com$/;

/** The operator's email when the JWT is valid and the email is on the allow-list; null otherwise (fail closed). */
export async function verifyAccess(token: string | undefined, env: AccessEnv, now: number): Promise<string | null> {
	if (!token || !TEAM_DOMAIN.test(env.ACCESS_TEAM_DOMAIN) || !env.ACCESS_AUD) return null;
	const allowed = audiences(env.ADMIN_EMAILS.toLowerCase());
	if (allowed.length === 0) return null;
	const issuer = `https://${env.ACCESS_TEAM_DOMAIN}`;
	const url = `${issuer}/cdn-cgi/access/certs`;
	const verify = (jwks: JSONWebKeySet) =>
		jwtVerify(token, createLocalJWKSet(jwks), { algorithms: ["RS256"], issuer, audience: env.ACCESS_AUD, currentDate: new Date(now), requiredClaims: ["exp", "email"] });
	try {
		const jwks = await providerKeys(url, now, false);
		if (!jwks) return null;
		let result;
		try {
			result = await verify(jwks);
		} catch (e) {
			if (!(e instanceof errors.JWKSNoMatchingKey)) throw e;
			const refreshed = await providerKeys(url, now, true);
			if (!refreshed) return null;
			result = await verify(refreshed);
		}
		const email = result.payload.email;
		return typeof email === "string" && allowed.includes(email.toLowerCase()) ? email.toLowerCase() : null;
	} catch {
		return null;
	}
}
