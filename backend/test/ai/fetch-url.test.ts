import { afterEach, describe, expect, it, vi } from "vitest";
import { MAX_PAGE_BYTES, MAX_TEXT_CHARS, fetchPage, pageText, validateUrl } from "../../src/ai/fetch-url";

afterEach(() => vi.restoreAllMocks());

describe("validateUrl: SSRF table (AC19)", () => {
	const rejected: [string, string][] = [
		["ftp://example.org/x", "scheme"],
		["file:///etc/passwd", "scheme"],
		["javascript:alert(1)", "scheme"],
		["data:text/html,hi", "scheme"],
		["gopher://example.org/", "scheme"],
		["https://user:pass@example.org/", "credentials"],
		["https://user@example.org/", "credentials"],
		["https://example.org:8080/", "port"],
		["http://example.org:22/", "port"],
		["http://example.org:0/", "port"],
		["http://127.0.0.1/", "ip_literal"],
		["http://10.0.0.1/", "ip_literal"],
		["http://169.254.169.254/latest/meta-data", "ip_literal"],
		["http://2130706433/", "ip_literal"], // decimal 127.0.0.1
		["http://0177.0.0.1/", "ip_literal"], // octal
		["http://0x7f.0.0.1/", "ip_literal"], // hex
		["http://0x7f000001/", "ip_literal"], // hex single number
		["http://127.1/", "ip_literal"], // short form
		["http://[::1]/", "ip_literal"],
		["http://[::ffff:127.0.0.1]/", "ip_literal"], // IPv4-mapped
		["http://[fd00::1]/", "ip_literal"],
		["http://localhost/", "hostname"],
		["http://intranet/", "hostname"],
		["http://localhost./", "hostname"],
		["http://foo.localhost/", "blocked_host"],
		["https://munich-tech.de/v1/health", "blocked_host"],
		["https://api.munich-tech.de./", "blocked_host"],
		["https://recipes-production.someone.workers.dev/", "blocked_host"],
		["http://printer.local/", "blocked_host"],
		["http://metadata.google.internal/", "blocked_host"],
		["http://1.0.0.127.in-addr.arpa/", "blocked_host"],
		["http://exa_mple.org/", "hostname"],
		[`https://example.org/${"a".repeat(2048)}`, "too_long"],
		["not a url", "unparsable"],
	];
	for (const [url, reason] of rejected) {
		it(`rejects ${url.slice(0, 60)} (${reason})`, () => {
			expect(validateUrl(url)).toEqual({ ok: false, reason });
		});
	}

	const accepted = [
		"https://www.chefkoch.de/rezepte/123/kartoffelsalat.html",
		"http://example.org/",
		"https://example.org:443/x?y=1#z",
		"http://example.org:80/",
		"https://example.org./", // trailing dot is the same host
		"https://bücher.example/rezept", // IDN becomes punycode
	];
	for (const url of accepted) {
		it(`accepts ${url}`, () => {
			const r = validateUrl(url);
			expect(r.ok).toBe(true);
		});
	}

	it("turns an IDN host into punycode before the checks", () => {
		const r = validateUrl("https://bücher.example/");
		expect(r.ok && r.url.hostname).toBe("xn--bcher-kva.example");
	});
});

/** Routes the global fetch: resolver answers and pages by URL. */
function web(pages: Record<string, () => Response>, blocked: string[] = []) {
	const seen: string[] = [];
	vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
		const url = new Request(input, init).url;
		if (url.startsWith("https://security.cloudflare-dns.com/dns-query")) {
			const name = new URL(url).searchParams.get("name")!;
			return Response.json({ Status: 0, Answer: [{ data: blocked.includes(name) ? "0.0.0.0" : "93.184.215.14" }] });
		}
		seen.push(url);
		expect(init?.redirect).toBe("manual");
		const page = pages[url];
		if (!page) throw new Error(`unexpected fetch ${url}`);
		return page();
	});
	return seen;
}
const html = (body: string, headers: Record<string, string> = {}) => () => new Response(body, { headers: { "content-type": "text/html; charset=utf-8", ...headers } });
const redirect = (to: string) => () => new Response(null, { status: 302, headers: { location: to } });

