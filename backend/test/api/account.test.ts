import { env, runDurableObjectAlarm } from "cloudflare:test";
import { exportPKCS8, generateKeyPair } from "jose";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MIGRATIONS } from "../../src/storage/migrations";
import { newShareToken } from "../../src/share";
import { DAY_MS, THIRD_PARTY_DEADLINE_MS, TOMBSTONE_KEYS, TOMBSTONE_TTL_MS, type UserDO } from "../../src/user-do";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import { REVENUECAT, TURNSTILE_OK, call, inObject, interceptOutbound, json, nonce, providerToken, signIn, userStub, uuid, type Session } from "./helpers";

// ---- outbound: RevenueCat v2 customer delete and Apple token endpoints, answered per test ----

type Hit = { url: string; method: string; body: string; auth: string | null };
let hits: Hit[] = [];
let rcDelete: () => number = () => 200;
let appleToken: () => Response = () => Response.json({ refresh_token: "apple-refresh-1", access_token: "a", token_type: "bearer", expires_in: 3600, id_token: "x" });
let appleRevoke: () => number = () => 200;
/** While set, RevenueCat v1 reads (entitlement refresh) wait for it: a request's await that deletion can overtake. */
let v1Gate: Promise<void> | null = null;
let v1Waiting = 0;

const testEnv = env as unknown as Record<string, string | undefined>;
const appleKey = await exportPKCS8((await generateKeyPair("ES256", { extractable: true })).privateKey);

beforeEach(() => {
	hits = [];
	rcDelete = () => 200;
	appleRevoke = () => 200;
	Object.assign(testEnv, { REVENUECAT_V2_API_KEY: "test-v2-key-not-a-secret", REVENUECAT_PROJECT_ID: "proj1", APPLE_P8: appleKey, APPLE_KEY_ID: "KEY1234567", APPLE_TEAM_ID: "TEAM123456" });
	const spy = interceptOutbound();
	const base = spy.getMockImplementation()!;
	spy.mockImplementation(async (input, init) => {
		const req = new Request(input, init);
		const hit = { url: req.url, method: req.method, body: req.method === "GET" ? "" : new TextDecoder().decode(await req.clone().arrayBuffer()), auth: req.headers.get("authorization") };
		if (req.url.startsWith(`${REVENUECAT}v1/`) && v1Gate) {
			v1Waiting++;
			await v1Gate;
			return new Response(null, { status: 503 });
		}
		if (req.url.startsWith(`${REVENUECAT}v2/`)) {
			hits.push(hit);
			return new Response(null, { status: rcDelete() });
		}
		if (req.url === "https://appleid.apple.com/auth/token") {
			hits.push(hit);
			return appleToken();
		}
		if (req.url === "https://appleid.apple.com/auth/revoke") {
			hits.push(hit);
			return new Response(null, { status: appleRevoke() });
		}
		return base(input, init);
	});
});
afterEach(() => {
	vi.restoreAllMocks();
	for (const k of ["REVENUECAT_V2_API_KEY", "APPLE_P8", "APPLE_TEAM_ID"]) testEnv[k] = undefined;
	testEnv.REVENUECAT_PROJECT_ID = "";
	testEnv.APPLE_KEY_ID = "";
});

const tables = (userId: string) =>
	inObject(userId, (_i, st) =>
		st.storage.sql
			.exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*' ORDER BY name")
			.toArray()
			.map((r) => r.name),
	);
const metaRows = (userId: string) =>
	inObject(userId, (_i, st) => Object.fromEntries(st.storage.sql.exec<{ key: string; value: string }>("SELECT key, value FROM meta ORDER BY key").toArray().map((r) => [r.key, r.value])));
const sql = (userId: string, query: string, ...args: unknown[]) => inObject(userId, (_i, st) => void st.storage.sql.exec(query, ...args));
const deleteAccount = (s: Session) => call("/account", { method: "DELETE", token: s.token });
const alarm = (userId: string) => runDurableObjectAlarm(userStub(userId));

