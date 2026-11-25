import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import { PDF_CSP, escapeHtml, pdfHtml } from "../../src/pdf";
import { RecipeInput } from "../../src/recipes";
import { type Session, call, inObject, interceptOutbound, signIn, uuid } from "./helpers";

const PDF_BYTES = new TextEncoder().encode("%PDF-1.7 test");
let renders: { action: string; options: BrowserRunPDFOptions & { html?: string } }[] = [];
/** Intercepts the Browser Run binding: records each call and answers with `answer`. */
function fakeBrowser(answer: () => Response = () => new Response(PDF_BYTES, { headers: { "content-type": "application/pdf" } })) {
	renders = [];
	(env as unknown as { BROWSER?: unknown }).BROWSER = {
		quickAction: async (action: string, options: BrowserRunPDFOptions) => {
			renders.push({ action, options });
			return answer();
		},
	};
}
beforeEach(() => {
	interceptOutbound();
	fakeBrowser();
});
afterEach(() => {
	vi.restoreAllMocks();
	(env as unknown as { BROWSER?: unknown }).BROWSER = undefined;
});

const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;
const pdf = (s: Session, id: string) => call(`/recipes/${id}/pdf`, { token: s.token });
const HOSTILE = {
	title: "<script>alert(1)</script>",
	description: '<img src=http://evil.example/x.png onerror="alert(2)">',
	steps: ["</li></ol><script>fetch('http://evil.example')</script>", "Tom & Jerry's \"test\""],
	ingredients: [{ quantity: "1", unit: "<b>", item: "<iframe src=http://evil.example>", group: "<style>" }],
	tags: ["<a href=http://evil.example>x</a>"],
};
const create = async (s: Session, body: object = {}) => {
	const id = uuid();
	const res = await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, ...body, id }), headers: { "if-none-match": "*" } });
	expect(res.status).toBe(200);
	return id;
};

