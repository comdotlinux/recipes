import { DurableObject } from "cloudflare:workers";

/** One entry per schema version; index 0 brings an empty object to version 1. Never edit a shipped entry, append a new one. */
export type Migration = (sql: SqlStorage) => void;

export const MIGRATIONS: Migration[] = [
	(sql) => {
		// IF NOT EXISTS: the erasure step may already have re-created meta and tasks for the tombstone (plan 4.7).
		sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
		sql.exec(
			"CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, kind TEXT NOT NULL, due_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, payload TEXT)",
		);
	},
];

// GLOB, not LIKE: "_" is a wildcard in LIKE. _cf_* tables are internal to the runtime.
const USER_TABLES = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*'";

export function rowCounts(sql: SqlStorage): Map<string, number> {
	const names = sql.exec<{ name: string }>(USER_TABLES).toArray();
	return new Map(names.map(({ name }) => [name, sql.exec<{ n: number }>(`SELECT count(*) AS n FROM "${name}"`).one().n]));
}

export function schemaVersion(sql: SqlStorage): number {
	if (sql.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'").toArray().length === 0) return 0;
	const row = sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'schema_version'").toArray()[0];
	return row ? Number(row.value) : 0;
}

/**
 * Applies the pending migrations in one transaction. Every table that existed before must keep its row count;
 * otherwise the transaction rolls back and the old schema stays as it was.
 * shortcut: no migration may change row counts yet; add a per-migration expected delta when one has to.
 */
export function migrate(storage: DurableObjectStorage, migrations: Migration[] = MIGRATIONS): number {
	const sql = storage.sql;
	const from = schemaVersion(sql);
	if (from >= migrations.length) return from;
	storage.transactionSync(() => {
		const before = rowCounts(sql);
		for (const m of migrations.slice(from)) m(sql);
		const after = rowCounts(sql);
		for (const [table, n] of before) {
			if (after.get(table) !== n) {
				throw new Error(`migration ${from}->${migrations.length} aborted: ${table} had ${n} rows, now ${after.get(table)}`);
			}
		}
		sql.exec(
			"INSERT INTO meta (key, value) VALUES ('schema_version', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
			String(migrations.length),
		);
	});
	return migrations.length;
}

export class UserDO extends DurableObject<Env> {
	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		void ctx.blockConcurrencyWhile(async () => {
			migrate(ctx.storage);
		});
	}
}