describe("fetchPage", () => {
	it("returns the page text", async () => {
		web({ "https://example.org/r": html("<html><body><h1>Kuchen</h1><p>Mehl &amp; Zucker</p></body></html>") });
		const r = await fetchPage("https://example.org/r");
		expect(r).toEqual({ ok: true, text: "Kuchen\nMehl & Zucker", finalUrl: "https://example.org/r" });
	});

	it("follows up to 3 redirects, re-validating every hop", async () => {
		const seen = web({
			"https://a.example/1": redirect("https://b.example/2"),
			"https://b.example/2": redirect("/3"),
			"https://b.example/3": redirect("https://c.example/4"),
			"https://c.example/4": html("<p>ok</p>"),
		});
		expect(await fetchPage("https://a.example/1")).toMatchObject({ ok: true, text: "ok", finalUrl: "https://c.example/4" });
		expect(seen).toHaveLength(4);
	});

	const hostile: [string, Record<string, () => Response>, string[], string][] = [
		["a fourth redirect", { "https://a.example/1": redirect("https://a.example/2"), "https://a.example/2": redirect("https://a.example/3"), "https://a.example/3": redirect("https://a.example/4"), "https://a.example/4": redirect("https://a.example/5") }, [], "too_many_redirects"],
		["a redirect to a private IP", { "https://a.example/1": redirect("http://169.254.169.254/") }, [], "url_ip_literal"],
		["a redirect to our own host", { "https://a.example/1": redirect("https://munich-tech.de/v1/me") }, [], "url_blocked_host"],
		["a redirect to another port", { "https://a.example/1": redirect("https://a.example:8443/") }, [], "url_port"],
		["a redirect to file:", { "https://a.example/1": redirect("file:///etc/passwd") }, [], "url_scheme"],
		["a redirect without Location", { "https://a.example/1": () => new Response(null, { status: 301 }) }, [], "redirect_without_location"],
		["a non-HTML type", { "https://a.example/1": () => new Response("{}", { headers: { "content-type": "application/json" } }) }, [], "not_html"],
		["an image", { "https://a.example/1": () => new Response("x", { headers: { "content-type": "image/svg+xml" } }) }, [], "not_html"],
		["a declared length above 2 MB", { "https://a.example/1": html("x", { "content-length": String(MAX_PAGE_BYTES + 1) }) }, [], "too_large"],
		["a body above 2 MB without a length", { "https://a.example/1": html("x".repeat(MAX_PAGE_BYTES + 1)) }, [], "too_large"],
		["a host flagged by the malware resolver", { "https://a.example/1": html("<p>x</p>") }, ["a.example"], "malware_resolver"],
		["a 404", { "https://a.example/1": () => new Response("no", { status: 404, headers: { "content-type": "text/html" } }) }, [], "status_404"],
	];
	for (const [name, pages, blocked, reason] of hostile) {
		it(`rejects ${name}`, async () => {
			web(pages, blocked);
			expect(await fetchPage("https://a.example/1")).toEqual({ ok: false, reason, transient: false });
		});
	}

	it("a body that streams past 2 MB is cut off without buffering it all", async () => {
		let sent = 0;
		const endless = new ReadableStream<Uint8Array>({
			pull(c) {
				sent += 65536;
				c.enqueue(new Uint8Array(65536));
			},
		});
		web({ "https://a.example/1": () => new Response(endless, { headers: { "content-type": "text/html" } }) });
		expect(await fetchPage("https://a.example/1")).toEqual({ ok: false, reason: "too_large", transient: false });
		expect(sent).toBeLessThan(MAX_PAGE_BYTES + 4 * 65536);
	});

	it("network errors and 5xx are transient", async () => {
		web({ "https://a.example/1": () => new Response("x", { status: 503 }) });
		expect(await fetchPage("https://a.example/1")).toEqual({ ok: false, reason: "status_503", transient: true });
		vi.restoreAllMocks();
		web({ "https://a.example/1": () => { throw new TypeError("connection refused"); } });
		expect(await fetchPage("https://a.example/1")).toEqual({ ok: false, reason: "network", transient: true });
	});

	it("one deadline covers every hop: a slow second hop ends as timeout (F4)", async () => {
		const real = globalThis.fetch;
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
			const url = new Request(input, init).url;
			if (url.startsWith("https://security.cloudflare-dns.com/")) return Response.json({ Status: 0, Answer: [] });
			await new Promise((r) => setTimeout(r, 150));
			if (url === "https://a.example/1") return new Response(null, { status: 302, headers: { location: "https://a.example/2" } });
			if (url === "https://a.example/2") return new Response("<p>x</p>", { headers: { "content-type": "text/html" } });
			return real(input, init);
		});
		expect(await fetchPage("https://a.example/1", 200)).toEqual({ ok: false, reason: "timeout", transient: true });
	});

	it("the deadline also stops a body that trickles in slowly (F4)", async () => {
		const slow = new ReadableStream<Uint8Array>({
			async pull(c) {
				await new Promise((r) => setTimeout(r, 50));
				c.enqueue(new TextEncoder().encode("<p>x</p>"));
			},
		});
		web({ "https://a.example/1": () => new Response(slow, { headers: { "content-type": "text/html" } }) });
		const started = Date.now();
		expect(await fetchPage("https://a.example/1", 300)).toEqual({ ok: false, reason: "timeout", transient: true });
		expect(Date.now() - started).toBeLessThan(1000);
	});

	it("queries A and AAAA; a block on either counts (F7)", async () => {
		const asked: string[] = [];
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
			const url = new URL(new Request(input, init).url);
			asked.push(url.searchParams.get("type")!);
			return Response.json({ Status: 0, Answer: [{ data: url.searchParams.get("type") === "AAAA" ? "::" : "1.2.3.4" }] });
		});
		expect(await fetchPage("https://a.example/1")).toEqual({ ok: false, reason: "malware_resolver", transient: false });
		expect(asked).toEqual(["A", "AAAA"]);
	});

	it("a failing resolver counts as blocked", async () => {
		vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("down"));
		expect(await fetchPage("https://a.example/1")).toEqual({ ok: false, reason: "malware_resolver", transient: false });
	});
});