async function appleSignIn(sub: string, appleAuthCode?: string): Promise<Session> {
	const n = await nonce();
	const res = await call("/session", {
		method: "POST",
		...json({ provider: "apple", idToken: await providerToken("apple", n, { sub }), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true, ...(appleAuthCode ? { appleAuthCode } : {}) }),
	});
	expect(res.status).toBe(200);
	return (await res.json()) as Session;
}

describe("DELETE /account (plan 4.7, AC28)", () => {
	it("switches to deleting at once: every call answers 401, other sessions too, links are revoked, a new sign-in gets 409", async () => {
		const s = await signIn("deleting-1");
		const other = await signIn("deleting-1");
		await sql(s.userId, "INSERT INTO shares (share_id, recipe_id, created_at, expires_at) VALUES ('ab', 'r', 1, ?)", Date.now() + DAY_MS);
		rcDelete = () => 503; // keeps the object in the deleting state for this test
		const res = await deleteAccount(s);
		expect(res.status).toBe(202);
		expect(await res.json()).toEqual({ state: "deleting" });
		for (const token of [s.token, other.token]) {
			expect((await call("/me", { token })).status).toBe(401);
			expect((await call("/recipes", { token })).status).toBe(401);
			expect((await deleteAccount({ ...s, token })).status).toBe(401);
		}
		const rows = await inObject(s.userId, (_i, st) => ({
			sessions: st.storage.sql.exec("SELECT count(*) AS n FROM sessions").one().n,
			revoked: st.storage.sql.exec("SELECT revoked FROM shares").one().revoked,
			tasks: st.storage.sql.exec("SELECT id FROM tasks").toArray(),
		}));
		expect(rows).toEqual({ sessions: 0, revoked: 1, tasks: [{ id: "deletion" }] });
		const n = await nonce();
		const again = await call("/session", { method: "POST", ...json({ provider: "test", idToken: `__TEST_IDP__|deleting-1|${n}`, nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true }) });
		expect(again.status).toBe(409);
	});

	it("calls the RevenueCat v2 customer delete and the Apple revoke, then leaves an empty object when no tombstone is needed", async () => {
		const s = await appleSignIn("apple-deleter", "code-1");
		await alarm(s.userId); // exchanges the code
		expect(hits.map((h) => h.url)).toEqual(["https://appleid.apple.com/auth/token"]);
		const exchange = new URLSearchParams(hits[0].body);
		expect([exchange.get("grant_type"), exchange.get("code"), exchange.get("client_id")]).toEqual(["authorization_code", "code-1", "local.test.apple"]);
		expect(exchange.get("client_secret")!.split(".")).toHaveLength(3);
		expect((await metaRows(s.userId)).apple_auth_codes).toBeUndefined();
		expect(JSON.parse((await metaRows(s.userId)).apple_refresh_tokens)).toEqual({ "local.test.apple": "apple-refresh-1" });
		hits = [];
		expect((await deleteAccount(s)).status).toBe(202);
		await alarm(s.userId);
		expect(hits.map((h) => [h.method, h.url])).toEqual([
			["DELETE", `https://api.revenuecat.com/v2/projects/proj1/customers/${encodeURIComponent(s.userId)}`],
			["POST", "https://appleid.apple.com/auth/revoke"],
		]);
		expect(hits[0].auth).toBe("Bearer test-v2-key-not-a-secret");
		const revoke = new URLSearchParams(hits[1].body);
		expect([revoke.get("token"), revoke.get("token_type_hint"), revoke.get("client_id")]).toEqual(["apple-refresh-1", "refresh_token", "local.test.apple"]);
		expect(await tables(s.userId)).toEqual([]);
		expect(await inObject(s.userId, (_i, st) => st.storage.getAlarm())).toBeNull();
	});

	it("a failing step is retried until done; a step that succeeded is not repeated", async () => {
		const s = await appleSignIn("retry-user", "code-2");
		await alarm(s.userId);
		let appleCalls = 0;
		appleRevoke = () => (++appleCalls === 1 ? 200 : 500);
		let rcCalls = 0;
		rcDelete = () => (++rcCalls < 3 ? 503 : 404); // 404 counts as done
		await deleteAccount(s);
		const retryNow = () => sql(s.userId, "UPDATE tasks SET due_at = 0 WHERE id = 'deletion'");
		await alarm(s.userId);
		await retryNow();
		await alarm(s.userId);
		expect((await metaRows(s.userId)).state).toBe("deleting");
		const task = await inObject(s.userId, (_i, st) => st.storage.sql.exec<{ attempts: number }>("SELECT attempts FROM tasks WHERE id = 'deletion'").one());
		expect(task.attempts).toBe(2);
		await retryNow();
		await alarm(s.userId);
		expect([rcCalls, appleCalls]).toEqual([3, 1]);
		expect(await tables(s.userId)).toEqual([]);
	});

	it("an expired or refused Apple code leaves nothing to revoke", async () => {
		appleToken = () => Response.json({ error: "invalid_grant" }, { status: 400 });
		const s = await appleSignIn("refused-code", "code-3");
		await alarm(s.userId);
		await deleteAccount(s);
		await alarm(s.userId);
		expect(hits.map((h) => h.url.replace(/customers\/.*/, "customers/x"))).toEqual(["https://appleid.apple.com/auth/token", `${REVENUECAT}v2/projects/proj1/customers/x`]);
		expect(await tables(s.userId)).toEqual([]);
		appleToken = () => Response.json({ refresh_token: "apple-refresh-1" });
	});

	it("keeps a tombstone with only the free-import facts for 12 months when the free import was used", async () => {
		const s = await signIn("tombstoned");
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '123'), ('free_import_refunds', '2'), ('consent_version', '1')");
		await sql(s.userId, "INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES ('r1', 1, 0, 1, '{\"title\":\"MARKER\"}')");
		await deleteAccount(s);
		const before = Date.now();
		await alarm(s.userId);
		expect(await tables(s.userId)).toEqual(["meta", "tasks"]);
		const meta = await metaRows(s.userId);
		expect(Object.keys(meta).every((k) => (TOMBSTONE_KEYS as readonly string[]).includes(k))).toBe(true);
		expect(meta).toMatchObject({ state: "tombstone", free_import_used_at: "123", free_import_refunds: "2" });
		const until = Number(meta.tombstone_until);
		expect(until - before).toBeGreaterThanOrEqual(TOMBSTONE_TTL_MS);
		expect(await inObject(s.userId, (_i, st) => st.storage.getAlarm())).toBe(until);
		expect(JSON.stringify(meta)).not.toContain("MARKER");
	});

	it("a late webhook or request changes nothing and a tombstone writes no metrics", async () => {
		const s = await signIn("late");
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '1')");
		await deleteAccount(s);
		await alarm(s.userId);
		const before = await metaRows(s.userId);
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		const hook = await call("/webhooks/revenuecat", {
			method: "POST",
			headers: { authorization: "Bearer test-webhook-secret-not-a-secret", "content-type": "application/json" },
			body: JSON.stringify({ event: { type: "RENEWAL", app_user_id: s.userId } }),
		});
		expect(hook.status).toBe(200);
		expect((await call("/me", { token: s.token })).status).toBe(401);
		expect(await userStub(s.userId).entitlementChanged()).toBe(false);
		expect(await metaRows(s.userId)).toEqual(before);
		expect(await tables(s.userId)).toEqual(["meta", "tasks"]);
		expect(write.mock.calls.filter((c) => c[0]?.indexes?.length)).toEqual([]);
	});

	it("the constructor does not recreate tables for a tombstone, and repairs a lost alarm", async () => {
		const s = await signIn("reload");
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '1')");
		await deleteAccount(s);
		await alarm(s.userId);
		await inObject(s.userId, async (_i, st) => {
			await st.storage.deleteAlarm();
			st.abort("reload");
		}).catch(() => undefined);
		expect(await tables(s.userId)).toEqual(["meta", "tasks"]);
		const until = Number((await metaRows(s.userId)).tombstone_until);
		expect(await inObject(s.userId, (_i, st) => st.storage.getAlarm())).toBe(until);
	});

	it("erase() outside deletion throws and writes a metric", async () => {
		const s = await signIn("not-deleting");
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		await expect(inObject(s.userId, (i) => i.erase(Date.now()))).rejects.toThrow(/erase refused/);
		expect(write.mock.calls.map((c) => c[0]?.blobs)).toContainEqual(["internal_error", "erase_refused"]);
		expect(await tables(s.userId)).toContain("recipes");
		// A tombstone that has not expired yet is refused too.
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '1')");
		await deleteAccount(s);
		await alarm(s.userId);
		await expect(inObject(s.userId, (i) => i.erase(Date.now()))).rejects.toThrow(/erase refused/);
		expect(await tables(s.userId)).toEqual(["meta", "tasks"]);
	});

	it("a crash at any point of the final erasure step leaves either the full data or the tombstone", async () => {
		const s = await signIn("crashy");
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '1')");
		await sql(s.userId, "INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES ('r1', 1, 0, 1, '{}')");
		await deleteAccount(s);
		const full = await tables(s.userId);
		let crashedAt = 0;
		for (let k = 1; k < 60; k++) {
			const ok = await inObject(s.userId, async (instance: UserDO) => {
				const target = instance as unknown as { sql: SqlStorage };
				const real = target.sql;
				let n = 0;
				target.sql = new Proxy(real, {
					get: (t, p) => (p === "exec" ? (...a: Parameters<SqlStorage["exec"]>) => (++n === k ? (() => { throw new Error("injected crash"); })() : t.exec(...a)) : Reflect.get(t, p)),
				});
				try {
					await instance.erase(Date.now());
					return true;
				} catch {
					return false;
				} finally {
					target.sql = real;
				}
			});
			const now = await tables(s.userId);
			if (ok) {
				crashedAt = k;
				expect(now).toEqual(["meta", "tasks"]);
				break;
			}
			expect([k, now]).toEqual([k, full]);
			expect((await metaRows(s.userId)).state).toBe("deleting");
		}
		expect(crashedAt).toBeGreaterThan(5);
		expect((await metaRows(s.userId)).state).toBe("tombstone");
	});

	it("an abort right after the erasure transaction keeps the full data and the deletion task, which then completes", async () => {
		const s = await signIn("abort-after");
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_refunds', '1')");
		await deleteAccount(s);
		const full = await tables(s.userId);
		await inObject(s.userId, async (instance: UserDO, st) => {
			// An abort before the output gate opens rolls back the committed transaction with everything else (spike S11).
			const storage = st.storage as unknown as { setAlarm: unknown };
			storage.setAlarm = () => st.abort("crash before setAlarm");
			await instance.erase(Date.now());
		}).catch(() => undefined);
		expect(await tables(s.userId)).toEqual(full);
		expect((await metaRows(s.userId)).state).toBe("deleting");
		expect(await inObject(s.userId, (_i, st) => st.storage.getAlarm())).not.toBeNull();
		await alarm(s.userId);
		expect(await tables(s.userId)).toEqual(["meta", "tasks"]);
		expect(await inObject(s.userId, (_i, st) => st.storage.getAlarm())).toBe(Number((await metaRows(s.userId)).tombstone_until));
	});

	it("after erasure the object reloads and the migrations run cleanly on the minimum schema", async () => {
		const s = await signIn("remigrate");
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '1')");
		await deleteAccount(s);
		await alarm(s.userId);
		const back = await signIn("remigrate");
		expect(await inObject(back.userId, (_i, st) => Number(st.storage.sql.exec("SELECT value FROM meta WHERE key = 'schema_version'").one().value))).toBe(MIGRATIONS.length);
	});

	it("signing in again creates a fresh account with a different metrics id and no second free import (AC18, AC32)", async () => {
		const s = await signIn("returner");
		const firstMetrics = (await metaRows(s.userId)).metrics_id;
		await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '7')");
		await sql(s.userId, "INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES ('r1', 1, 0, 1, '{}')");
		await deleteAccount(s);
		await alarm(s.userId);
		const back = await signIn("returner");
		expect(back.userId).toBe(s.userId);
		const meta = await metaRows(back.userId);
		expect(meta.state).toBe("active");
		expect(meta.metrics_id).not.toBe(firstMetrics);
		expect(meta.tombstone_until).toBeUndefined();
		const me = (await (await call("/me", { token: back.token })).json()) as { quota: { freeImportUsed: boolean } };
		expect(me.quota.freeImportUsed).toBe(true);
		expect((await (await call("/recipes", { token: back.token })).json()) as { recipes: unknown[] }).toMatchObject({ recipes: [] });
		const tasks = await inObject(back.userId, (_i, st) => st.storage.sql.exec("SELECT kind FROM tasks").toArray().map((r) => r.kind));
		expect(tasks).not.toContain("tombstone_expiry");
	});

	it("an account without the free import used gets a fresh free import after deletion; an expired tombstone is wiped", async () => {
		const s = await signIn("no-tombstone");
		await deleteAccount(s);
		await alarm(s.userId);
		expect(await tables(s.userId)).toEqual([]);
		const back = await signIn("no-tombstone");
		expect(((await (await call("/me", { token: back.token })).json()) as { quota: { freeImportUsed: boolean } }).quota.freeImportUsed).toBe(false);

		const t = await signIn("expiring");
		await sql(t.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '1')");
		await deleteAccount(t);
		await alarm(t.userId);
		const until = Number((await metaRows(t.userId)).tombstone_until);
		await inObject(t.userId, (i) => i.erase(until));
		expect(await tables(t.userId)).toEqual([]);
		expect(await inObject(t.userId, (_i, st) => st.storage.getAlarm())).toBeNull();
	});
});

