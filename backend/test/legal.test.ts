import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { LEGAL_CSP, LEGAL_PLACEHOLDER } from "../src/legal";

const get = (path: string) => exports.default.fetch(`http://localhost${path}`);

describe("legal pages (AC29)", () => {
	for (const page of ["privacy", "imprint", "terms"]) {
		for (const [suffix, lang] of [["", "en"], ["/de", "de"]] as const) {
			it(`/${page}${suffix} loads without any challenge, with HSTS and a strict CSP`, { timeout: 20_000 }, async () => {
				const res = await get(`/${page}${suffix}`);
				expect(res.status).toBe(200);
				expect(res.headers.get("content-security-policy")).toBe(LEGAL_CSP);
				expect(res.headers.get("strict-transport-security")).toBe("max-age=63072000; includeSubDomains");
				expect(res.headers.get("x-content-type-options")).toBe("nosniff");
				const html = await res.text();
				expect(html).toContain(`<html lang="${lang}">`);
				expect(html).not.toMatch(/<script|challenges\.cloudflare\.com|cf-turnstile/);
			});
		}
	}

	it("the terms are marked as a draft and name the suspension grounds; staging may still show the placeholder", async () => {
		const en = await (await get("/terms")).text();
		expect(en).toContain("Draft for the owner's review");
		for (const ground of ["illegal or abusive content", "quotas or of payments", "keeps billing", "Check every draft"]) expect(en).toContain(ground);
		expect(await (await get("/terms/de")).text()).toContain("Entwurf");
		expect(await (await get("/privacy")).text()).toContain(LEGAL_PLACEHOLDER);
	});

	it("static files and the share landing page also carry HSTS", async () => {
		for (const path of ["/", "/s", "/turnstile.html", "/account-delete.js"]) {
			const res = await get(path);
			expect([path, res.status, res.headers.get("strict-transport-security")]).toEqual([path, 200, "max-age=63072000; includeSubDomains"]);
		}
		expect((await get("/no-such-file")).status).toBe(404);
	});
});

describe("security headers on every answer (security review M6, finding 6)", () => {
	it("asset, page, API answer, API error, 404 and the public /admin 404 all carry HSTS and nosniff; HTML also a CSP", async () => {
		const paths = ["/", "/index.html", "/s", "/s/", "/s/index.html", "/terms", "/turnstile.html", "/account-delete.js", "/v1/health", "/v1/no-such-route", "/no-such-file", "/admin", "/admin/users"];
		for (const path of paths) {
			const res = await get(path);
			const html = (res.headers.get("content-type") ?? "").startsWith("text/html");
			const h = (n: string) => res.headers.get(n);
			expect([path, h("strict-transport-security"), h("x-content-type-options"), h("referrer-policy")]).toEqual([path, "max-age=63072000; includeSubDomains", "nosniff", "no-referrer"]);
			if (html) expect([path, h("content-security-policy")?.startsWith("default-src 'none'")]).toEqual([path, true]);
		}
	});
});
