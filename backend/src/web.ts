/** Shared pieces of the server-rendered pages: security headers and HTML escaping. */

/** Two years, as the HSTS preload list asks; subdomains included because every host of the zone is HTTPS-only. */
export const HSTS = "max-age=63072000; includeSubDomains";

/** Headers every page sends (plan 4.8 "Edge"; AC29). */
export function pageHeaders(csp: string): Record<string, string> {
	return {
		"content-type": "text/html; charset=utf-8",
		"content-security-policy": csp,
		"strict-transport-security": HSTS,
		"x-content-type-options": "nosniff",
		"referrer-policy": "no-referrer",
		"x-frame-options": "DENY",
		"cache-control": "no-store",
	};
}

const ESCAPES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
export const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (ch) => ESCAPES[ch]);

/** The comma-separated audiences of a provider as a list. */
export const audiences = (value: string) =>
	value
		.split(",")
		.map((a) => a.trim())
		.filter((a) => a.length > 0);
