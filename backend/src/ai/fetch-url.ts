/**
 * URL import (plan 4.5 step 3). The most attack-exposed code in the product: every rule here is a guard against
 * server-side request forgery or resource exhaustion. `validateUrl` is pure; `fetchPage` follows redirects by hand
 * and re-validates every hop.
 *
 * Not checkable here: DNS rebinding to private addresses. Workers' outbound fetch cannot reach private networks
 * (no RFC 1918 route without a tunnel), so a hostname that resolves to 10.0.0.1 fails at connect time.
 */

export const MAX_URL_LENGTH = 2048;
export const MAX_REDIRECTS = 3;
export const FETCH_TIMEOUT_MS = 10_000;
export const MAX_PAGE_BYTES = 2 * 1024 * 1024;
export const MAX_TEXT_CHARS = 20_000;

/** Our own hostnames and reserved names: never fetched (no requests to ourselves or to local names). */
export const BLOCKED_HOST_SUFFIXES = ["munich-tech.de", "workers.dev", "localhost", "local", "internal", "arpa", "invalid", "test", "onion"];

/**
 * UNVERIFIED (spike S9, needs a deployed Worker): Cloudflare's malware-blocking DNS-over-HTTPS resolver answers a
 * blocked name with 0.0.0.0 / ::. Until S9 confirms it, this is the documented resolver and the rule below.
 */
export const MALWARE_RESOLVER_URL = "https://security.cloudflare-dns.com/dns-query";

export type UrlCheck = { ok: true; url: URL } | { ok: false; reason: string };

const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;
const HOSTNAME = /^[a-z0-9-]+(\.[a-z0-9-]+)+$/;

export function validateUrl(raw: string): UrlCheck {
	if (raw.length > MAX_URL_LENGTH) return { ok: false, reason: "too_long" };
	let url: URL;
	try {
		url = new URL(raw);
	} catch {
		return { ok: false, reason: "unparsable" };
	}
	if (url.protocol !== "http:" && url.protocol !== "https:") return { ok: false, reason: "scheme" };
	if (url.username || url.password) return { ok: false, reason: "credentials" };
	if (!["", "80", "443"].includes(url.port)) return { ok: false, reason: "port" };
	// The URL parser already turned decimal, octal and hex IPv4 forms into dotted quads, and IDN into punycode.
	const host = url.hostname.toLowerCase().replace(/\.$/, "");
	if (host.startsWith("[") || IPV4.test(host)) return { ok: false, reason: "ip_literal" };
	if (!HOSTNAME.test(host)) return { ok: false, reason: "hostname" };
	if (BLOCKED_HOST_SUFFIXES.some((s) => host === s || host.endsWith(`.${s}`))) return { ok: false, reason: "blocked_host" };
	return { ok: true, url };
}

/** True when the malware-blocking resolver refuses the name (A or AAAA). A resolver failure counts as "not known to be safe". */
export async function isBlockedByResolver(host: string, signal?: AbortSignal): Promise<boolean> {
	for (const type of ["A", "AAAA"]) {
		try {
			const res = await fetch(`${MALWARE_RESOLVER_URL}?name=${encodeURIComponent(host)}&type=${type}`, {
				headers: { accept: "application/dns-json" },
				signal: signal ?? AbortSignal.timeout(FETCH_TIMEOUT_MS),
			});
			if (!res.ok) return true;
			const body = (await res.json()) as { Status?: number; Answer?: { data?: string }[] };
			if (body.Status !== 0) return true;
			if ((body.Answer ?? []).some((a) => a.data === "0.0.0.0" || a.data === "::")) return true;
		} catch {
			return true;
		}
	}
	return false;
}

export type PageResult = { ok: true; text: string; finalUrl: string } | { ok: false; reason: string; transient: boolean };

class DeadlinePassed extends Error {}

/**
 * Reads at most `max` bytes of the body before `deadline`; more is an error, never a truncation of something we then
 * trust. The deadline is enforced here too: whether aborting the fetch signal also stops a body read that is already
 * running is not documented, so each read races the remaining time.
 */
