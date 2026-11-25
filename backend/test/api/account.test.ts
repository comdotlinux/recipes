import { env, runDurableObjectAlarm } from "cloudflare:test";
import { exportPKCS8, generateKeyPair } from "jose";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MIGRATIONS } from "../../src/storage/migrations";
import { DAY_MS, TOMBSTONE_KEYS, TOMBSTONE_TTL_MS, type UserDO } from "../../src/user-do";
import { REVENUECAT, TURNSTILE_OK, call, inObject, interceptOutbound, json, nonce, providerToken, signIn, userStub, type Session } from "./helpers";

// ---- outbound: RevenueCat v2 customer delete and Apple token endpoints, answered per test ----

type Hit = { url: string; method: string; body: string; auth: string | null };
let hits: Hit[] = [];
let rcDelete: () => number = () => 200;
let appleToken: () => Response = () => Response.json({ refresh_token: "apple-refresh-1", access_token: "a", token_type: "bearer", expires_in: 3600, id_token: "x" });
let appleRevoke: () => number = () => 200;

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
		expect((await metaRows(s.userId)).apple_auth_code).toBeUndefined();
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
