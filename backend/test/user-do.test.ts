import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { MIGRATIONS, migrate, rowCounts, schemaVersion, type Migration } from "../src/user-do";

const tables = (sql: SqlStorage) =>
	sql
		.exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*' ORDER BY name")
		.toArray()
		.map((r) => r.name);

const stub = (name: string) => env.USER_DO.get(env.USER_DO.idFromName(name));

describe("UserDO migrations", () => {
	it("constructor creates meta and tasks and records the schema version", async () => {
		await runInDurableObject(stub("fresh"), async (_instance, state) => {
			expect(tables(state.storage.sql)).toEqual(["meta", "tasks"]);
			expect(schemaVersion(state.storage.sql)).toBe(MIGRATIONS.length);
		});
	});

	it("is idempotent and keeps rows", async () => {
		await runInDurableObject(stub("idem"), async (_instance, state) => {
			const sql = state.storage.sql;
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
			sql.exec("DROP TABLE meta");
			sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
			sql.exec("INSERT INTO meta (key, value) VALUES ('state', 'tombstone')");
			expect(schemaVersion(sql)).toBe(0);
			migrate(state.storage);
			expect(schemaVersion(sql)).toBe(MIGRATIONS.length);
			expect(sql.exec("SELECT value FROM meta WHERE key = 'state'").one().value).toBe("tombstone");
		});
	});

	it("a migration that changes row counts aborts and leaves the old schema intact", async () => {
		await runInDurableObject(stub("mismatch"), async (_instance, state) => {
			const sql = state.storage.sql;
			sql.exec("INSERT INTO tasks (id, kind, due_at) VALUES ('t1', 'expiry', 1), ('t2', 'expiry', 2)");
			const bad: Migration = (s) => {
				s.exec("CREATE TABLE recipes (id TEXT PRIMARY KEY)");
				s.exec("DELETE FROM tasks WHERE id = 't2'");
			};
			expect(() => migrate(state.storage, [...MIGRATIONS, bad])).toThrow(/tasks had 2 rows, now 1/);
			expect(tables(sql)).toEqual(["meta", "tasks"]);
			expect(schemaVersion(sql)).toBe(MIGRATIONS.length);
			expect(rowCounts(sql).get("tasks")).toBe(2);
		});
	});
});
