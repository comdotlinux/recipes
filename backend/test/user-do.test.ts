import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { MIGRATIONS, type Migration, migrate, rowCounts, schemaVersion } from "../src/storage/migrations";

const tables = (sql: SqlStorage) =>
	sql
		.exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*' ORDER BY name")
		.toArray()
		.map((r) => r.name);

const stub = (name: string) => env.USER_DO.get(env.USER_DO.idFromName(name));
const ALL = ["attempts", "daily_counters", "images", "jobs", "meta", "recipes", "reports", "sessions", "tasks", "translations", "usage", "used_nonces"];

/** Turns the object back into the M0 (v1) schema with some rows, as a storage fixture. */
function v1Fixture(storage: DurableObjectStorage) {
	const sql = storage.sql;
	for (const t of tables(sql)) sql.exec(`DROP TABLE "${t}"`);
	migrate(storage, MIGRATIONS.slice(0, 1));
	sql.exec("INSERT INTO meta (key, value) VALUES ('state', 'active'), ('rev', '0')");
	sql.exec("INSERT INTO tasks (id, kind, due_at) VALUES ('expiry', 'expiry', 1)");
}

describe("UserDO migrations (AC31)", () => {
	it("an id that never had a session gets no tables; migrate() creates the full schema", async () => {
		await runInDurableObject(stub("fresh"), async (_instance, state) => {
			expect(tables(state.storage.sql)).toEqual([]);
			migrate(state.storage);
			expect(tables(state.storage.sql)).toEqual(ALL);
			expect(schemaVersion(state.storage.sql)).toBe(MIGRATIONS.length);
		});
	});

	it("migrating the M0 (v1) fixture to the latest schema keeps every row count", async () => {
		await runInDurableObject(stub("from-v1"), async (_instance, state) => {
			v1Fixture(state.storage);
			expect(schemaVersion(state.storage.sql)).toBe(1);
			const before = rowCounts(state.storage.sql);
			expect(migrate(state.storage)).toBe(MIGRATIONS.length);
			const after = rowCounts(state.storage.sql);
			for (const [t, n] of before) expect([t, after.get(t)]).toEqual([t, n]);
			expect(tables(state.storage.sql)).toEqual(ALL);
		});
	});

	it("is idempotent and keeps rows", async () => {
		await runInDurableObject(stub("idem"), async (_instance, state) => {
			const sql = state.storage.sql;
			migrate(state.storage);
			sql.exec("INSERT INTO tasks (id, kind, due_at) VALUES ('t1', 'expiry', 1)");
			const before = rowCounts(sql);
			expect(migrate(state.storage)).toBe(MIGRATIONS.length);
			expect(migrate(state.storage)).toBe(MIGRATIONS.length);
			expect(rowCounts(sql)).toEqual(before);
		});
	});

	it("runs on top of the minimum tombstone schema left by erasure", async () => {
		await runInDurableObject(stub("tombstone"), async (_instance, state) => {
			const sql = state.storage.sql;
			for (const t of tables(sql)) sql.exec(`DROP TABLE "${t}"`);
			sql.exec("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
			sql.exec("INSERT INTO meta (key, value) VALUES ('state', 'tombstone')");
			expect(schemaVersion(sql)).toBe(0);
			migrate(state.storage);
			expect(schemaVersion(sql)).toBe(MIGRATIONS.length);
			expect(sql.exec("SELECT value FROM meta WHERE key = 'state'").one().value).toBe("tombstone");
		});
	});

	it("a simulated mismatch aborts and leaves the v1 fixture intact", async () => {
		await runInDurableObject(stub("mismatch"), async (_instance, state) => {
			const sql = state.storage.sql;
			v1Fixture(state.storage);
			const before = rowCounts(sql);
			const bad: Migration = (s) => {
				s.exec("CREATE TABLE extra (id TEXT PRIMARY KEY)");
				s.exec("DELETE FROM tasks");
			};
			expect(() => migrate(state.storage, [...MIGRATIONS, bad])).toThrow(/tasks had 1 rows, now 0/);
			expect(tables(sql)).toEqual(["meta", "tasks"]);
			expect(schemaVersion(sql)).toBe(1);
			expect(rowCounts(sql)).toEqual(before);
		});
	});
});
