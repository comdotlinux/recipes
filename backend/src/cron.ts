/**
 * Scheduled alert check every 30 minutes (plan 4.10, AC32). Reads the last hour of metrics from Analytics Engine,
 * checks the plan's thresholds and posts one message to ALERT_WEBHOOK_URL when any is crossed; silent otherwise.
 * Messages carry counter names and numbers only: metrics hold no content and no user id (metrics.ts), and nothing here
 * adds any.
 */
import { type MetricRow, type QueryEnv, overview, runQuery } from "./admin/queries";
import { AI_GATEWAY_LIMIT } from "./limits";

export const THRESHOLDS = {
	/** AI calls in the last hour above this share of the gateway ceiling. */
	aiShare: 0.8,
	/** Imports ending as failed above this share in the last hour. */
	importFailureRate: 0.1,
	/** Webhook failures in the last hour with no successful webhook refresh. */
	webhookStreak: 5,
} as const;

const HOUR_SQL = (dataset: string) =>
	`SELECT blob1 AS event, blob2 AS a, blob3 AS b, SUM(_sample_interval) AS n FROM ${dataset} WHERE timestamp > NOW() - INTERVAL '1' HOUR GROUP BY event, a, b`;
const DATASET = /^[a-z][a-z0-9_]{0,63}$/;

/** The alert lines for one hour of event rows and the health check result; empty when everything is fine. */
export function alerts(rows: MetricRow[], healthOk: boolean): string[] {
	const o = overview(rows, 0);
	const out: string[] = [];
	const ceiling = (AI_GATEWAY_LIMIT.requests * 3600) / AI_GATEWAY_LIMIT.periodSeconds;
	if (o.aiCalls > THRESHOLDS.aiShare * ceiling) out.push(`AI calls: ${o.aiCalls} in the last hour, above ${THRESHOLDS.aiShare * 100}% of the gateway ceiling (${ceiling})`);
	const imports = Object.values(o.importsByOutcome).reduce((a, b) => a + b, 0);
	const failed = o.importsByOutcome.failed ?? 0;
	if (imports > 0 && failed / imports > THRESHOLDS.importFailureRate) out.push(`Import failures: ${failed} of ${imports} in the last hour`);
	const webhookOk = rows
		.filter((r) => r.event === "entitlement_refresh" && r.a === "webhook" && (r.b === "active" || r.b === "inactive"))
		.reduce((n, r) => n + Number(r.n), 0);
	if (o.webhookFailures >= THRESHOLDS.webhookStreak && webhookOk === 0) out.push(`Webhook failures: ${o.webhookFailures} in the last hour without a successful one`);
	if (o.entitlementMismatches > 0) out.push(`Entitlement mismatches: ${o.entitlementMismatches} in the last hour`);
	const refused = rows.filter((r) => r.event === "internal_error").reduce((n, r) => n + Number(r.n), 0);
	if (refused > 0) out.push(`Internal errors (for example an erasure refused outside deletion): ${refused} in the last hour`);
	if (!healthOk) out.push("Health check failed: GET /v1/health did not answer ok");
	return out;
}

type CronEnv = QueryEnv & Pick<Env, "APP_HOST" | "ALERT_WEBHOOK_URL">;

async function healthy(host: string): Promise<boolean> {
	try {
		const res = await fetch(`https://${host}/v1/health`, { signal: AbortSignal.timeout(10_000) });
		return res.ok && ((await res.json()) as { status?: unknown }).status === "ok";
	} catch {
		return false;
	}
}

/** Runs one check; returns the alert lines it posted (for tests and logs). */
export async function checkAndAlert(env: CronEnv): Promise<string[]> {
	const healthOk = env.APP_HOST ? await healthy(env.APP_HOST) : true;
	// Without the analytics account configured (env.local) only the health check runs.
	const rows = env.CF_ACCOUNT_ID && DATASET.test(env.METRICS_DATASET) ? await runQuery(env, HOUR_SQL(env.METRICS_DATASET)) : [];
	const lines = rows === null ? ["Metrics could not be read from Analytics Engine", ...alerts([], healthOk)] : alerts(rows, healthOk);
	if (lines.length === 0 || !env.ALERT_WEBHOOK_URL) return lines;
	try {
		const res = await fetch(env.ALERT_WEBHOOK_URL, {
			method: "POST",
			headers: { "content-type": "application/json" },
			body: JSON.stringify({ text: `Recipes (${env.APP_HOST || "local"}):\n${lines.join("\n")}` }),
			signal: AbortSignal.timeout(10_000),
		});
		await res.body?.cancel();
		if (!res.ok) console.error(`alert webhook answered ${res.status}`);
	} catch (e) {
		console.error(`alert webhook failed: ${e instanceof Error ? e.name : "non-error value"}`);
	}
	return lines;
}