describe("pageText", () => {
	it("is linear: 2 MB of '<' and 2 MB of '<script ' take under a second each (F1)", async () => {
		for (const unit of ["<", "<script ", "<a <b <!-- ", '<script type="application/ld+json">{'] ) {
			const page = unit.repeat(Math.ceil(MAX_PAGE_BYTES / unit.length));
			const started = Date.now();
			await pageText(page);
			expect([unit, Date.now() - started < 1000]).toEqual([unit, true]);
		}
	});

	it("positions stay right after characters whose lowercase is longer (N1)", async () => {
		expect(await pageText(`${"İ".repeat(10)}<script>SECRET</script><p>ok</p>`)).toBe(`${"İ".repeat(10)}ok`);
	});

	it("a '<' that no '>' follows is kept as text with the rest of the page (N2)", async () => {
		expect(await pageText("<p>Mix</p><p>if a < b then stir</p>")).toBe("Mix\nif a < b then stir");
		expect(await pageText("<p>Mix</p>if a <b then stir")).toBe("Mix\nif a <b then stir");
	});

	it("keeps text outside script, style, noscript and template, and breaks lines at blocks", async () => {
		expect(await pageText("<h1>Titel</h1><noscript>no</noscript><template>t</template><p>a<br>b</p><div>c</div>")).toBe("Titel\na\nb\nc");
	});

	it("uses schema.org Recipe JSON-LD without reviews, ratings and comments", async () => {
		const ld = { "@context": "https://schema.org", "@graph": [{ "@type": "WebPage" }, { "@type": ["Recipe"], name: "Kuchen", review: [{ x: 1 }], aggregateRating: {}, comment: [] }] };
		const page = `<script type="application/ld+json">${JSON.stringify(ld)}</script><p>ignored</p>`;
		expect(JSON.parse(await pageText(page))).toEqual({ "@type": ["Recipe"], name: "Kuchen" });
	});

	it("falls back to readable text when the JSON-LD is broken or not a recipe", async () => {
		expect(await pageText(`<script type="application/ld+json">{broken</script><p>Text</p>`)).toBe("Text");
		expect(await pageText(`<script type="application/ld+json">{"@type":"Article"}</script><p>Text</p>`)).toBe("Text");
	});

	it("drops scripts and styles, decodes entities and caps the length", async () => {
		expect(await pageText("<style>p{}</style><script>alert(1)</script><p>a&#228;&#xE4;&nbsp;&unknown;</p>")).toBe("aää &unknown;");
		expect(await pageText(`<p>${"x".repeat(MAX_TEXT_CHARS + 50)}</p>`)).toHaveLength(MAX_TEXT_CHARS);
		expect(await pageText("<p>&#99999999;</p>")).toBe("");
	});
});
