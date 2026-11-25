/** One entry per schema version; index 0 brings an empty object to version 1. Never edit a shipped entry, append a new one. */
export type Migration = (sql: SqlStorage) => void;

export const MIGRATIONS: Migration[] = [
	// v1 (M0)
	(sql) => {
		// IF NOT EXISTS: the erasure step may already have re-created meta and tasks for the tombstone (plan 4.7).
		sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
		sql.exec(
			"CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, kind TEXT NOT NULL, due_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, payload TEXT)",
		);
	},
	// v2 (M1): sessions, nonces, recipes, images, request counters
	(sql) => {
		sql.exec("CREATE TABLE sessions (token_hash TEXT PRIMARY KEY, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, platform TEXT)");
		sql.exec("CREATE TABLE used_nonces (nonce_id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)");
		sql.exec(
			"CREATE TABLE recipes (id TEXT PRIMARY KEY, rev INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, json TEXT)",
		);
		sql.exec("CREATE INDEX recipes_rev ON recipes (rev)");
		sql.exec(
			"CREATE TABLE images (id TEXT PRIMARY KEY, recipe_id TEXT NOT NULL, mime TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL, screened_ok INTEGER)",
		);
		sql.exec("CREATE INDEX images_recipe ON images (recipe_id)");
		sql.exec("CREATE TABLE daily_counters (day TEXT NOT NULL, key TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (day, key))");
	},
	// v3 (M2): import jobs, monthly usage, daily counted attempts, reports
	(sql) => {
		sql.exec(
			`CREATE TABLE jobs (job_key TEXT PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL, input TEXT, input_blob BLOB,
			 created_at INTEGER NOT NULL, ended_at INTEGER, result_json TEXT, charge TEXT, period TEXT)`,
		);
		sql.exec("CREATE TABLE usage (period TEXT PRIMARY KEY, count INTEGER NOT NULL)");
		sql.exec("CREATE TABLE attempts (day TEXT PRIMARY KEY, count INTEGER NOT NULL)");
		// One report per job and kind (F2): reports cannot pile up.
		sql.exec(
			"CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, job_key TEXT, reason TEXT NOT NULL, note TEXT NOT NULL, UNIQUE (job_key, kind))",
		);
	},
	// v4 (M4): one translation per recipe and language; stale once the source recipe's content changed
	(sql) => {
		sql.exec(
			"CREATE TABLE translations (recipe_id TEXT NOT NULL, lang TEXT NOT NULL, stale INTEGER NOT NULL DEFAULT 0, json TEXT NOT NULL, PRIMARY KEY (recipe_id, lang))",
		);
	},
	// v5 (M5): share links and their recipients (a hash of each recipient's user id, never the id itself)
	(sql) => {
		sql.exec(
			"CREATE TABLE shares (share_id TEXT PRIMARY KEY, recipe_id TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)",
		);
		sql.exec("CREATE INDEX shares_created ON shares (created_at)");
		sql.exec(
			"CREATE TABLE share_accepts (share_id TEXT NOT NULL, recipient_hash TEXT NOT NULL, accepted_at INTEGER NOT NULL, PRIMARY KEY (share_id, recipient_hash))",
		);
	},
];

// GLOB, not LIKE: "_" is a wildcard in LIKE. _cf_* tables are internal to the runtime.
const USER_TABLES = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*'";

export function rowCounts(sql: SqlStorage): Map<string, number> {
	const names = sql.exec<{ name: string }>(USER_TABLES).toArray();
	return new Map(names.map(({ name }) => [name, sql.exec<{ n: number }>(`SELECT count(*) AS n FROM "${name}"`).one().n]));
}

/** Drops every user table. Only the erasure step (user-do.ts erase) calls it, inside its transaction. */
export function dropUserTables(sql: SqlStorage): void {
	for (const { name } of sql.exec<{ name: string }>(USER_TABLES).toArray()) sql.exec(`DROP TABLE "${name}"`);
}

/** The account state in meta, or null when there is no meta table or no state (no account). */
export function accountState(sql: SqlStorage): string | null {
	if (sql.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'").toArray().length === 0) return null;
	return sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'state'").toArray()[0]?.value ?? null;
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
