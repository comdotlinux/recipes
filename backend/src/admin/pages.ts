/**
 * Server-rendered admin pages (plan 4.12). Plain HTML tables, every value escaped, no script at all, no gradients and
 * no purple: the only colours are greys and one dark blue for links.
 */
import { escapeHtml as e } from "../web";
import type { MetricRow, Overview, Window } from "./queries";

export const ADMIN_CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
export const REVENUECAT_DASHBOARD = "https://app.revenuecat.com/";
const LINKS_OUT: [string, string][] = [
	["Cloudflare Workers analytics", "https://dash.cloudflare.com/?to=/:account/workers-and-pages"],
	["AI Gateway analytics", "https://dash.cloudflare.com/?to=/:account/ai/ai-gateway"],
	["RevenueCat", REVENUECAT_DASHBOARD],
];

const STYLE = "body{font:15px/1.4 system-ui,sans-serif;margin:16px;color:#1a1a1a;background:#fff}table{border-collapse:collapse;margin:8px 0 16px}td,th{border:1px solid #bbb;padding:4px 8px;text-align:left}th{background:#eee}a{color:#0b4f8a}nav a{margin-right:12px}";

export function layout(title: string, body: string): string {
	const nav = [
		["/admin/", "Overview"],
		["/admin/subscriptions", "Subscriptions"],
		["/admin/users", "Users"],
		["/admin/reports", "Reports"],
	]
		.map(([href, label]) => `<a href="${href}">${label}</a>`)
		.join("");
	const out = LINKS_OUT.map(([label, href]) => `<a href="${e(href)}">${e(label)}</a>`).join(" · ");
	return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex"><title>Recipes admin: ${e(title)}</title><style>${STYLE}</style></head><body><nav>${nav}</nav><h1>${e(title)}</h1>${body}<p>${out}</p></body></html>`;
}

const cell = (v: unknown) => `<td>${e(v === null || v === undefined ? "" : String(v))}</td>`;
export const tableHtml = (head: string[], rows: unknown[][]) =>
	`<table><tr>${head.map((h) => `<th>${e(h)}</th>`).join("")}</tr>${rows.map((r) => `<tr>${r.map(cell).join("")}</tr>`).join("")}</table>`;

const UNAVAILABLE = "<p>Analytics Engine could not be read (token, account id or dataset not configured, or the API did not answer).</p>";

export function overviewPage(windows: { days: Window; o: Overview }[] | null, aiCeiling: number): string {
	if (!windows) return layout("Overview", UNAVAILABLE);
	const head = ["Counter", ...windows.map((w) => `${w.days} day${w.days === 1 ? "" : "s"}`)];
	const row = (label: string, pick: (o: Overview) => number) => [label, ...windows.map((w) => pick(w.o))];
	const keys = (pick: (o: Overview) => Record<string, number>) => [...new Set(windows.flatMap((w) => Object.keys(pick(w.o))))].sort();
	const rows = [
		row("Active users", (o) => o.activeUsers),
		row("New accounts", (o) => o.newAccounts),
		...keys((o) => o.signInFailures).map((k) => row(`Sign-in failures: ${k}`, (o) => o.signInFailures[k] ?? 0)),
		...keys((o) => o.importsByOutcome).map((k) => row(`Imports: ${k}`, (o) => o.importsByOutcome[k] ?? 0)),
		row("Guard blocks", (o) => o.guardBlocks),
		row("Translations", (o) => o.translations),
		row("PDFs", (o) => o.pdfs),
		row("Shares", (o) => o.shares),
		row("Reports", (o) => o.reports),
		row("Quota denials", (o) => o.quotaDenials),
		row(`AI jobs and picture checks (ceiling ${aiCeiling} per hour)`, (o) => o.aiCalls),
		row("Webhook failures", (o) => o.webhookFailures),
		row("Entitlement mismatches", (o) => o.entitlementMismatches),
		row("Accounts erased", (o) => o.deletionsErased),
		row("Internal errors", (o) => o.internalErrors),
	];
	return layout("Overview", tableHtml(head, rows));
}

export function subscriptionsPage(active: number | null, daily: MetricRow[] | null): string {
	const figure =
		active === null
			? `<p>Active subscribers: not available from RevenueCat right now. See the <a href="${REVENUECAT_DASHBOARD}">RevenueCat dashboard</a>, which stays the financial source of truth.</p>`
			: `<p>Active subscribers now: <strong>${e(String(active))}</strong> (RevenueCat). The <a href="${REVENUECAT_DASHBOARD}">RevenueCat dashboard</a> stays the financial source of truth.</p>`;
	const changes = daily ? tableHtml(["Day", "RevenueCat event", "Count"], daily.map((r) => [r.day, r.a, r.n])) : UNAVAILABLE;
	return layout("Subscriptions", `${figure}<h2>Subscription events per day (30 days)</h2>${changes}`);
}

