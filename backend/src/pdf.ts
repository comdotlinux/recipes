/**
 * GET /recipes/:id/pdf through Cloudflare Browser Run Quick Actions (docs/versions/backend.md, "Browser Run").
 * The page (src/pdf-template.ts) is built from the user's own recipe and pictures; every field is escaped, pictures are
 * data URIs, the page's CSP forbids every load except data URIs, JavaScript is off for the render, and the only requests
 * the browser may make are data: and about: URLs.
 */

export const PDF_PER_DAY = 20;
/** Failed renders per user and UTC day; they do not count against PDF_PER_DAY but still use browser time. */
export const PDF_FAILURES_PER_DAY = 40;

/**
 * Quick Action options (fields of BrowserRunPDFOptions in the generated runtime types): JavaScript off, no cache of the
 * page, an allow rule that admits only data: and about: requests (the documented regex-string form; UNVERIFIED until a
 * real render), A4 with margins.
 */
export const pdfOptions = (html: string): BrowserRunPDFOptions => ({
	html,
	setJavaScriptEnabled: false,
	allowRequestPattern: ["/^(data|about):/"],
	cacheTTL: 0,
	gotoOptions: { waitUntil: "load", timeout: 30_000 },
	pdfOptions: { format: "a4", printBackground: true, margin: { top: "15mm", right: "15mm", bottom: "15mm", left: "15mm" } },
});

/** The PDF bytes, or null when the service failed (any non-200 or a body that is not a PDF). */
export async function renderPdf(browser: Pick<BrowserRun, "quickAction">, html: string): Promise<ArrayBuffer | null> {
	try {
		const res = await browser.quickAction("pdf", pdfOptions(html));
		if (res.status !== 200 || !(res.headers.get("content-type") ?? "").startsWith("application/pdf")) return null;
		return await res.arrayBuffer();
	} catch {
		return null;
	}
}