describe("PDF (AC26)", () => {
	it("renders the user's recipe with JavaScript off, no cache and network requests refused; answers a PDF", async () => {
		const s = await signIn();
		const id = await create(s);
		const res = await pdf(s, id);
		expect(res.status).toBe(200);
		expect(res.headers.get("content-type")).toBe("application/pdf");
		expect(res.headers.get("cache-control")).toBe("private, no-store");
		expect(new Uint8Array(await res.arrayBuffer())).toEqual(PDF_BYTES);
		expect(renders).toHaveLength(1);
		const [{ action, options }] = renders;
		expect(action).toBe("pdf");
		expect(options).toMatchObject({ setJavaScriptEnabled: false, cacheTTL: 0, rejectRequestPattern: ["/^(https?|wss?|ftp):/"] });
		expect(options).not.toHaveProperty("url");
		expect(options).not.toHaveProperty("addScriptTag");
		expect(options.html).toContain(`<meta http-equiv="Content-Security-Policy" content="${PDF_CSP}">`);
		expect(options.html).toContain("<h1>Kartoffelsalat</h1>");
	});

	it("<script> and <img src=http://...> in any field appear as text; nothing in the page refers to a remote resource", async () => {
		const s = await signIn();
		const id = await create(s, HOSTILE);
		await pdf(s, id);
		const html = renders[0].options.html!;
		expect(html).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
		expect(html).toContain("&lt;img src=http://evil.example/x.png onerror=&quot;alert(2)&quot;&gt;");
		expect(html).toContain("Tom &amp; Jerry&#39;s &quot;test&quot;");
		const tags = [...html.matchAll(/<\s*([a-z0-9]+)/gi)].map((m) => m[1].toLowerCase());
		expect(new Set(tags)).toEqual(new Set(["html", "head", "meta", "title", "style", "body", "h1", "p", "h2", "ul", "h3", "li", "ol"]));
		// Real tags only (escaped text starts with &lt;): none carries a link or a remote source.
		for (const tag of html.match(/<[a-z][^>]*>/gi)!) expect(tag).not.toMatch(/\b(src|href|srcset|action)\s*=/i);
	});

	it("pictures are embedded as data URIs from the user's own storage", async () => {
		const s = await signIn();
		const id = await create(s);
		const imageId = uuid();
		const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 9, 8, 7]);
		await call(`/recipes/${id}/images/${imageId}`, { method: "PUT", token: s.token, body: jpeg, headers: { "content-type": "image/jpeg" } });
		await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, id, imageIds: [imageId] }), headers: { "if-match": '"1"' } });
		await pdf(s, id);
		expect(renders[0].options.html).toContain(`<img alt="" src="data:image/jpeg;base64,${btoa(String.fromCharCode(...jpeg))}">`);
	});

	it("the twenty-first PDF of a UTC day answers 429 pdf_limit; a failed render does not count", async () => {
		const s = await signIn();
		const id = await create(s);
		fakeBrowser(() => Response.json({ success: false }, { status: 500 }));
		const failed = await pdf(s, id);
		expect([failed.status, await code(failed)]).toEqual([503, "pdf_unavailable"]);
		fakeBrowser();
		for (let i = 0; i < 20; i++) expect((await pdf(s, id)).status).toBe(200);
		const res = await pdf(s, id);
		expect([res.status, await code(res)]).toEqual([429, "pdf_limit"]);
		expect(renders).toHaveLength(20);
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("UPDATE daily_counters SET day = '2000-01-01' WHERE key = 'pdf_rendered'"));
		expect((await pdf(s, id)).status).toBe(200);
	});

	it("is free: no subscription needed; uses the global pdf rate-limit key", async () => {
		const s = await signIn();
		const id = await create(s);
		const limit = vi.spyOn(env.RL_GLOBAL, "limit").mockResolvedValue({ success: false });
		const res = await pdf(s, id);
		expect([res.status, await code(res)]).toEqual([429, "rate_limited"]);
		expect(limit).toHaveBeenCalledWith({ key: "pdf" });
		limit.mockResolvedValue({ success: true });
		expect((await pdf(s, id)).status).toBe(200);
	});

	it("404 for another user's or a deleted recipe; 400 for a bad id; 503 without the binding; a non-PDF answer is 503", async () => {
		const a = await signIn();
		const b = await signIn();
		const theirs = await create(b);
		expect(await code(await pdf(a, theirs))).toBe("not_found");
		const mine = await create(a);
		await call(`/recipes/${mine}`, { method: "DELETE", token: a.token });
		expect((await pdf(a, mine)).status).toBe(404);
		expect(await code(await pdf(a, "nope"))).toBe("invalid_id");
		expect(renders).toHaveLength(0);
		const other = await create(a);
		fakeBrowser(() => new Response("<html>", { headers: { "content-type": "text/html" } }));
		expect((await pdf(a, other)).status).toBe(503);
		fakeBrowser(() => {
			throw new Error("binding down");
		});
		expect((await pdf(a, other)).status).toBe(503);
		(env as unknown as { BROWSER?: unknown }).BROWSER = undefined;
		expect(await code(await pdf(a, other))).toBe("pdf_unavailable");
	});
});

describe("pdfHtml", () => {
	it("escapes all five HTML-significant characters", () => {
		expect(escapeHtml(`<>&"'`)).toBe("&lt;&gt;&amp;&quot;&#39;");
	});
	it("shows servings and times, group headings once per group, and leaves out empty sections", () => {
		const recipe = RecipeInput.parse({
			...inputFixture,
			description: "",
			prepMinutes: null,
			ingredients: [
				{ quantity: "1", unit: "kg", item: "Kartoffeln", group: "Salat" },
				{ quantity: "", unit: "", item: "Salz", group: "Salat" },
				{ quantity: "2", unit: "EL", item: "Essig", group: "Dressing" },
			],
			steps: [],
			tags: [],
		});
		const html = pdfHtml(recipe, []);
		expect(html).toContain('<p class="meta">4 servings · cook 25 min</p>');
		expect(html.match(/<h3>/g)).toHaveLength(2);
		expect(html).toContain("<li>Salz</li>");
		expect(html).not.toContain("<h2>Steps</h2>");
		expect(html).not.toContain("<img");
	});
});
