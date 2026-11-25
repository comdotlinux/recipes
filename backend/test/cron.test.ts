import { createExecutionContext, createScheduledController, env, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { alerts, checkAndAlert } from "../src/cron";
import worker from "../src/index";
import { AI_GATEWAY_LIMIT } from "../src/limits";
import { call, inObject, interceptOutbound, signIn, uuid } from "./api/helpers";
import inputFixture from "../../contract/fixtures/recipe_input.json";

const ACCOUNT = "0123456789abcdef0123456789abcdef";
const HOOK = "https://alerts.example/hook";
const cronEnv = { ...env, CF_ACCOUNT_ID: ACCOUNT, CF_API_TOKEN_READ: "t", METRICS_DATASET: "recipes_metrics", APP_HOST: "recipe.example", ALERT_WEBHOOK_URL: HOOK } as unknown as Env;

let rows: unknown[] | null = [];
let health = () => Response.json({ status: "ok", version: 1 });
let posted: string[] = [];

beforeEach(() => {
	rows = [];
	posted = [];
	health = () => Response.json({ status: "ok", version: 1 });
	const spy = interceptOutbound();
	const base = spy.getMockImplementation()!;
	spy.mockImplementation(async (input, init) => {
		const req = new Request(input, init);
		if (req.url.endsWith("/analytics_engine/sql")) {
			expect(await req.text()).toContain("INTERVAL '1' HOUR");
			return rows ? Response.json({ data: rows }) : new Response(null, { status: 500 });
		}
		if (req.url === "https://recipe.example/v1/health") return health();
		if (req.url === HOOK) {
			posted.push(await req.text());
			return new Response(null, { status: 200 });
		}
		return base(input, init);
	});
});
afterEach(() => vi.restoreAllMocks());

const row = (event: string, a: string | null, b: string | null, n: number) => ({ event, a, b, n });

describe("alert thresholds (plan 4.10, AC32)", () => {
	it("stays silent below every threshold", async () => {
		rows = [row("import_outcome", "import_url", "done", 20), row("import_outcome", "import_url", "failed", 2), row("entitlement_refresh", "webhook", "unavailable", 9), row("entitlement_refresh", "webhook", "active", 1)];
		expect(await checkAndAlert(cronEnv)).toEqual([]);
		expect(posted).toEqual([]);
	});

	it.each([
		["AI calls above 80% of the ceiling", [row("import_outcome", "import_url", "done", Math.floor(AI_GATEWAY_LIMIT.requests * 0.8) + 1)], /AI calls/],
		["import failure rate above 10%", [row("import_outcome", "import_url", "done", 8), row("import_outcome", "import_text", "failed", 2)], /Import failures: 2 of 10/],
		["webhook failure streak of 5", [row("entitlement_refresh", "webhook", "gave_up", 3), row("auth_failure", "webhook_unauthorized", null, 2)], /Webhook failures: 5/],
		["any entitlement mismatch", [row("entitlement_mismatch", "daily", null, 1)], /Entitlement mismatches: 1/],
		["an erasure refused outside deletion", [row("internal_error", "erase_refused", null, 1)], /Internal errors/],
		["deletion step retries in the last hour", [row("account_deletion", "step_retry", null, 2)], /Account deletion step retries: 2/],
		["an abandoned third-party deletion step", [row("account_deletion", "third_party_abandoned", "apple", 1)], /abandoned after 7 days: 1/],
	])("posts when %s", async (_label, fixture, expected) => {
		rows = fixture;
		const lines = await checkAndAlert(cronEnv);
		expect(lines).toHaveLength(1);
		expect(lines[0]).toMatch(expected);
		expect(posted).toHaveLength(1);
		expect(JSON.parse(posted[0]).text).toMatch(expected);
	});

	it("posts when the health check fails, and when metrics cannot be read", async () => {
		health = () => new Response(null, { status: 500 });
		expect(await checkAndAlert(cronEnv)).toEqual(["Health check failed: GET /v1/health did not answer ok"]);
		health = () => Response.json({ status: "ok" });
		rows = null;
		expect(await checkAndAlert(cronEnv)).toEqual(["Metrics could not be read from Analytics Engine"]);
	});

	it("the scheduled handler runs the check", async () => {
		rows = [row("entitlement_mismatch", "stale", null, 1)];
		const ctx = createExecutionContext();
		await worker.scheduled!(createScheduledController({ cron: "*/30 * * * *" }), cronEnv, ctx);
		await waitOnExecutionContext(ctx);
		expect(posted).toHaveLength(1);
	});

	it("alerts() is pure: no rows and a healthy check give nothing", () => {
		expect(alerts([], true)).toEqual([]);
	});
});

describe("no metric or log line contains recipe text or a user id (AC32)", () => {
	it("a flow with a marker recipe, an import, a report and a deletion writes neither", async () => {
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		const logs = [vi.spyOn(console, "log"), vi.spyOn(console, "error"), vi.spyOn(console, "warn")];
		const s = await signIn();
		const id = uuid();
		await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, id, title: "MARKER-RECIPE", imageIds: [] }), headers: { "content-type": "application/json", "if-none-match": "*" } });
		await call("/me/consent", { method: "PUT", token: s.token, body: JSON.stringify({ version: 1 }), headers: { "content-type": "application/json" } });
		await call("/imports", { method: "POST", token: s.token, body: JSON.stringify({ kind: "text", text: "MARKER-IMPORT\nstep" }), headers: { "content-type": "application/json", "idempotency-key": uuid() } });
		await call("/reports", { method: "POST", token: s.token, body: JSON.stringify({ kind: "import_result", jobId: uuid(), reason: "other", note: "MARKER-NOTE" }), headers: { "content-type": "application/json" } });
		await call("/account", { method: "DELETE", token: s.token });
		await inObject(s.userId, (i) => i.runDeletion(Date.now()).catch(() => undefined));
		const everything = JSON.stringify([write.mock.calls, ...logs.map((l) => l.mock.calls)]);
		expect(write.mock.calls.length).toBeGreaterThan(3);
		expect(everything).not.toContain("MARKER");
		expect(everything).not.toContain(s.userId);
	});
});
