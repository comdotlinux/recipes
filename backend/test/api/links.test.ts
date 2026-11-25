import { exports } from "cloudflare:workers";
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { app } from "../../src/index";

const token = "A".repeat(107);
const PRINT = Array.from({ length: 32 }, (_, i) => (i * 7).toString(16).padStart(2, "0").toUpperCase()).join(":");

describe("share link landing page (AC25)", () => {
	it("serves the static page for any token with its own CSP, no referrer and no recipe content", async () => {
		const res = await exports.default.fetch(`http://localhost/s/${token}`);
		expect(res.status).toBe(200);
		expect(res.headers.get("content-security-policy")).toBe("default-src 'none'; script-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
		expect(res.headers.get("referrer-policy")).toBe("no-referrer");
		expect(res.headers.get("x-content-type-options")).toBe("nosniff");
		const html = await res.text();
		expect(html).toContain("Someone shared a recipe with you");
		expect(html).not.toContain(token);
		// Only our own script; nothing from a third party.
		expect([...html.matchAll(/src="([^"]+)"/g)].map((m) => m[1])).toEqual(["/share-landing.js"]);
		const other = await (await exports.default.fetch("http://localhost/s/anything-else")).text();
		expect(other).toBe(html);
		const js = await (await env.ASSETS.fetch("https://example.com/share-landing.js")).text();
		expect(js).toContain("package=com.linux.recipes");
	});
});

describe(".well-known files for App Links and Universal Links", () => {
	it("404 while the provisioning values are unset or malformed", async () => {
		for (const path of ["/.well-known/assetlinks.json", "/.well-known/apple-app-site-association"]) {
			expect((await exports.default.fetch(`http://localhost${path}`)).status).toBe(404);
		}
		expect((await app.request("/.well-known/assetlinks.json", {}, { ...env, ANDROID_CERT_SHA256: "not-a-print" })).status).toBe(404);
		expect((await app.request("/.well-known/apple-app-site-association", {}, { ...env, APPLE_TEAM_ID: "short" })).status).toBe(404);
	});

	it("assetlinks.json names the app and the fingerprints from the environment", async () => {
		const res = await app.request("/.well-known/assetlinks.json", {}, { ...env, ANDROID_CERT_SHA256: `${PRINT.toLowerCase()}, ${PRINT}` });
		expect(res.status).toBe(200);
		expect(res.headers.get("content-type")).toMatch(/^application\/json/);
		expect(await res.json()).toEqual([
			{ relation: ["delegate_permission/common.handle_all_urls"], target: { namespace: "android_app", package_name: "com.linux.recipes", sha256_cert_fingerprints: [PRINT, PRINT] } },
		]);
	});

	it("apple-app-site-association covers /s/* for the team's app", async () => {
		const res = await app.request("/.well-known/apple-app-site-association", {}, { ...env, APPLE_TEAM_ID: "ABCDE12345" });
		expect(res.headers.get("content-type")).toMatch(/^application\/json/);
		expect(await res.json()).toEqual({ applinks: { details: [{ appIDs: ["ABCDE12345.com.linux.recipes"], components: [{ "/": "/s/*", comment: "share links" }] }] } });
	});
});
