/**
 * GET /recipes/:id/pdf through Cloudflare Browser Run Quick Actions (docs/versions/backend.md, "Browser Run").
 * The page is built here from the user's own recipe and pictures; every field is escaped, pictures are data URIs, the
 * page's CSP forbids every load except data URIs, JavaScript is off for the render, and network URLs are refused.
 */
import { base64 } from "./ai/transcribe";
import type { RecipeInput } from "./recipes";

export const PDF_PER_DAY = 20;

/** Plan 4.8: the template's own CSP. */
export const PDF_CSP = "default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'";

const ENTITIES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
export const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ENTITIES[c]);

const STYLE = `body{font-family:sans-serif;margin:0;color:#222}h1{font-size:22pt;margin:0 0 6pt}h2{font-size:13pt;margin:14pt 0 4pt}
.meta{color:#555}img{max-width:100%;max-height:9cm;margin:4pt 0}li{margin:2pt 0}p{white-space:pre-wrap}`;

/** The printable page. Plain text only: nothing from the recipe can become markup. */
export function pdfHtml(recipe: RecipeInput, pictures: { mime: string; bytes: ArrayBuffer }[]): string {
	const e = escapeHtml;
	const meta = [
		recipe.servings !== null ? `${recipe.servings} servings` : "",
		recipe.prepMinutes !== null ? `prep ${recipe.prepMinutes} min` : "",
		recipe.cookMinutes !== null ? `cook ${recipe.cookMinutes} min` : "",
	].filter((m) => m);
	let group: string | null = null;
	const ingredients = recipe.ingredients
		.map((i) => {
			const heading = i.group !== null && i.group !== group ? `</ul><h3>${e(i.group)}</h3><ul>` : "";
			group = i.group;
			return `${heading}<li>${e([i.quantity, i.unit, i.item].filter((p) => p).join(" "))}</li>`;
		})
		.join("");
	return `<!doctype html>
<html lang="${e(recipe.language)}">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${PDF_CSP}">
<title>${e(recipe.title)}</title>
<style>${STYLE}</style>
</head>
<body>
<h1>${e(recipe.title)}</h1>
${meta.length ? `<p class="meta">${e(meta.join(" · "))}</p>` : ""}
${pictures.map((p) => `<img alt="" src="data:${p.mime};base64,${base64(p.bytes)}">`).join("\n")}
${recipe.description ? `<p>${e(recipe.description)}</p>` : ""}
${recipe.ingredients.length ? `<h2>Ingredients</h2><ul>${ingredients}</ul>` : ""}
${recipe.steps.length ? `<h2>Steps</h2><ol>${recipe.steps.map((s) => `<li><p>${e(s)}</p></li>`).join("")}</ol>` : ""}
${recipe.tags.length ? `<p class="meta">${e(recipe.tags.join(", "))}</p>` : ""}
</body>
</html>
`;
}

/**
 * Quick Action options (fields of BrowserRunPDFOptions in the generated runtime types): JavaScript off, no cache of the
 * page, every http(s) or websocket request refused (the documented regex-string form; UNVERIFIED until a real render),
 * A4 with margins.
 */
export const pdfOptions = (html: string): BrowserRunPDFOptions => ({
	html,
	setJavaScriptEnabled: false,
	rejectRequestPattern: ["/^(https?|wss?|ftp):/"],
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
