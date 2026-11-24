import { exports } from "cloudflare:workers";
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import fixture from "../../contract/fixtures/health.json";
import { app } from "../src/index";

describe("production app", () => {
	it("GET /v1/health returns the contract fixture", async () => {
		const res = await app.request("/v1/health", {}, env);
		expect(res.status).toBe(200);
		expect(res.headers.get("content-type")).toMatch(/^application\/json/);
		expect(await res.json()).toEqual(fixture);
	});

	it("has no test-only route", async () => {
		const res = await app.request("/v1/test/idp", {}, env);
		expect(res.status).toBe(404);
	});
});

describe("local entry", () => {
	it("adds the test identity provider route", async () => {
		const res = await exports.default.fetch("https://example.com/v1/test/idp");
		expect(await res.json()).toEqual({ issuer: "__TEST_IDP__" });
	});

	it("still serves the production routes", async () => {
		const res = await exports.default.fetch("https://example.com/v1/health");
		expect(await res.json()).toEqual(fixture);
	});

	it("unknown API routes return 404", async () => {
		const res = await exports.default.fetch("https://example.com/v1/unknown");
		expect(res.status).toBe(404);
	});
});

describe("static assets", () => {
	it("serves public/index.html through the ASSETS binding", async () => {
		const res = await env.ASSETS.fetch("https://example.com/");
		expect(res.status).toBe(200);
		expect(await res.text()).toContain("<title>Recipe Box</title>");
	});
});