async function readCapped(body: ReadableStream<Uint8Array> | null, max: number, deadline: number): Promise<string | null> {
	if (!body) return "";
	const reader = body.getReader();
	const chunks: Uint8Array[] = [];
	let size = 0;
	for (;;) {
		const remaining = deadline - Date.now();
		let timer: ReturnType<typeof setTimeout> | undefined;
		const late = new Promise<never>((_, reject) => {
			timer = setTimeout(() => reject(new DeadlinePassed()), Math.max(remaining, 0));
		});
		let step: ReadableStreamReadResult<Uint8Array>;
		try {
			step = await Promise.race([reader.read(), late]);
		} catch (e) {
			await reader.cancel().catch(() => {});
			throw e;
		} finally {
			clearTimeout(timer);
		}
		const { done, value } = step;
		if (done) break;
		size += value.byteLength;
		if (size > max) {
			await reader.cancel();
			return null;
		}
		chunks.push(value);
	}
	const all = new Uint8Array(size);
	let offset = 0;
	for (const c of chunks) {
		all.set(c, offset);
		offset += c.byteLength;
	}
	return new TextDecoder().decode(all);
}

/** One deadline (`timeoutMs`, default 10 s) for the whole import fetch: every hop, resolver query and body read. */
export async function fetchPage(raw: string, timeoutMs = FETCH_TIMEOUT_MS): Promise<PageResult> {
	const deadline = Date.now() + timeoutMs;
	const signal = AbortSignal.timeout(timeoutMs);
	try {
		let current = raw;
		for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
			const check = validateUrl(current);
			if (!check.ok) return { ok: false, reason: `url_${check.reason}`, transient: false };
			const blocked = await isBlockedByResolver(check.url.hostname.replace(/\.$/, ""), signal);
			if (Date.now() >= deadline) throw new DeadlinePassed();
			if (blocked) return { ok: false, reason: "malware_resolver", transient: false };
			let res: Response;
			try {
				res = await fetch(check.url.toString(), { redirect: "manual", headers: { accept: "text/html" }, signal });
			} catch {
				if (Date.now() >= deadline) throw new DeadlinePassed();
				return { ok: false, reason: "network", transient: true };
			}
			if (Date.now() >= deadline) throw new DeadlinePassed();
			if (res.status >= 300 && res.status < 400) {
				const location = res.headers.get("location");
				if (!location) return { ok: false, reason: "redirect_without_location", transient: false };
				current = new URL(location, check.url).toString();
				continue;
			}
			if (!res.ok) return { ok: false, reason: `status_${res.status}`, transient: res.status >= 500 };
			const type = (res.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();
			if (type !== "text/html") return { ok: false, reason: "not_html", transient: false };
			if (Number(res.headers.get("content-length") ?? "0") > MAX_PAGE_BYTES) return { ok: false, reason: "too_large", transient: false };
			const html = await readCapped(res.body, MAX_PAGE_BYTES, deadline);
			if (html === null) return { ok: false, reason: "too_large", transient: false };
			return { ok: true, text: await pageText(html), finalUrl: check.url.toString() };
		}
		return { ok: false, reason: "too_many_redirects", transient: false };
	} catch (e) {
		if (e instanceof DeadlinePassed) return { ok: false, reason: "timeout", transient: true };
		throw e;
	}
}

const DROP_KEYS = new Set(["review", "aggregateRating", "comment"]);

function findRecipe(node: unknown): Record<string, unknown> | null {
	if (Array.isArray(node)) {
		for (const n of node) {
			const r = findRecipe(n);
			if (r) return r;
		}
		return null;
	}
	if (!node || typeof node !== "object") return null;
	const obj = node as Record<string, unknown>;
	const type = obj["@type"];
	if (type === "Recipe" || (Array.isArray(type) && type.includes("Recipe"))) return obj;
	return findRecipe(obj["@graph"]);
}

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };
// Collected text is bounded, so a hostile page cannot grow memory past this.
const COLLECT_LIMIT = 4 * MAX_TEXT_CHARS;
const SKIPPED = new Set(["script", "style", "noscript", "template", "svg"]);
const BREAKS = new Set(["br", "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr"]);
const TAG_NAME = /^\/?([a-z][a-z0-9-]*)/;

