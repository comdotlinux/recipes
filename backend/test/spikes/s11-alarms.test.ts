/**
 * Spike S11: alarm and storage behaviour of SQLite-backed Durable Objects, as observed in local workerd.
 * Run with `bun run test:spikes` (not part of the default `bun run test`); docs/spikes/S11.md lists what still needs a real deployment.
 */
import { env, runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it, vi } from "vitest";
import type { SpikeDO } from "./spike-do";

const spike = (name: string) => env.SPIKE_DO.get(env.SPIKE_DO.idFromName(name));
const userTables = (sql: SqlStorage) =>
	sql
		.exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*' ORDER BY name")
		.toArray()
		.map((r) => r.name);

describe("S11 (a) setAlarm replaces the pending alarm", () => {
	it("keeps one alarm slot holding the last time set", async () => {
		const stub = spike("a");
		const first = Date.now() + 60_000;
		const second = Date.now() + 120_000;
		await runInDurableObject(stub, async (_i: SpikeDO, state) => {
			await state.storage.put("mode", "ok");
			await state.storage.setAlarm(first);
			await state.storage.setAlarm(second);
			expect(await state.storage.getAlarm()).toBe(second);
		});
		expect(await runDurableObjectAlarm(stub)).toBe(true);
		expect(await runDurableObjectAlarm(stub)).toBe(false); // nothing left: the first time was not queued separately
		expect((await stub.events()).filter((e) => e.event === "alarm")).toHaveLength(1);
	});
});

describe("S11 (b) a thrown alarm handler is retried", () => {
	it("runs the handler again with isRetry and retryCount 1", { timeout: 30_000 }, async () => {
		const stub = spike("b");
		await stub.arm("throw-once", 10);
		await vi.waitFor(async () => expect((await stub.events()).filter((e) => e.event === "alarm")).toHaveLength(2), {
			timeout: 25_000,
			interval: 200,
		});
		const [first, retry] = (await stub.events()).filter((e) => e.event === "alarm");
		expect(first.detail).toBe(0);
		expect(retry.detail).toBe(1);
		expect(retry.at - first.at).toBeGreaterThanOrEqual(1_500); // backoff, documented as starting at 2 s
	});
});

describe("S11 (c) deleteAll() and writes issued after it without an await", () => {
	it("deleteAll() drops tables, not only rows, and also clears the alarm", async () => {
		await runInDurableObject(spike("c1"), async (_i: SpikeDO, state) => {
			state.storage.sql.exec("CREATE TABLE recipes (id TEXT PRIMARY KEY)");
			state.storage.sql.exec("INSERT INTO recipes VALUES ('r1')");
			await state.storage.setAlarm(Date.now() + 60_000);
			await state.storage.deleteAll();
			expect(userTables(state.storage.sql)).toEqual([]);
			expect(await state.storage.getAlarm()).toBeNull();
		});
	});

	it("writes issued synchronously after deleteAll() survive it and land together", async () => {
		const stub = spike("c2");
		await runInDurableObject(stub, async (_i: SpikeDO, state) => {
			const sql = state.storage.sql;
			sql.exec("CREATE TABLE recipes (id TEXT PRIMARY KEY)");
			sql.exec("INSERT INTO recipes VALUES ('r1')");
			const wiped = state.storage.deleteAll();
			sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
			sql.exec("INSERT INTO meta (key, value) VALUES ('state', 'tombstone')");
			await wiped;
		});
		await runInDurableObject(stub, async (_i: SpikeDO, state) => {
			expect(userTables(state.storage.sql)).toEqual(["meta"]);
			expect(state.storage.sql.exec("SELECT value FROM meta WHERE key = 'state'").one().value).toBe("tombstone");
		});
	});

	const seed = async (name: string) => {
		const stub = spike(name);
		await runInDurableObject(stub, async (_i: SpikeDO, state) => {
			state.storage.sql.exec("CREATE TABLE recipes (id TEXT PRIMARY KEY)");
			state.storage.sql.exec("INSERT INTO recipes VALUES ('r1')");
		});
		return stub;
	};
	const crash = (stub: DurableObjectStub<SpikeDO>, body: (state: DurableObjectState) => void) =>
		expect(
			runInDurableObject(stub, async (_i: SpikeDO, state) => {
				body(state);
				state.abort("spike: crash before the next await");
			}),
		).rejects.toThrow();
	// A stub is broken once its object aborted, so inspection goes through a new stub to the same object.
	const inspect = (name: string) =>
		runInDurableObject(spike(name), async (_i: SpikeDO, state) => {
			const tables = userTables(state.storage.sql);
			const tombstone = tables.includes("meta") && state.storage.sql.exec("SELECT count(*) AS n FROM meta WHERE value = 'tombstone'").one().n === 1;
			return { tables, tombstone };
		});

	it("control: plain unawaited writes are rolled back by an abort before the next await", async () => {
		const stub = await seed("c3");
		await crash(stub, (state) => {
			state.storage.sql.exec("DELETE FROM recipes");
			state.storage.sql.exec("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
		});
		expect(await inspect("c3")).toEqual({ tables: ["recipes"], tombstone: false });
	});

	it("FINDING: deleteAll() is not rolled back with the writes after it, so an abort leaves the wipe without the tombstone", async () => {
		const stub = await seed("c4");
		await crash(stub, (state) => {
			void state.storage.deleteAll();
			state.storage.sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
			state.storage.sql.exec("INSERT INTO meta (key, value) VALUES ('state', 'tombstone')");
		});
		// The plan's assumption fails in local workerd: data gone, tombstone lost.
		expect(await inspect("c4")).toEqual({ tables: [], tombstone: false });
	});

	it("fallback: one SQL transaction that drops every table and writes the tombstone is all-or-nothing", async () => {
		const wipe = (state: DurableObjectState) =>
			state.storage.transactionSync(() => {
				for (const t of userTables(state.storage.sql)) state.storage.sql.exec(`DROP TABLE "${t}"`);
				state.storage.sql.exec("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
				state.storage.sql.exec("INSERT INTO meta (key, value) VALUES ('state', 'tombstone')");
			});
		const crashed = await seed("c5");
		await crash(crashed, wipe);
		expect(await inspect("c5")).toEqual({ tables: ["recipes"], tombstone: false });

		const committed = await seed("c6");
		await runInDurableObject(committed, async (_i: SpikeDO, state) => wipe(state));
		expect(await inspect("c6")).toEqual({ tables: ["meta"], tombstone: true });
	});
});

describe("S11 (d) alarm-driven work finishes after the client disconnects", () => {
	it("completes the work even though the request that started it was cancelled", { timeout: 15_000 }, async () => {
		const stub = spike("d");
		const client = new AbortController();
		const pending = stub.fetch("https://spike/start", { signal: client.signal }).catch((e: unknown) => e);
		await vi.waitFor(async () => expect((await stub.events()).map((e) => e.event)).toContain("request-start"), { timeout: 5_000 });
		client.abort();
		expect(await pending).toBeInstanceOf(Error);
		await vi.waitFor(async () => expect((await stub.events()).map((e) => e.event)).toContain("work-done"), { timeout: 10_000, interval: 100 });
		// Locally the cancelled request's handler also runs to its end, after the alarm work.
		await vi.waitFor(async () => expect((await stub.events()).map((e) => e.event)).toEqual(["request-start", "alarm", "work-done", "request-end"]), {
			timeout: 5_000,
			interval: 100,
		});
	});
});
