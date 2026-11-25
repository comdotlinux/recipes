/**
 * Analytics Engine SQL API (plan 4.12; docs/versions/backend.md "Analytics Engine SQL API", checked 2026-11-25):
 * POST https://api.cloudflare.com/client/v4/accounts/<account_id>/analytics_engine/sql, body = the SQL text, token with
 * "Account Analytics Read" only (secret CF_API_TOKEN_READ). The default output is JSON `{ meta, data, rows }`.
 * Rows are sampled, so every count is SUM(_sample_interval). Columns: blob1 = event name, blob2 and blob3 = codes,
 * index1 = the random metrics_id of user events (metrics.ts). The SQL is fixed text; the only inserted values are the
 * dataset name (checked against a strict pattern) and a window of 1, 7 or 30 days.
 */
export type MetricRow = Record<string, string | number | null>;
export type QueryEnv = Pick<Env, "CF_ACCOUNT_ID" | "CF_API_TOKEN_READ" | "METRICS_DATASET">;

export const WINDOWS = [1, 7, 30] as const;
export type Window = (typeof WINDOWS)[number];
const DATASET = /^[a-z][a-z0-9_]{0,63}$/;
const ACCOUNT = /^[0-9a-f]{32}$/;

/** Rows of one query, or null when the API is not configured or not reachable. */
export async function runQuery(env: QueryEnv, sql: string): Promise<MetricRow[] | null> {
	if (!ACCOUNT.test(env.CF_ACCOUNT_ID) || !env.CF_API_TOKEN_READ) return null;
	try {
		const res = await fetch(`https://api.cloudflare.com/client/v4/accounts/${env.CF_ACCOUNT_ID}/analytics_engine/sql`, {
			method: "POST",
			headers: { authorization: `Bearer ${env.CF_API_TOKEN_READ}` },
			body: sql,
			signal: AbortSignal.timeout(10_000),
		});
		if (!res.ok) {
			await res.body?.cancel();
			return null;
		}
		const body = (await res.json()) as { data?: unknown };
		return Array.isArray(body.data) ? (body.data as MetricRow[]) : null;
	} catch {
		return null;
	}
}

const table = (env: QueryEnv) => {
	if (!DATASET.test(env.METRICS_DATASET)) throw new Error("METRICS_DATASET is not a dataset name");
	return env.METRICS_DATASET;
};
const since = (days: Window) => `timestamp > NOW() - INTERVAL '${days}' DAY`;

/** Every event with its two codes and its count over the window. */
export const eventCountsSql = (env: QueryEnv, days: Window) =>
	`SELECT blob1 AS event, blob2 AS a, blob3 AS b, SUM(_sample_interval) AS n FROM ${table(env)} WHERE ${since(days)} GROUP BY event, a, b`;

/** Distinct accounts with at least one counted request (job polling is never written as a request, metrics.ts). */
export const activeUsersSql = (env: QueryEnv, days: Window) =>
	`SELECT COUNT(DISTINCT index1) AS n FROM ${table(env)} WHERE blob1 = 'request' AND ${since(days)}`;

/** Per day: an event and its first code, for the daily tables. */
export const dailySql = (env: QueryEnv, event: "billing_webhook" | "account_created" | "request") =>
	`SELECT toStartOfDay(timestamp) AS day, blob2 AS a, SUM(_sample_interval) AS n, COUNT(DISTINCT index1) AS users FROM ${table(env)} WHERE blob1 = '${event}' AND ${since(30)} GROUP BY day, a ORDER BY day`;

/** Accounts ranked by requests over 30 days, with the route group breakdown; only the random metrics_id is shown. */
export const topAccountsSql = (env: QueryEnv) =>
	`SELECT index1 AS id, blob2 AS grp, SUM(_sample_interval) AS n FROM ${table(env)} WHERE blob1 = 'request' AND ${since(30)} GROUP BY id, grp ORDER BY n DESC LIMIT 500`;