/**
 * schema.org Recipe JSON-LD without reviews and ratings when present, otherwise the readable text; capped.
 * One forward scan with indexOf: every character is looked at a bounded number of times, and no regular expression
 * runs over the whole document. (A backtracking pattern took minutes on 2 MB of "<"; HTMLRewriter parses that fast
 * but calls the text handler once per character, about 40 s for 2 MB, so it is not used here.)
 */
export async function pageText(html: string): Promise<string> {
	// ASCII-only lowercasing keeps every position equal to the original's ("İ".toLowerCase() is two code units).
	const lower = html.replace(/[A-Z]/g, (c) => c.toLowerCase());
	const scripts: string[] = [];
	let text = "";
	const add = (s: string) => {
		if (text.length < COLLECT_LIMIT) text += s.slice(0, COLLECT_LIMIT - text.length);
	};
	let i = 0;
	while (i < html.length) {
		const lt = html.indexOf("<", i);
		if (lt < 0) {
			add(html.slice(i));
			break;
		}
		add(html.slice(i, lt));
		if (lower.startsWith("<!--", lt)) {
			const end = html.indexOf("-->", lt + 4);
			if (end < 0) break;
			i = end + 3;
			continue;
		}
		// As in HTML, "<" not followed by a letter, "/", "!" or "?" starts no tag ("a < b"): it is text.
		const next = lower.charCodeAt(lt + 1);
		if (!((next >= 97 && next <= 122) || next === 47 || next === 33 || next === 63)) {
			add("<");
			i = lt + 1;
			continue;
		}
		const gt = html.indexOf(">", lt + 1);
		if (gt < 0) {
			// No later tag can close either, so the rest ("a < b" near the end) is text; one slice keeps this linear.
			add(html.slice(lt));
			break;
		}
		const name = TAG_NAME.exec(lower.slice(lt + 1, Math.min(gt, lt + 32)))?.[1] ?? "";
		const closing = lower[lt + 1] === "/";
		i = gt + 1;
		if (!closing && SKIPPED.has(name)) {
			const end = lower.indexOf(`</${name}`, i);
			const body = html.slice(i, end < 0 ? html.length : end);
			if (name === "script" && lower.slice(lt, gt).includes("application/ld+json") && body.length <= MAX_PAGE_BYTES) scripts.push(body);
			if (end < 0) break;
			const endGt = html.indexOf(">", end);
			i = endGt < 0 ? html.length : endGt + 1;
			continue;
		}
		if (BREAKS.has(name) && (closing || name === "br")) add("\n");
	}

	for (const script of scripts) {
		try {
			const recipe = findRecipe(JSON.parse(script));
			if (recipe) {
				const kept = Object.fromEntries(Object.entries(recipe).filter(([k]) => !DROP_KEYS.has(k)));
				return JSON.stringify(kept).slice(0, MAX_TEXT_CHARS);
			}
		} catch {
			// malformed JSON-LD: fall through to the readable text
		}
	}
	// Linear patterns only, over the bounded collected text.
	return text
		.replace(/&(#\d{1,8}|#x[0-9a-f]{1,8}|[a-z]{1,10});/gi, (all, e: string) => {
			if (e[0] !== "#") return ENTITIES[e.toLowerCase()] ?? all;
			const code = e[1] === "x" || e[1] === "X" ? Number.parseInt(e.slice(2), 16) : Number(e.slice(1));
			return code <= 0x10ffff ? String.fromCodePoint(code) : "";
		})
		.replace(/[ \t\f\v\r]+/g, " ")
		.replace(/ ?\n[ \n]*/g, "\n")
		.trim()
		.slice(0, MAX_TEXT_CHARS);
}
