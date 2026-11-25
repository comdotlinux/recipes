import { DurableObject } from "cloudflare:workers";
import { type Result, error } from "./http";
import { FREE_CAP_BYTES, SUBSCRIBER_CAP_BYTES } from "./images";
import { userEvent } from "./metrics";
import { MAX_IMAGES_PER_RECIPE, MAX_RECIPES, type RecipeInput, isoTime, toRecipe } from "./recipes";
import { migrate, rowCounts, schemaVersion } from "./storage/migrations";
import { armAlarm, runDueTasks, schedule } from "./storage/tasks";

export const DAY_MS = 86_400_000;
export const SESSION_TTL_MS = 30 * DAY_MS;
export const MARKER_TTL_MS = 90 * DAY_MS;
export const COUNTER_DAYS = 90;
export const MONTH_LIMIT = 30;
export const PAGE_SIZE = 200;
export const MAX_SESSIONS = 10;

type RecipeRow = { id: string; rev: number; deleted: number; updated_at: number; json: string | null };

const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/** One instance per user, named by the user id. The only stateful component. */
export class UserDO extends DurableObject<Env> {
	private readonly sql: SqlStorage;

	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		this.sql = ctx.storage.sql;
		void ctx.blockConcurrencyWhile(async () => {
			// An id that never had a session gets no tables: only createSession() makes the schema.
			// When account erasure lands (plan 4.7): a tombstone keeps only meta and tasks, so check the erased state here
			// before migrating, or the user tables would be recreated for an erased account.
			if (rowCounts(this.sql).size === 0) return;
			migrate(ctx.storage);
			// Repair: a task row without a matching alarm (for example after a crash between both writes) gets its alarm back.
			await armAlarm(ctx.storage);
		});
	}

	private meta(key: string): string | null {
		return this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null;
	}

	private setMeta(key: string, value: string): void {
		this.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value);
	}

	private event(e: Parameters<typeof userEvent>[2]): void {
		const id = this.meta("metrics_id");
		if (id) userEvent(this.env, id, e);
	}

	/** Valid session: extends it at most once per day. Deleting accounts accept no session (plan 4.7). */
	private authenticate(secretHash: string, now: number): boolean {
		if (schemaVersion(this.sql) === 0) return false;
		if (this.meta("state") === "deleting") return false;
		const row = this.sql.exec<{ expires_at: number }>("SELECT expires_at FROM sessions WHERE token_hash = ?", secretHash).toArray()[0];
		if (!row) return false;
		if (row.expires_at <= now) {
			this.sql.exec("DELETE FROM sessions WHERE token_hash = ?", secretHash);
			return false;
		}
		if (now - (row.expires_at - SESSION_TTL_MS) >= DAY_MS) {
			this.sql.exec("UPDATE sessions SET expires_at = ? WHERE token_hash = ?", now + SESSION_TTL_MS, secretHash);
		}
		return true;
	}

	private async run(group: string, secretHash: string, op: (now: number) => Result | Promise<Result>, write = false): Promise<Result> {
		const now = Date.now();
		if (!this.authenticate(secretHash, now)) return error(401, "unauthenticated", "missing, unknown or expired session");
		// Per-user write limit, applied only after the session is accepted; the object id stands for the user.
		const limited = write && !(await this.env.RL_USER.limit({ key: this.ctx.id.toString() })).success;
		// The await above lets other requests in; a DELETE /session in between must win.
		if (write && !this.authenticate(secretHash, now)) return error(401, "unauthenticated", "session revoked");
		const result = limited ? error(429, "rate_limited", "too many writes") : await op(now);
		this.sql.exec(
			"INSERT INTO daily_counters (day, key, count) VALUES (?, ?, 1) ON CONFLICT (day, key) DO UPDATE SET count = count + 1",
			day(now),
			group,
		);
		this.event({ event: "request", group, status: result.status });
		return result;
	}

	private nextRev(): number {
		const rev = Number(this.meta("rev") ?? "0") + 1;
		this.setMeta("rev", String(rev));
		return rev;
	}

	private capBytes(): number {
		const entitlement = JSON.parse(this.meta("entitlement") ?? '{"active":false}') as { active: boolean };
		return entitlement.active ? SUBSCRIBER_CAP_BYTES : FREE_CAP_BYTES;
	}

	private usedBytes(exceptImageId = ""): number {
		return this.sql.exec<{ n: number }>("SELECT coalesce(sum(size), 0) AS n FROM images WHERE id != ?", exceptImageId).one().n;
	}

	// ---- session ----

	async createSession(a: { nonceId: string; nonceExpiresAt: number; secretHash: string; platform: string }): Promise<Result> {
		const now = Date.now();
		migrate(this.ctx.storage);
		if (this.meta("state") === "deleting") return error(409, "account_deleting", "account deletion is running");
		const used = this.sql.exec("INSERT INTO used_nonces (nonce_id, expires_at) VALUES (?, ?) ON CONFLICT DO NOTHING", a.nonceId, a.nonceExpiresAt);
		if (used.rowsWritten === 0) return error(401, "invalid_nonce", "nonce already used");
		if (this.meta("state") === null) {
			this.setMeta("state", "active");
			this.setMeta("metrics_id", crypto.randomUUID());
			this.setMeta("created_at", String(now));
			this.event({ event: "account_created" });
		}
		const expiresAt = now + SESSION_TTL_MS;
		this.sql.exec("INSERT INTO sessions (token_hash, created_at, expires_at, platform) VALUES (?, ?, ?, ?)", a.secretHash, now, expiresAt, a.platform);
		// At most MAX_SESSIONS per user: the oldest go, in the same synchronous step (no await in between).
		this.sql.exec(
			"DELETE FROM sessions WHERE token_hash NOT IN (SELECT token_hash FROM sessions ORDER BY created_at DESC, rowid DESC LIMIT ?)",
			MAX_SESSIONS,
		);
		await this.scheduleExpiry(Math.min(a.nonceExpiresAt, expiresAt));
		this.event({ event: "session_created" });
		return { status: 200, body: { expiresAt: isoTime(expiresAt) } };
	}

	deleteSession(secretHash: string): Promise<Result> {
		return this.run("session", secretHash, () => {
			this.sql.exec("DELETE FROM sessions WHERE token_hash = ?", secretHash);
			return { status: 204 };
		});
	}

	me(secretHash: string, userId: string): Promise<Result> {
		return this.run("me", secretHash, () => {
			const state = this.meta("state") ?? "active";
			const entitlement = JSON.parse(this.meta("entitlement") ?? '{"active":false,"expiresAt":null}') as { active: boolean; expiresAt: string | null };
			const consent = this.meta("consent_version");
			return {
				status: 200,
				body: {
					userId,
					state,
					suspension: state === "suspended" ? { reason: this.meta("suspension_reason") } : null,
					entitlement: { active: entitlement.active, expiresAt: entitlement.expiresAt },
					// monthUsed: imports and their usage table arrive in M2; until then no import can have been counted.
					quota: { freeImportUsed: this.meta("free_import_used_at") !== null, monthUsed: 0, monthLimit: MONTH_LIMIT },
					storage: { usedBytes: this.usedBytes(), capBytes: this.capBytes() },
					consentVersion: consent === null ? null : Number(consent),
				},
			};
		});
	}

	// ---- recipes ----

	listRecipes(secretHash: string, since: number): Promise<Result> {
		return this.run("recipes", secretHash, () => {
			if (since > 0 && since < Number(this.meta("purged_rev") ?? "0")) {
				return error(410, "cursor_expired", "deletion markers after this cursor were purged; pull with since=0");
			}
			const rows = this.sql
				.exec<RecipeRow>("SELECT id, rev, deleted, updated_at, json FROM recipes WHERE rev > ? ORDER BY rev LIMIT ?", since, PAGE_SIZE + 1)
				.toArray();
			const more = rows.length > PAGE_SIZE;
			const page = rows.slice(0, PAGE_SIZE);
			const rev = more ? page[page.length - 1].rev : Number(this.meta("rev") ?? "0");
			return { status: 200, body: { recipes: page.map(toRecipe), rev, more } };
		});
	}

	putRecipe(secretHash: string, input: RecipeInput, precondition: { create: true } | { ifMatch: number | null }): Promise<Result> {
		return this.run("recipes", secretHash, (now) => {
			const existing = this.sql.exec<RecipeRow>("SELECT id, rev, deleted, updated_at, json FROM recipes WHERE id = ?", input.id).toArray()[0];
			// An update of an id this user never had is a 404, like every other id that is not theirs (AC5).
			if (!("create" in precondition) && existing === undefined) return error(404, "not_found", "recipe not found");
			const live = existing !== undefined && existing.deleted === 0;
			if ("create" in precondition ? live : !live || existing.rev !== precondition.ifMatch) {
				return error(412, "precondition_failed", "create on an existing recipe, or stale rev");
			}
			if (!live && this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM recipes WHERE deleted = 0").one().n >= MAX_RECIPES) {
				return error(409, "recipe_limit", `at most ${MAX_RECIPES} recipes`);
			}
			const stored = new Set(this.sql.exec<{ id: string }>("SELECT id FROM images WHERE recipe_id = ?", input.id).toArray().map((r) => r.id));
			if (input.imageIds.some((id) => !stored.has(id))) return error(400, "invalid_body", "imageIds lists an image not uploaded to this recipe");
			for (const id of stored) if (!input.imageIds.includes(id)) this.sql.exec("DELETE FROM images WHERE id = ?", id);
			const rev = this.nextRev();
			this.sql.exec(
				"INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, ?, 0, ?, ?) ON CONFLICT (id) DO UPDATE SET rev = excluded.rev, deleted = 0, updated_at = excluded.updated_at, json = excluded.json",
				input.id,
				rev,
				now,
				JSON.stringify(input),
			);
			return { status: 200, body: toRecipe({ id: input.id, rev, deleted: 0, updated_at: now, json: JSON.stringify(input) }) };
		}, true);
	}

	deleteRecipe(secretHash: string, id: string): Promise<Result> {
		return this.run("recipes", secretHash, async (now) => {
			const existing = this.sql.exec<{ deleted: number }>("SELECT deleted FROM recipes WHERE id = ?", id).toArray()[0];
			if (!existing) return error(404, "not_found", "recipe not found");
			if (existing.deleted === 0) {
				this.sql.exec("UPDATE recipes SET rev = ?, deleted = 1, updated_at = ?, json = NULL WHERE id = ?", this.nextRev(), now, id);
				this.sql.exec("DELETE FROM images WHERE recipe_id = ?", id);
				await this.scheduleExpiry(now + MARKER_TTL_MS);
			}
			return { status: 204 };
		}, true);
	}

	// ---- images ----

	putImage(secretHash: string, recipeId: string, imageId: string, mime: string, bytes: ArrayBuffer): Promise<Result> {
		return this.run("images", secretHash, () => {
			if (!this.sql.exec("SELECT 1 FROM recipes WHERE id = ? AND deleted = 0", recipeId).toArray().length) return error(404, "not_found", "recipe not found");
			const owner = this.sql.exec<{ recipe_id: string }>("SELECT recipe_id FROM images WHERE id = ?", imageId).toArray()[0];
			if (owner && owner.recipe_id !== recipeId) return error(404, "not_found", "image belongs to another recipe");
			const count = this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM images WHERE recipe_id = ? AND id != ?", recipeId, imageId).one().n;
			if (count >= MAX_IMAGES_PER_RECIPE) return error(409, "image_limit", `at most ${MAX_IMAGES_PER_RECIPE} images per recipe`);
			if (this.usedBytes(imageId) + bytes.byteLength > this.capBytes()) return error(413, "storage_cap", "storage cap reached");
			this.sql.exec(
				"INSERT INTO images (id, recipe_id, mime, bytes, size) VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET mime = excluded.mime, bytes = excluded.bytes, size = excluded.size",
				imageId,
				recipeId,
				mime,
				bytes,
				bytes.byteLength,
			);
			return { status: 201, body: { id: imageId, size: bytes.byteLength, mime } };
		}, true);
	}

	getImage(secretHash: string, imageId: string): Promise<Result> {
		return this.run("images", secretHash, () => {
			const row = this.sql.exec<{ mime: string; bytes: ArrayBuffer }>("SELECT mime, bytes FROM images WHERE id = ?", imageId).toArray()[0];
			if (!row) return error(404, "not_found", "image not found");
			return { status: 200, bytes: row.bytes, headers: {
					"content-type": row.mime,
					"cache-control": "private, immutable, max-age=31536000",
					"x-content-type-options": "nosniff",
					"content-security-policy": "sandbox",
				},
			};
		});
	}

	// ---- tasks ----

	private scheduleExpiry(dueAt: number): Promise<void> {
		return schedule(this.ctx.storage, { id: "expiry", kind: "expiry", dueAt }, true);
	}

	/** Drops expired sessions, nonces, request counters and deletion markers older than 90 days, then re-arms for the next expiry. */
	async expire(now: number): Promise<void> {
		this.sql.exec("DELETE FROM sessions WHERE expires_at <= ?", now);
		this.sql.exec("DELETE FROM used_nonces WHERE expires_at <= ?", now);
		this.sql.exec("DELETE FROM daily_counters WHERE day < ?", day(now - COUNTER_DAYS * DAY_MS));
		const horizon = now - MARKER_TTL_MS;
		const purged = this.sql.exec<{ rev: number | null }>("SELECT max(rev) AS rev FROM recipes WHERE deleted = 1 AND updated_at <= ?", horizon).one().rev;
		if (purged !== null) {
			this.setMeta("purged_rev", String(Math.max(purged, Number(this.meta("purged_rev") ?? "0"))));
			this.sql.exec("DELETE FROM recipes WHERE deleted = 1 AND updated_at <= ?", horizon);
		}
		const next = this.sql
			.exec<{ at: number | null }>(
				`SELECT min(at) AS at FROM (
					SELECT min(expires_at) AS at FROM sessions UNION ALL
					SELECT min(expires_at) FROM used_nonces UNION ALL
					SELECT min(updated_at) + ? FROM recipes WHERE deleted = 1)`,
				MARKER_TTL_MS,
			)
			.one().at;
		if (next !== null) await schedule(this.ctx.storage, { id: "expiry", kind: "expiry", dueAt: next });
	}

	async alarm(): Promise<void> {
		await runDueTasks(this.ctx.storage, { expiry: (_payload, now) => this.expire(now) }, Date.now());
	}
}