/** Reports: AI-result reports by kind and reason, and share reports with their encrypted reference. */
export const reportsSql = (env: QueryEnv) =>
	`SELECT timestamp, blob1 AS event, blob2 AS a, blob3 AS b FROM ${table(env)} WHERE (blob1 = 'share_report' OR blob1 = 'report') AND ${since(30)} ORDER BY timestamp DESC LIMIT 200`;

export type Overview = {
	activeUsers: number;
	newAccounts: number;
	signInFailures: Record<string, number>;
	importsByOutcome: Record<string, number>;
	guardBlocks: number;
	translations: number;
	pdfs: number;
	shares: number;
	reports: number;
	quotaDenials: number;
	aiCalls: number;
	webhookFailures: number;
	entitlementMismatches: number;
	deletionsErased: number;
	internalErrors: number;
};

/** The plan 4.12 overview counters from event rows (eventCountsSql) and the active-user count. */
export function overview(rows: MetricRow[], activeUsers: number): Overview {
	const o: Overview = {
		activeUsers,
		newAccounts: 0,
		signInFailures: {},
		importsByOutcome: {},
		guardBlocks: 0,
		translations: 0,
		pdfs: 0,
		shares: 0,
		reports: 0,
		quotaDenials: 0,
		aiCalls: 0,
		webhookFailures: 0,
		entitlementMismatches: 0,
		deletionsErased: 0,
		internalErrors: 0,
	};
	const add = (rec: Record<string, number>, k: string, n: number) => (rec[k] = (rec[k] ?? 0) + n);
	for (const r of rows) {
		const n = Number(r.n) || 0;
		const [event, a, b] = [String(r.event), String(r.a ?? ""), String(r.b ?? "")];
		if (event === "account_created") o.newAccounts += n;
		else if (event === "auth_failure") {
			if (a === "webhook_unauthorized") o.webhookFailures += n;
			else add(o.signInFailures, a, n);
		} else if (event === "import_outcome") {
			// Every job made at least one model call; a translation is a job of kind "translation".
			o.aiCalls += n;
			if (a === "translation") o.translations += n;
			else add(o.importsByOutcome, b, n);
			if (b === "guard_block") o.guardBlocks += n;
		} else if (event === "screen" && (a === "safe" || a === "not_safe")) o.aiCalls += n;
		else if (event === "pdf" && a === "rendered") o.pdfs += n;
		else if (event === "share" && a === "created") o.shares += n;
		else if (event === "report" || event === "share_report") o.reports += n;
		else if (event === "quota_denied") o.quotaDenials += n;
		else if (event === "entitlement_refresh" && a === "webhook" && (b === "unavailable" || b === "gave_up")) o.webhookFailures += n;
		else if (event === "entitlement_mismatch") o.entitlementMismatches += n;
		else if (event === "account_deletion" && a === "erased") o.deletionsErased += n;
		else if (event === "internal_error") o.internalErrors += n;
	}
	return o;
}

/**
 * Active subscribers from RevenueCat's overview metrics (plan 4.12): `GET /v2/projects/{id}/metrics/overview`, key
 * permission `charts_metrics:overview:read` (docs/versions/backend.md). The docs show only the id `active_trials`, so
 * `active_subscriptions` is UNVERIFIED; null whenever the figure is not there, and the page then shows only the link.
 */
export async function activeSubscribers(env: { REVENUECAT_V2_API_KEY?: string; REVENUECAT_PROJECT_ID: string }): Promise<number | null> {
	if (!env.REVENUECAT_V2_API_KEY || !env.REVENUECAT_PROJECT_ID) return null;
	try {
		const res = await fetch(`https://api.revenuecat.com/v2/projects/${encodeURIComponent(env.REVENUECAT_PROJECT_ID)}/metrics/overview`, {
			headers: { authorization: `Bearer ${env.REVENUECAT_V2_API_KEY}` },
			signal: AbortSignal.timeout(5_000),
		});
		if (!res.ok) {
			await res.body?.cancel();
			return null;
		}
		const body = (await res.json()) as { metrics?: { id?: unknown; value?: unknown }[] };
		const value = body.metrics?.find((m) => m.id === "active_subscriptions")?.value;
		return typeof value === "number" && Number.isFinite(value) ? value : null;
	} catch {
		return null;
	}
}
