/**
 * Legal pages on the app host (plan 4.2, AC29): privacy policy, imprint and terms of use, in English and German. The
 * bodies are the owner's files under backend/legal/ (see its README); this module only frames them. No script, a strict
 * CSP and HSTS; no Turnstile or other challenge, so they load for anyone, including store reviewers.
 */
import imprintDe from "../legal/imprint.de.html";
import imprintEn from "../legal/imprint.en.html";
import privacyDe from "../legal/privacy.de.html";
import privacyEn from "../legal/privacy.en.html";
import termsDe from "../legal/terms.de.html";
import termsEn from "../legal/terms.en.html";

export const LEGAL_CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
/** Present in every owner-supplied file until the owner replaces it; scripts/check-legal.ts blocks production while it is. */
export const LEGAL_PLACEHOLDER = "OWNER-LEGAL-PLACEHOLDER";

type Lang = "en" | "de";
const PAGES = {
	privacy: { en: ["Privacy policy", privacyEn], de: ["Datenschutzerklärung", privacyDe] },
	imprint: { en: ["Imprint", imprintEn], de: ["Impressum", imprintDe] },
	terms: { en: ["Terms of use", termsEn], de: ["Nutzungsbedingungen", termsDe] },
} as const satisfies Record<string, Record<Lang, readonly [string, string]>>;
export type LegalPage = keyof typeof PAGES;
export const LEGAL_PAGES = Object.keys(PAGES) as LegalPage[];

const STYLE = "body{font:16px/1.5 system-ui,sans-serif;max-width:46rem;margin:16px auto;padding:0 16px;color:#1a1a1a;background:#fff}a{color:#0b4f8a}nav a{margin-right:12px}";

export function legalHtml(page: LegalPage, lang: Lang): string {
	const [title, body] = PAGES[page][lang];
	const nav = LEGAL_PAGES.map((p) => `<a href="/${p}${lang === "de" ? "/de" : ""}">${PAGES[p][lang][0]}</a>`).join("");
	const other = lang === "en" ? `<a href="/${page}/de" hreflang="de">Deutsch</a>` : `<a href="/${page}" hreflang="en">English</a>`;
	return `<!doctype html><html lang="${lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Recipes: ${title}</title><style>${STYLE}</style></head><body><nav>${nav}${other}</nav><main><h1>${title}</h1>${body}</main></body></html>`;
}
