import { exports } from "cloudflare:workers";
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import fixture from "../../contract/fixtures/health.json";
import { app } from "../src/index";
import wranglerConfig from "../wrangler.jsonc?raw";
import bridgeFixture from "../../contract/fixtures/turnstile_bridge.json";
import bridgeScript from "../public/turnstile.js?raw";

describe("production app", () => {
	it("GET /v1/health returns the contract fixture", async () => {
		const res = await app.request("/v1/health", {}, env);
		expect(res.status).toBe(200);
		expect(res.headers.get("content-type")).toMatch(/^application\/json/);
		expect(await res.json()).toEqual(fixture);
	});

	it("refuses the test provider with 400 invalid_body", async () => {
		const body = JSON.stringify({ provider: "test", idToken: "__TEST_IDP__|x|n", nonce: "n", turnstileToken: "t", ageConfirmed: true });
		const res = await app.request("/v1/session", { method: "POST", body, headers: { "cf-connecting-ip": "203.0.113.9", "content-length": String(body.length) } }, env);
		expect(res.status).toBe(400);
		expect(await res.json()).toEqual({ error: { code: "invalid_body", message: "session refused: invalid_body" } });
	});
});

describe("local entry", () => {
	it("serves the production routes", async () => {
		const res = await exports.default.fetch("http://localhost/v1/health");
		expect(await res.json()).toEqual(fixture);
	});

	it("unknown API routes return 404 in the error format", async () => {
		const res = await exports.default.fetch("http://localhost/v1/unknown");
		expect(res.status).toBe(404);
		expect(await res.json()).toEqual({ error: { code: "not_found", message: "no such route" } });
	});
});

describe("static assets", () => {
	it("serves public/index.html through the ASSETS binding", async () => {
		const res = await env.ASSETS.fetch("https://example.com/");
		expect(res.status).toBe(200);
		expect(await res.text()).toContain("<title>Recipes</title>");
	});
});

describe("Turnstile page", () => {
	it("renders the widget with the environment's site key, a strict CSP and the bridge script", async () => {
		const res = await exports.default.fetch("http://localhost/turnstile.html");
		expect(res.status).toBe(200);
		expect(res.headers.get("content-security-policy")).toBe(
			"default-src 'none'; script-src 'self' https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
		);
		const html = await res.text();
		expect(html).toContain('data-sitekey="1x00000000000000000000AA"');
		expect(html).toContain('data-callback="onTurnstileToken"');
		const js = await (await env.ASSETS.fetch("https://example.com/turnstile.js")).text();
		expect(js).toContain("window.RecipesBridge.onToken(token)");
		expect(js).toContain("window.webkit.messageHandlers.turnstile.postMessage(token)");
	});

	it("503 when the site key is not configured", async () => {
		const res = await app.request("/turnstile.html", {}, { ...env, TURNSTILE_SITE_KEY: "" });
		expect(res.status).toBe(503);
	});
});

describe("Turnstile test keys", () => {
	// Comment lines are dropped before parsing; no value in the config contains a line-leading "//".
	const config = JSON.parse(
		wranglerConfig
			.split("\n")
			.filter((l: string) => !l.trim().startsWith("//"))
			.join("\n"),
	) as { vars: Record<string, string>; env: Record<string, { vars: Record<string, string> }> };
	const TEST_KEYS = /1x0{20,}AA/;

	it("only env.local carries the development pepper and server key; the others still require real secrets", () => {
		expect(config.env.local.vars.USER_ID_PEPPER).toMatch(/^dev-only-/);
		expect(config.env.local.vars.SERVER_KEY).toMatch(/^dev-only-/);
		const required = (block: unknown) => (block as { secrets: { required: string[] } }).secrets.required;
		for (const name of ["production", "staging"]) {
			expect(JSON.stringify(config.env[name])).not.toContain("dev-only-");
			expect(required(config.env[name])).toEqual(["USER_ID_PEPPER", "SERVER_KEY", "TURNSTILE_SECRET", "REVENUECAT_API_KEY", "REVENUECAT_WEBHOOK_SECRET", "REVENUECAT_V2_API_KEY", "APPLE_P8"]);
		}
		expect(JSON.stringify(config.vars)).not.toContain("dev-only-");
		expect(required(config)).toEqual(["USER_ID_PEPPER", "SERVER_KEY", "TURNSTILE_SECRET", "REVENUECAT_API_KEY", "REVENUECAT_WEBHOOK_SECRET", "REVENUECAT_V2_API_KEY", "APPLE_P8"]);
	});

	it("only env.local carries Cloudflare's always-pass test keys", () => {
		expect(config.env.local.vars.TURNSTILE_SITE_KEY).toBe("1x00000000000000000000AA");
		expect(config.env.local.vars.TURNSTILE_SECRET).toBe("1x0000000000000000000000000000000AA");
		for (const name of ["production", "staging"]) expect(JSON.stringify(config.env[name])).not.toMatch(TEST_KEYS);
		expect(JSON.stringify(config.vars)).not.toMatch(TEST_KEYS);
	});
});

describe("Turnstile bridge names", () => {
	it("public/turnstile.js uses exactly the names in contract/fixtures/turnstile_bridge.json", () => {
		const { object, method } = bridgeFixture.android;
		expect(bridgeScript).toContain(`if (window.${object})`);
		expect(bridgeScript).toContain(`window.${object}.${method}(token)`);
		expect(bridgeScript).toContain(`window.webkit.messageHandlers.${bridgeFixture.ios.messageHandler}.postMessage(token)`);
		const globals = [...bridgeScript.matchAll(/window\.(\w+)/g)].map((m) => m[1]);
		expect(new Set(globals)).toEqual(new Set([object, "webkit"]));
	});
});

describe("local entry host guard (security review S4)", () => {
	it("answers 404 for any host other than localhost, 127.0.0.1 and 10.0.2.2", async () => {
		for (const url of ["https://recipes-local.example.workers.dev/v1/health", "https://munich-tech.de/v1/health", "http://localhost.evil.example/v1/health"]) {
			expect([url, (await exports.default.fetch(url)).status]).toEqual([url, 404]);
		}
		for (const url of ["http://localhost:8787/v1/health", "http://127.0.0.1/v1/health", "http://10.0.2.2:8787/v1/health"]) {
			expect([url, (await exports.default.fetch(url)).status]).toEqual([url, 200]);
		}
	});
});
