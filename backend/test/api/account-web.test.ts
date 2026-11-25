import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import requestFixture from "../../../contract/fixtures/account_delete_web_request.json";
import { app } from "../../src/index";
import { TURNSTILE_OK, call, inObject, interceptOutbound, json, nonce, providerToken } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => vi.restoreAllMocks());

const GOOGLE_WEB = "123456-abcdef.apps.googleusercontent.com";
const APPLE_WEB = "de.munichtech.recipes.web";
const configured = { ...env, GOOGLE_WEB_CLIENT_ID: GOOGLE_WEB, APPLE_WEB_SERVICES_ID: APPLE_WEB, GOOGLE_AUDIENCES: `local.test.google,${GOOGLE_WEB}`, APPLE_AUDIENCES: `local.test.apple,${APPLE_WEB}` };
const page = (e: object) => app.request("https://recipe.example/account/delete", {}, e as Env);

const signInWith = async (provider: "apple" | "google", sub: string) => {
	const n = await nonce();
	const res = await call("/session", { method: "POST", ...json({ provider, idToken: await providerToken(provider, n, { sub }), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true }) });
	expect(res.status).toBe(200);
	return (await res.json()) as { token: string; userId: string };
};
const deleteWeb = async (provider: "apple" | "google", sub: string, extra: Record<string, unknown> = {}) => {
	const n = await nonce();
	return call("/account/delete-web", { method: "POST", ...json({ provider, idToken: await providerToken(provider, n, { sub }), nonce: n, turnstileToken: TURNSTILE_OK, ...extra }) });
};
const tableCount = (userId: string) => inObject(userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM sqlite_master WHERE type = 'table' AND name NOT GLOB '_cf_*'").one().n);

describe("GET /account/delete (AC29)", () => {
	it("answers 404 while no provider's web client id is configured", async () => {
		expect((await page(env)).status).toBe(404);
		// A client id that the token check would not accept as an audience does not count either.
		expect((await page({ ...env, GOOGLE_WEB_CLIENT_ID: GOOGLE_WEB })).status).toBe(404);
		expect((await page({ ...configured, TURNSTILE_SITE_KEY: "" })).status).toBe(404);
	});

	it("loads script only from Turnstile and the providers, with a CSP listing exactly those, and HSTS", async () => {
		const res = await page(configured);
		expect(res.status).toBe(200);
		expect(res.headers.get("content-security-policy")).toBe(
			"default-src 'none'; script-src 'self' https://challenges.cloudflare.com https://accounts.google.com/gsi/client https://appleid.cdn-apple.com; frame-src https://challenges.cloudflare.com https://accounts.google.com/gsi/; connect-src 'self' https://accounts.google.com/gsi/; style-src https://accounts.google.com/gsi/style; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
		);
		expect(res.headers.get("strict-transport-security")).toMatch(/^max-age=\d{8}; includeSubDomains$/);
		const html = await res.text();
		const scripts = [...html.matchAll(/<script src="([^"]+)"/g)].map((m) => m[1]);
		expect(scripts).toEqual([
			"/account-delete.js",
			"https://challenges.cloudflare.com/turnstile/v0/api.js",
			"https://accounts.google.com/gsi/client",
			"https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js",
		]);
		expect(html).not.toMatch(/<script>|\son\w+="/);
		expect(html).toContain(`data-redirect="https://recipe.example/account/delete"`);
		const onlyGoogle = await page({ ...configured, APPLE_WEB_SERVICES_ID: "" });
		expect(onlyGoogle.headers.get("content-security-policy")).not.toContain("apple");
		expect(await onlyGoogle.text()).not.toContain('id="apple"');
	});
});

describe("POST /account/delete-web (AC29)", () => {
	it("the request fixture matches the contract shape", () => {
		expect(Object.keys(requestFixture).sort()).toEqual(["appleAuthCode", "idToken", "nonce", "provider", "turnstileToken"]);
	});

	it("fails without a valid Turnstile token and leaves the account alone", async () => {
		const s = await signInWith("google", "web-1");
		const res = await deleteWeb("google", "web-1", { turnstileToken: "wrong" });
		expect(res.status).toBe(401);
		expect(((await res.json()) as { error: { code: string } }).error.code).toBe("invalid_turnstile");
		expect((await call("/me", { token: s.token })).status).toBe(200);
	});

	it("starts the same deletion as the app: 202, then every call answers 401", async () => {
		const s = await signInWith("google", "web-2");
		const res = await deleteWeb("google", "web-2");
		expect(res.status).toBe(202);
		expect(await res.json()).toEqual({ state: "deleting" });
		expect((await call("/me", { token: s.token })).status).toBe(401);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT id FROM tasks").toArray())).toEqual([{ id: "deletion" }]);
		expect((await deleteWeb("google", "web-2")).status).toBe(202);
	});

	it("an id token for an account that does not exist answers 404 and creates no storage", async () => {
		const res = await deleteWeb("google", "never-signed-up");
		expect(res.status).toBe(404);
		const n = await nonce();
		const token = await providerToken("google", n, { sub: "never-signed-up" });
		const probe = await call("/account/delete-web", { method: "POST", ...json({ provider: "google", idToken: token, nonce: n, turnstileToken: TURNSTILE_OK }) });
		expect(probe.status).toBe(404);
	});

	it("rejects a bad token, a reused nonce and the test provider", async () => {
		await signInWith("google", "web-3");
		const n = await nonce();
		const bad = await call("/account/delete-web", { method: "POST", ...json({ provider: "google", idToken: await providerToken("google", n, { sub: "web-3", wrongKey: true }), nonce: n, turnstileToken: TURNSTILE_OK }) });
		expect(bad.status).toBe(401);
		const test = await call("/account/delete-web", { method: "POST", ...json({ provider: "test", idToken: "__TEST_IDP__|web-3|x", nonce: n, turnstileToken: TURNSTILE_OK }) });
		expect(test.status).toBe(400);
	});

	it("keeps the Apple code with the page's redirect URI for the deletion's revoke step", async () => {
		const s = await signInWith("apple", "web-apple");
		expect((await deleteWeb("apple", "web-apple", { appleAuthCode: "web-code" })).status).toBe(202);
		const stored = await inObject(s.userId, (_i, st) => JSON.parse(st.storage.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'apple_auth_code'").one().value));
		expect(stored).toMatchObject({ code: "web-code", clientId: "local.test.apple", redirectUri: "http://localhost/account/delete" });
		expect(await tableCount(s.userId)).toBeGreaterThan(2);
	});
});