describe("security review M6 fixes", () => {
	const onlyTombstoneKeys = async (userId: string) => {
		const keys = Object.keys(await metaRows(userId));
		expect(keys.filter((k) => !(TOMBSTONE_KEYS as readonly string[]).includes(k))).toEqual([]);
	};

	it("finding 1: a suspended user who deletes and signs in again is still suspended with the same reason", async () => {
		const s = await signIn("escaper");
		await userStub(s.userId).setSuspended("abusive_content", "op@example.org");
		await deleteAccount(s);
		await alarm(s.userId);
		expect(await metaRows(s.userId)).toMatchObject({ state: "tombstone", suspension_reason: "abusive_content" });
		const back = await signIn("escaper");
		const me = (await (await call("/me", { token: back.token })).json()) as { state: string; suspension: { reason: string } };
		expect(me).toMatchObject({ state: "suspended", suspension: { reason: "abusive_content" } });
		expect((await call("/me/consent", { method: "DELETE", token: back.token })).status).toBe(403);
	});

	it("finding 11: deleting and signing in again keeps this month's quota count", async () => {
		const s = await signIn("quota-reset");
		const period = new Date().toISOString().slice(0, 7);
		await sql(s.userId, "INSERT INTO usage (period, count) VALUES (?, 3)", period);
		await deleteAccount(s);
		await alarm(s.userId);
		expect(JSON.parse((await metaRows(s.userId)).month_usage)).toEqual({ period, count: 3 });
		const back = await signIn("quota-reset");
		const me = (await (await call("/me", { token: back.token })).json()) as { quota: { monthUsed: number } };
		expect(me.quota.monthUsed).toBe(3);
		expect((await metaRows(back.userId)).month_usage).toBeUndefined();
	});

	describe("finding 2: third-party steps never hold the erasure longer than 7 days", () => {
		const overdue = (userId: string) => sql(userId, "UPDATE meta SET value = ? WHERE key = 'deletion_started_at'", String(Date.now() - THIRD_PARTY_DEADLINE_MS - 1));
		const abandoned = (calls: unknown[][]) => calls.map((c) => (c[0] as { blobs?: string[] }).blobs).filter((b) => b?.[1] === "third_party_abandoned");

		it("RevenueCat 401 or 403 keeps retrying within 7 days, then the erasure runs with an abandonment metric", async () => {
			for (const status of [401, 403]) {
				const s = await signIn(`rc-${status}`);
				rcDelete = () => status;
				await deleteAccount(s);
				await alarm(s.userId);
				expect((await metaRows(s.userId)).state).toBe("deleting");
				await overdue(s.userId);
				await sql(s.userId, "UPDATE tasks SET due_at = 0");
				const write = vi.spyOn(env.METRICS, "writeDataPoint");
				await alarm(s.userId);
				expect(await tables(s.userId)).toEqual([]);
				expect(abandoned(write.mock.calls)).toEqual([["account_deletion", "third_party_abandoned", "revenuecat"]]);
				write.mockRestore();
			}
		});

		it("an empty REVENUECAT_PROJECT_ID or missing Apple key config is abandoned after 7 days too; 404 counts as done", async () => {
			const s = await appleSignIn("no-config", "code-nc");
			await alarm(s.userId);
			testEnv.REVENUECAT_PROJECT_ID = "";
			testEnv.APPLE_TEAM_ID = "";
			await deleteAccount(s);
			await alarm(s.userId);
			expect((await metaRows(s.userId)).state).toBe("deleting");
			await overdue(s.userId);
			await sql(s.userId, "UPDATE tasks SET due_at = 0");
			const write = vi.spyOn(env.METRICS, "writeDataPoint");
			await alarm(s.userId);
			expect(await tables(s.userId)).toEqual([]);
			expect(abandoned(write.mock.calls)).toEqual([
				["account_deletion", "third_party_abandoned", "revenuecat"],
				["account_deletion", "third_party_abandoned", "apple"],
			]);
			write.mockRestore();
			testEnv.REVENUECAT_PROJECT_ID = "proj1";
			const t = await signIn("rc-404");
			rcDelete = () => 404;
			await deleteAccount(t);
			await alarm(t.userId);
			expect(await tables(t.userId)).toEqual([]);
		});
	});

	it("finding 3: the app's and the web page's Apple refresh tokens are both revoked", async () => {
		const s = await appleSignIn("two-tokens", "app-code");
		await alarm(s.userId);
		appleToken = () => Response.json({ refresh_token: "web-refresh" });
		const n = await nonce();
		const started = await userStub(s.userId).deleteAccountWeb({
			nonceId: n.split(".")[0],
			nonceExpiresAt: Date.now() + 300_000,
			apple: { code: "web-code", clientId: "web.services.id", redirectUri: "https://recipe.example/account/delete" },
		});
		expect(started).toMatchObject({ status: 202 });
		hits = [];
		await alarm(s.userId);
		const revoked = hits
			.filter((h) => h.url.endsWith("/auth/revoke"))
			.map((h) => new URLSearchParams(h.body))
			.map((f) => [f.get("client_id"), f.get("token")]);
		expect(revoked.sort()).toEqual([
			["local.test.apple", "apple-refresh-1"],
			["web.services.id", "web-refresh"],
		]);
		expect(new URLSearchParams(hits.find((h) => h.url.endsWith("/auth/token"))!.body).get("redirect_uri")).toBe("https://recipe.example/account/delete");
		expect(await tables(s.userId)).toEqual([]);
		appleToken = () => Response.json({ refresh_token: "apple-refresh-1" });
	});

	describe("item 12: a request that deletion overtakes during its await writes nothing", () => {
		type Kind = "import" | "translation" | "image" | "share" | "accept" | "pdf";
		const body = (v: unknown, headers: Record<string, string> = {}) => ({ body: JSON.stringify(v), headers: { "content-type": "application/json", ...headers } });

		/** An account with a recipe, consent, the free import used (so a tombstone is written) and a due entitlement re-read. */
		async function prepared(sub: string) {
			const s = await signIn(sub);
			const recipeId = uuid();
			await call(`/recipes/${recipeId}`, { method: "PUT", token: s.token, ...body({ ...inputFixture, id: recipeId, imageIds: [] }, { "if-none-match": "*" }) });
			await call("/me/consent", { method: "PUT", token: s.token, ...body({ version: 1 }) });
			await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('free_import_used_at', '1')");
			await sql(s.userId, "INSERT INTO meta (key, value) VALUES ('entitlement', ?)", JSON.stringify({ active: true, expiresAt: new Date(Date.now() + DAY_MS).toISOString() }));
			return { s, recipeId };
		}

		async function sharedByOther() {
			const o = await signIn(`owner-${uuid()}`);
			const recipeId = uuid();
			await call(`/recipes/${recipeId}`, { method: "PUT", token: o.token, ...body({ ...inputFixture, id: recipeId, imageIds: [] }, { "if-none-match": "*" }) });
			const shareId = "ab".repeat(16);
			await sql(o.userId, "INSERT INTO shares (share_id, recipe_id, created_at, expires_at) VALUES (?, ?, ?, ?)", shareId, recipeId, Date.now(), Date.now() + DAY_MS);
			return newShareToken(o.userId, shareId, env);
		}

		function late(kind: Kind, s: Session, recipeId: string, shareToken: string): Promise<Response> {
			switch (kind) {
				case "import":
					return call("/imports", { method: "POST", token: s.token, ...body({ kind: "text", text: "Soup" }, { "idempotency-key": uuid() }) });
				case "translation":
					return call(`/recipes/${recipeId}/translations`, { method: "POST", token: s.token, ...body({ language: "en" }, { "idempotency-key": uuid() }) });
				case "image":
					return call(`/recipes/${recipeId}/images/${uuid()}`, { method: "PUT", token: s.token, body: new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1]), headers: { "content-type": "image/jpeg" } });
				case "share":
					return call(`/recipes/${recipeId}/shares`, { method: "POST", token: s.token });
				case "accept":
					return call("/shares/accept", { method: "POST", token: s.token, ...body({ shareToken }) });
				case "pdf":
					return call(`/recipes/${recipeId}/pdf`, { method: "GET", token: s.token });
			}
		}

		async function overtake(kinds: Kind[], sub: string) {
			const { s, recipeId } = await prepared(sub);
			const shareToken = await sharedByOther();
			let open: () => void = () => undefined;
			v1Gate = new Promise((r) => (open = r));
			v1Waiting = 0;
			let pdfStarted = 0;
			let pdfOpen: () => void = () => undefined;
			const pdfGate = new Promise<void>((r) => (pdfOpen = r));
			(env as unknown as { BROWSER?: unknown }).BROWSER = {
				quickAction: async () => {
					pdfStarted++;
					await pdfGate;
					return new Response(new Uint8Array([37, 80, 68, 70]), { headers: { "content-type": "application/pdf" } });
				},
			};
			const pending = kinds.map((k) => late(k, s, recipeId, shareToken));
			const viaRevenueCat = kinds.filter((k) => k !== "pdf").length;
			await vi.waitFor(() => expect([v1Waiting >= Math.min(viaRevenueCat, 1), pdfStarted >= (kinds.includes("pdf") ? 1 : 0)]).toEqual([true, true]), { timeout: 5000, interval: 10 });
			// Deletion starts and finishes while the requests wait (another session of the same user deletes).
			const other = await signIn(sub);
			expect((await deleteAccount(other)).status).toBe(202);
			await alarm(s.userId);
			open();
			pdfOpen();
			v1Gate = null;
			const results = await Promise.all(pending);
			(env as unknown as { BROWSER?: unknown }).BROWSER = undefined;
			return { s, statuses: results.map((r) => r.status) };
		}

		for (const kind of ["import", "translation", "image", "share", "accept", "pdf"] as Kind[]) {
			it(`${kind}: answers 401 and the object holds only the plain tombstone`, async () => {
				const { s, statuses } = await overtake([kind], `late-${kind}`);
				expect(statuses).toEqual([401]);
				expect(await tables(s.userId)).toEqual(["meta", "tasks"]);
				await onlyTombstoneKeys(s.userId);
				expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT kind FROM tasks").toArray().map((r) => r.kind))).toEqual(["tombstone_expiry"]);
			});
		}

		it("guard: a randomised mix of late calls never leaves a meta key outside the tombstone keys", { timeout: 30_000 }, async () => {
			const all: Kind[] = ["import", "translation", "image", "share", "accept", "pdf"];
			for (let round = 0; round < 3; round++) {
				const kinds = all.filter(() => Math.random() < 0.7);
				if (kinds.length === 0) kinds.push("import");
				kinds.sort(() => Math.random() - 0.5);
				const { s } = await overtake(kinds, `late-mix-${round}`);
				expect([round, kinds, await tables(s.userId)]).toEqual([round, kinds, ["meta", "tasks"]]);
				await onlyTombstoneKeys(s.userId);
			}
		});
	});
});