export function usersPage(created: MetricRow[] | null, requests: MetricRow[] | null, top: MetricRow[] | null): string {
	if (!created || !requests || !top) return layout("Users", UNAVAILABLE);
	const byDay = new Map<string, { created: number; active: number }>();
	for (const r of created) byDay.set(String(r.day), { created: (byDay.get(String(r.day))?.created ?? 0) + Number(r.n), active: byDay.get(String(r.day))?.active ?? 0 });
	for (const r of requests) {
		const d = byDay.get(String(r.day)) ?? { created: 0, active: 0 };
		d.active = Math.max(d.active, Number(r.users));
		byDay.set(String(r.day), d);
	}
	const accounts = new Map<string, { total: number; groups: string[] }>();
	for (const r of top) {
		const a = accounts.get(String(r.id)) ?? { total: 0, groups: [] };
		a.total += Number(r.n);
		a.groups.push(`${r.grp} ${r.n}`);
		accounts.set(String(r.id), a);
	}
	const ranked = [...accounts.entries()].sort((x, y) => y[1].total - x[1].total).slice(0, 50);
	return layout(
		"Users",
		`<h2>Per day (30 days)</h2>${tableHtml(["Day", "Accounts created", "Active users (largest route group)"], [...byDay.entries()].sort().map(([d, v]) => [d, v.created, v.active]))}` +
			`<h2>Accounts by requests (30 days)</h2><p>Random metrics ids; they cannot be turned back into a Support ID.</p>${tableHtml(["Metrics id", "Requests", "By route group"], ranked.map(([id, v]) => [id, v.total, v.groups.join(", ")]))}` +
			`<h2>Open an account</h2><form method="get" action="/admin/user"><label>Support ID <input name="id" size="50" required></label> <button>Open</button></form>`,
	);
}

export function reportsPage(rows: MetricRow[] | null): string {
	if (!rows) return layout("Reports", UNAVAILABLE);
	const body = rows.map((r) =>
		r.event === "share_report"
			? `<tr>${cell(r.timestamp)}${cell("shared recipe")}${cell(r.a)}<td><a href="/admin/user?ref=${encodeURIComponent(String(r.b))}">open reported account</a></td></tr>`
			: `<tr>${cell(r.timestamp)}${cell(r.a)}${cell(r.b)}${cell("")}</tr>`,
	);
	return layout("Reports", `<p>A share report's reference opens for 30 days.</p><table><tr><th>Time</th><th>Kind</th><th>Reason</th><th>Account</th></tr>${body.join("")}</table>`);
}

export function userPage(userId: string, summary: Record<string, unknown>, reasons: readonly string[]): string {
	const s = summary as {
		state: string;
		suspensionReason?: string | null;
		recentJobs?: { kind: string; status: string; createdAt: string }[];
		requests?: { day: string; key: string; count: number }[];
		audit?: { at: string; actor: string; action: string; reason: string | null }[];
		entitlement?: { active: boolean; expiresAt: string | null };
	};
	const facts = Object.entries(summary)
		.filter(([, v]) => typeof v !== "object" || v === null)
		.map(([k, v]) => [k, v]);
	if (s.entitlement) facts.push(["entitlement", `${s.entitlement.active ? "active" : "inactive"}${s.entitlement.expiresAt ? ` until ${s.entitlement.expiresAt}` : ""}`]);
	const action =
		s.state === "active" || s.state === "suspended"
			? `<h2>Suspension</h2><form method="post" action="/admin/user/suspend"><input type="hidden" name="id" value="${e(userId)}">` +
				`<label>Reason <select name="reason">${[...reasons, "none"].map((r) => `<option value="${e(r)}">${r === "none" ? "lift the suspension" : e(r)}</option>`).join("")}</select></label> ` +
				`<label>Type SUSPEND or UNSUSPEND to confirm <input name="confirm" required></label> <button>Apply</button></form>`
			: "";
	return layout(
		"Account",
		`<p>Support ID ${e(userId)}</p>${tableHtml(["Field", "Value"], facts)}` +
			(s.recentJobs ? `<h2>Recent jobs</h2>${tableHtml(["Kind", "Outcome", "Created"], s.recentJobs.map((j) => [j.kind, j.status, j.createdAt]))}` : "") +
			(s.requests ? `<h2>Requests per day and route group (90 days)</h2>${tableHtml(["Day", "Route group", "Count"], s.requests.map((r) => [r.day, r.key, r.count]))}` : "") +
			(s.audit ? `<h2>Audit</h2>${tableHtml(["Time", "Operator", "Action", "Reason"], s.audit.map((a) => [a.at, a.actor, a.action, a.reason]))}` : "") +
			action,
	);
}
