import { DurableObject } from "cloudflare:workers";
import { type Result, error } from "./http";
import { FREE_CAP_BYTES, SUBSCRIBER_CAP_BYTES } from "./images";
import { userEvent } from "./metrics";
import { MAX_IMAGES_PER_RECIPE, MAX_RECIPES, type RecipeInput, type TranslationView, isoTime, toRecipe } from "./recipes";
import { migrate, rowCounts, schemaVersion } from "./storage/migrations";
import { armAlarm, runDueTasks, schedule } from "./storage/tasks";
import { fetchPage } from "./ai/fetch-url";
import { AI_NOTICE_VERSION, ConsentRequired, type ModelRunner } from "./ai/gateway";
import { type ImportOutcome, structureRecipe } from "./ai/structure";
import { transcribePhoto } from "./ai/transcribe";
import { type TranslateOutcome, type TranslationLanguage, translateRecipe } from "./ai/translate";
import { type Entitlement, type EntitlementSource, NONE, endOf, revenueCat } from "./billing/revenuecat";

export const DAY_MS = 86_400_000;
export const SESSION_TTL_MS = 30 * DAY_MS;
export const MARKER_TTL_MS = 90 * DAY_MS;
export const COUNTER_DAYS = 90;
export const MONTH_LIMIT = 30;
export const PAGE_SIZE = 200;
export const MAX_SESSIONS = 10;
/** Sum of the stored JSON of live recipes per user: 20 MB, so a full pull fits in a phone's memory. */
export const RECIPE_STORAGE_CAP = 20_971_520;
const utf8 = new TextEncoder();
const utf8Length = (s: string) => utf8.encode(s).byteLength;
export const JOB_TIMEOUT_MS = 10 * 60 * 1000;
export const JOB_TRIES = 3;
export const JOB_RESULT_DAYS = 7;
export const ATTEMPTS_PER_DAY = 10;
export const FREE_REFUND_LIMIT = 5;
export const REPORT_DAYS = 90;
/** Plan 4.6: with RevenueCat unreachable, a stored entitlement is honoured this long after its end. */
export const OFFLINE_HONOUR_MS = DAY_MS;
/** After a failed RevenueCat call, requests use the stored entitlement for this long instead of calling again. */
export const REFRESH_RETRY_MS = 60_000;
/** An active entitlement that has not run out is re-read from RevenueCat at most this often (missed revocation webhooks). */
export const RECHECK_MS = DAY_MS;
/** POST /entitlement/refresh at most once per this interval per user. */
export const CLIENT_REFRESH_MS = 5_000;
/** A webhook-triggered refresh is retried with backoff (storage/tasks.ts) at most this many times. */
export const REFRESH_TASK_TRIES = 10;

/** Plan 4.6: active means now is before the end (grace period included); while RevenueCat is unreachable, 24 hours longer. */
export function entitled(e: Entitlement, now: number, reachable: boolean): boolean {
	if (!e.active) return false;
	const end = endOf(e);
	return end === null || now < end || (!reachable && now < end + OFFLINE_HONOUR_MS);
}

export type ImportInput = { kind: "url"; url: string } | { kind: "text"; text: string } | { kind: "photo"; bytes: ArrayBuffer };
type JobRow = { job_key: string; kind: string; status: string; input: string | null; input_blob: ArrayBuffer | null; created_at: number; result_json: string | null; charge: string | null; period: string | null };
const OPEN = ["pending", "running"];
const month = (ms: number) => new Date(ms).toISOString().slice(0, 7);

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

	protected meta(key: string): string | null {
		return this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null;
	}

	protected setMeta(key: string, value: string): void {
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

	protected async run(group: string, secretHash: string, op: (now: number) => Result | Promise<Result>, write = false): Promise<Result> {
		const now = Date.now();
		if (!this.authenticate(secretHash, now)) return error(401, "unauthenticated", "missing, unknown or expired session");
		// Per-user write limit, applied only after the session is accepted; the object id stands for the user.
		const limited = write && !(await this.env.RL_USER.limit({ key: this.ctx.id.toString() })).success;
		// The await above lets other requests in; a DELETE /session in between must win.
		if (write && !this.authenticate(secretHash, now)) return error(401, "unauthenticated", "session revoked");
		const result = limited ? error(429, "rate_limited", "too many writes") : await op(now);
		// Job polling is not counted (plan 4.10).
		if (group === "jobs") return result;
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

	/** Running total of the stored JSON bytes of live recipes; set up from the table once for objects that predate it. */
	private recipeBytes(): number {
		const stored = this.meta("recipe_bytes");
		if (stored !== null) return Number(stored);
		const total = this.sql.exec<{ n: number }>("SELECT coalesce(sum(length(CAST(json AS BLOB))), 0) AS n FROM recipes WHERE deleted = 0").one().n;
		this.setMeta("recipe_bytes", String(total));
		return total;
	}

	private capBytes(subscribed: boolean): number {
		return subscribed ? SUBSCRIBER_CAP_BYTES : FREE_CAP_BYTES;
	}

	/** Recipes in the contract shape, each with its stored translations. */
	private recipeViews(rows: RecipeRow[]) {
		const byRecipe = new Map<string, TranslationView[]>();
		for (const row of rows) {
			if (row.deleted) continue;
			const variants = this.sql
				.exec<{ lang: string; stale: number; json: string }>("SELECT lang, stale, json FROM translations WHERE recipe_id = ? ORDER BY lang", row.id)
				.toArray()
				.map((t) => ({ language: t.lang, stale: t.stale === 1, ...JSON.parse(t.json) }));
			byRecipe.set(row.id, variants);
		}
		return rows.map((row) => toRecipe(row, byRecipe.get(row.id)));
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
		return this.run("me", secretHash, async (now) => {
			const billing = this.billingView(await this.entitledNow(now), now);
			const state = this.meta("state") ?? "active";
			const consent = this.meta("consent_version");
			return {
				status: 200,
				body: {
					userId,
					state,
					suspension: state === "suspended" ? { reason: this.meta("suspension_reason") } : null,
					...billing,
					consentVersion: consent === null ? null : Number(consent),
					// At most one open job (startImport's job_running check), so a 409 can always be resolved to it.
					openJobId: this.sql.exec<{ job_key: string }>("SELECT job_key FROM jobs WHERE status IN ('pending', 'running') LIMIT 1").toArray()[0]?.job_key ?? null,
				},
			};
		});
	}

	/** The entitlement, quota and storage parts of GET /me; also the answer of POST /entitlement/refresh. */
	protected billingView(subscribed: boolean, now: number) {
		const end = endOf(this.storedEntitlement());
		return {
			entitlement: { active: subscribed, expiresAt: subscribed && end !== null ? isoTime(end) : null },
			quota: { freeImportUsed: this.meta("free_import_used_at") !== null, monthUsed: this.monthUsed(now), monthLimit: MONTH_LIMIT },
			storage: { usedBytes: this.usedBytes(), capBytes: this.capBytes(subscribed) },
		};
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
			return { status: 200, body: { recipes: this.recipeViews(page), rev, more } };
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
			const json = JSON.stringify(input);
			const size = utf8Length(json);
			const oldSize = live && existing.json !== null ? utf8Length(existing.json) : 0;
			const total = this.recipeBytes();
			// Bounds what a full pull holds on the phone; an update that does not grow always passes.
			if (size > oldSize && total - oldSize + size > RECIPE_STORAGE_CAP) {
				return error(413, "recipe_storage_cap", "stored recipe text would exceed 20 MB");
			}
			const stored = new Set(this.sql.exec<{ id: string }>("SELECT id FROM images WHERE recipe_id = ?", input.id).toArray().map((r) => r.id));
			if (input.imageIds.some((id) => !stored.has(id))) return error(400, "invalid_body", "imageIds lists an image not uploaded to this recipe");
			for (const id of stored) if (!input.imageIds.includes(id)) this.sql.exec("DELETE FROM images WHERE id = ?", id);
			// A content change makes every translation of this recipe stale (AC22); they stay until translated again.
			if (live && existing.json !== json) this.sql.exec("UPDATE translations SET stale = 1 WHERE recipe_id = ?", input.id);
			const rev = this.nextRev();
			this.sql.exec(
				"INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, ?, 0, ?, ?) ON CONFLICT (id) DO UPDATE SET rev = excluded.rev, deleted = 0, updated_at = excluded.updated_at, json = excluded.json",
				input.id,
				rev,
				now,
				json,
			);
			this.setMeta("recipe_bytes", String(total - oldSize + size));
			return { status: 200, body: this.recipeViews([{ id: input.id, rev, deleted: 0, updated_at: now, json }])[0] };
		}, true);
	}

	deleteRecipe(secretHash: string, id: string): Promise<Result> {
		return this.run("recipes", secretHash, async (now) => {
			const existing = this.sql.exec<{ deleted: number; json: string | null }>("SELECT deleted, json FROM recipes WHERE id = ?", id).toArray()[0];
			if (!existing) return error(404, "not_found", "recipe not found");
			if (existing.deleted === 0) {
				const freed = existing.json === null ? 0 : utf8Length(existing.json);
				const total = this.recipeBytes();
				this.sql.exec("UPDATE recipes SET rev = ?, deleted = 1, updated_at = ?, json = NULL WHERE id = ?", this.nextRev(), now, id);
				this.setMeta("recipe_bytes", String(total - freed));
				this.sql.exec("DELETE FROM images WHERE recipe_id = ?", id);
				this.sql.exec("DELETE FROM translations WHERE recipe_id = ?", id);
				await this.scheduleExpiry(now + MARKER_TTL_MS);
			}
			return { status: 204 };
		}, true);
	}

	// ---- images ----

	putImage(secretHash: string, recipeId: string, imageId: string, mime: string, bytes: ArrayBuffer): Promise<Result> {
		return this.run("images", secretHash, async (now) => {
			// Before the checks below: they and the insert must run without an await in between.
			const subscribed = await this.entitledNow(now);
			if (!this.sql.exec("SELECT 1 FROM recipes WHERE id = ? AND deleted = 0", recipeId).toArray().length) return error(404, "not_found", "recipe not found");
			const owner = this.sql.exec<{ recipe_id: string }>("SELECT recipe_id FROM images WHERE id = ?", imageId).toArray()[0];
			if (owner && owner.recipe_id !== recipeId) return error(404, "not_found", "image belongs to another recipe");
			const count = this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM images WHERE recipe_id = ? AND id != ?", recipeId, imageId).one().n;
			if (count >= MAX_IMAGES_PER_RECIPE) return error(409, "image_limit", `at most ${MAX_IMAGES_PER_RECIPE} images per recipe`);
			// A lapsed subscriber above the free cap keeps everything; only new uploads are refused (plan 4.6, AC8).
			if (this.usedBytes(imageId) + bytes.byteLength > this.capBytes(subscribed)) return error(413, "storage_cap", "storage cap reached");
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

	// ---- AI consent ----

	setConsent(secretHash: string, version: number): Promise<Result> {
		return this.run("me", secretHash, () => {
			if (version !== AI_NOTICE_VERSION) return error(400, "consent_version_unknown", `the current AI notice version is ${AI_NOTICE_VERSION}`);
			this.setMeta("consent_version", String(version));
			return { status: 200, body: { consentVersion: version } };
		}, true);
	}

	clearConsent(secretHash: string): Promise<Result> {
		return this.run("me", secretHash, () => {
			this.sql.exec("DELETE FROM meta WHERE key = 'consent_version'");
			return { status: 204 };
		}, true);
	}

	private consentVersion(): number | null {
		const v = this.meta("consent_version");
		return v === null ? null : Number(v);
	}

	// ---- quota (plan 4.5 step 7, 4.6) ----

	private monthUsed(now: number): number {
		return this.sql.exec<{ count: number }>("SELECT count FROM usage WHERE period = ?", month(now)).toArray()[0]?.count ?? 0;
	}

	/**
	 * Charges one unit: the free lifetime unit first (imports only; a translation never takes it), then a monthly unit
	 * while subscribed. Null when none is left.
	 */
	private reserve(now: number, subscribed: boolean, free = true): { charge: "free" | "month"; period: string | null } | null {
		if (free && this.meta("free_import_used_at") === null) {
			this.setMeta("free_import_used_at", String(now));
			return { charge: "free", period: null };
		}
		// The count is kept per UTC month, so a lapse and a resubscription in the same month continue it.
		if (subscribed && this.monthUsed(now) < MONTH_LIMIT) {
			this.sql.exec("INSERT INTO usage (period, count) VALUES (?, 1) ON CONFLICT (period) DO UPDATE SET count = count + 1", month(now));
			return { charge: "month", period: month(now) };
		}
		return null;
	}

	private refund(job: JobRow): void {
		if (job.charge === "month" && job.period) {
			this.sql.exec("UPDATE usage SET count = max(count - 1, 0) WHERE period = ?", job.period);
		} else if (job.charge === "free") {
			// At most FREE_REFUND_LIMIT refunds in an account's life; after that the free unit stays consumed.
			const refunds = Number(this.meta("free_import_refunds") ?? "0");
			if (refunds < FREE_REFUND_LIMIT) {
				this.setMeta("free_import_refunds", String(refunds + 1));
				this.sql.exec("DELETE FROM meta WHERE key = 'free_import_used_at'");
			}
		}
	}

	private attemptsToday(now: number): number {
		return this.sql.exec<{ count: number }>("SELECT count FROM attempts WHERE day = ?", day(now)).toArray()[0]?.count ?? 0;
	}

	// ---- import jobs ----

	private jobView(job: JobRow): Result {
		if (job.kind === "translation") {
			// result_json holds { recipeId, language } from the start; the translation itself lives with the recipe.
			const target = JSON.parse(job.result_json!) as { recipeId: string; language: string };
			return { status: 0, body: { id: job.job_key, kind: job.kind, status: job.status, createdAt: isoTime(job.created_at), draft: null, ...target } };
		}
		const kind = job.kind.replace("import_", "");
		const draft = job.status === "done" && job.result_json ? JSON.parse(job.result_json) : null;
		return { status: 0, body: { id: job.job_key, kind, status: job.status, createdAt: isoTime(job.created_at), draft } };
	}

	private job(key: string): JobRow | undefined {
		return this.sql.exec<JobRow>("SELECT * FROM jobs WHERE job_key = ?", key).toArray()[0];
	}

	startImport(secretHash: string, jobKey: string, input: ImportInput): Promise<Result> {
		return this.run("imports", secretHash, async (now) => {
			// The only await: everything below, from the idempotency check to the insert, runs in one synchronous step.
			const subscribed = await this.entitledNow(now);
			const existing = this.job(jobKey);
			if (existing) return { ...this.jobView(existing), status: 202 };
			if (this.consentVersion() !== AI_NOTICE_VERSION) return error(403, "consent_required", "accept the AI notice first");
			if (this.sql.exec("SELECT 1 FROM jobs WHERE status IN ('pending', 'running')").toArray().length) {
				return error(409, "job_running", "another import is still running");
			}
			if (this.attemptsToday(now) >= ATTEMPTS_PER_DAY) {
				this.event({ event: "quota_denied", reason: "attempt_limit" });
				return error(429, "attempt_limit", "too many failed imports today");
			}
			const unit = this.reserve(now, subscribed);
			if (!unit) {
				this.event({ event: "quota_denied", reason: "quota_exhausted" });
				return error(402, "quota_exhausted", "no import left");
			}
			this.sql.exec(
				"INSERT INTO jobs (job_key, kind, status, input, input_blob, created_at, charge, period) VALUES (?, ?, 'pending', ?, ?, ?, ?, ?)",
				jobKey,
				`import_${input.kind}`,
				input.kind === "url" ? input.url : input.kind === "text" ? input.text : null,
				input.kind === "photo" ? input.bytes : null,
				now,
				unit.charge,
				unit.period,
			);
			await schedule(this.ctx.storage, { id: `job:${jobKey}`, kind: "job", dueAt: now, payload: jobKey });
			await schedule(this.ctx.storage, { id: `job_timeout:${jobKey}`, kind: "job_timeout", dueAt: now + JOB_TIMEOUT_MS, payload: jobKey });
			return { ...this.jobView(this.job(jobKey)!), status: 202 };
		}, true);
	}

	getJob(secretHash: string, jobKey: string): Promise<Result> {
		return this.run("jobs", secretHash, (now) => {
			const job = this.job(jobKey);
			if (!job || job.created_at < now - JOB_RESULT_DAYS * DAY_MS) return error(404, "not_found", "job not found");
			return { ...this.jobView(job), status: 200 };
		});
	}

	/** POST /recipes/:id/translations: a job like an import, subscriber only, always a monthly unit (plan 4.5, 4.6). */
	startTranslation(secretHash: string, jobKey: string, recipeId: string, language: TranslationLanguage): Promise<Result> {
		return this.run("translations", secretHash, async (now) => {
			// The only await: everything below, from the idempotency check to the insert, runs in one synchronous step.
			const subscribed = await this.entitledNow(now);
			const existing = this.job(jobKey);
			if (existing) return { ...this.jobView(existing), status: 202 };
			const recipe = this.sql.exec<{ json: string }>("SELECT json FROM recipes WHERE id = ? AND deleted = 0", recipeId).toArray()[0];
			if (!recipe) return error(404, "not_found", "recipe not found");
			if (this.consentVersion() !== AI_NOTICE_VERSION) return error(403, "consent_required", "accept the AI notice first");
			if (!subscribed) {
				this.event({ event: "quota_denied", reason: "subscription_required" });
				return error(402, "subscription_required", "translation needs the subscription");
			}
			if (this.sql.exec("SELECT 1 FROM jobs WHERE status IN ('pending', 'running')").toArray().length) {
				return error(409, "job_running", "another job is still running");
			}
			if (this.attemptsToday(now) >= ATTEMPTS_PER_DAY) {
				this.event({ event: "quota_denied", reason: "attempt_limit" });
				return error(429, "attempt_limit", "too many failed jobs today");
			}
			const unit = this.reserve(now, subscribed, false);
			if (!unit) {
				this.event({ event: "quota_denied", reason: "quota_exhausted" });
				return error(402, "quota_exhausted", "no monthly unit left");
			}
			this.sql.exec(
				"INSERT INTO jobs (job_key, kind, status, input, created_at, result_json, charge, period) VALUES (?, 'translation', 'pending', ?, ?, ?, ?, ?)",
				jobKey,
				JSON.stringify({ recipeId, language, source: recipe.json }),
				now,
				JSON.stringify({ recipeId, language }),
				unit.charge,
				unit.period,
			);
			await schedule(this.ctx.storage, { id: `job:${jobKey}`, kind: "job", dueAt: now, payload: jobKey });
			await schedule(this.ctx.storage, { id: `job_timeout:${jobKey}`, kind: "job_timeout", dueAt: now + JOB_TIMEOUT_MS, payload: jobKey });
			return { ...this.jobView(this.job(jobKey)!), status: 202 };
		}, true);
	}

	report(secretHash: string, r: { kind: string; jobId: string; reason: string; note: string }): Promise<Result> {
		return this.run("reports", secretHash, async (now) => {
			if (!this.job(r.jobId)) return error(404, "not_found", "job not found");
			const inserted = this.sql.exec(
				"INSERT INTO reports (at, kind, job_key, reason, note) VALUES (?, ?, ?, ?, ?) ON CONFLICT (job_key, kind) DO NOTHING",
				now,
				r.kind,
				r.jobId,
				r.reason,
				r.note,
			);
			if (inserted.rowsWritten === 0) return { status: 204 };
			this.event({ event: "report", kind: r.kind, reason: r.reason });
			await this.scheduleExpiry(now + REPORT_DAYS * DAY_MS);
			return { status: 204 };
		}, true);
	}

	/** Ends a job in exactly one outcome, in one synchronous step: status, refund, attempt count, input removed. */
	private finish(job: JobRow, result: ImportOutcome | TranslateOutcome | { status: "failed" }, now: number): void {
		let outcome = result;
		if (outcome.status === "done" && "translation" in outcome) {
			const { recipeId, language, source } = JSON.parse(job.input!) as { recipeId: string; language: string; source: string };
			const current = this.sql.exec<{ json: string }>("SELECT json FROM recipes WHERE id = ? AND deleted = 0", recipeId).toArray()[0];
			if (!current) {
				// Deleted while the job ran: nothing to attach the translation to.
				outcome = { status: "failed" };
			} else {
				// Changed while the job ran: the translation is kept, already marked stale (AC22).
				this.sql.exec(
					"INSERT INTO translations (recipe_id, lang, stale, json) VALUES (?, ?, ?, ?) ON CONFLICT (recipe_id, lang) DO UPDATE SET stale = excluded.stale, json = excluded.json",
					recipeId,
					language,
					current.json === source ? 0 : 1,
					JSON.stringify(outcome.translation),
				);
				// A new revision, so the delta pull delivers the recipe with its translation.
				this.sql.exec("UPDATE recipes SET rev = ?, updated_at = ? WHERE id = ?", this.nextRev(), now, recipeId);
			}
		}
		const draft = outcome.status === "done" && "draft" in outcome ? JSON.stringify(outcome.draft) : null;
		this.sql.exec(
			"UPDATE jobs SET status = ?, result_json = coalesce(?, result_json), input = NULL, input_blob = NULL, ended_at = ? WHERE job_key = ?",
			outcome.status,
			draft,
			now,
			job.job_key,
		);
		if (outcome.status !== "done") this.refund(job);
		// A failed job on a monthly unit also counts, so failing imports cannot run unlimited model calls (docs/api.md, M3).
		const counted = outcome.status === "guard_block" || outcome.status === "no_recipe" || outcome.status === "invalid";
		if (counted || (outcome.status === "failed" && job.charge === "month")) {
			this.sql.exec("INSERT INTO attempts (day, count) VALUES (?, 1) ON CONFLICT (day) DO UPDATE SET count = count + 1", day(now));
		}
		this.sql.exec("DELETE FROM tasks WHERE id IN (?, ?)", `job:${job.job_key}`, `job_timeout:${job.job_key}`);
		this.event({ event: "import_outcome", kind: job.kind, outcome: outcome.status, durationMs: now - job.created_at });
	}

	/** The Workers AI binding; the local entry's subclass supplies a stand-in where the environment has none. */
	protected models(): { AI?: ModelRunner } {
		return this.env;
	}

	private async pipeline(job: JobRow): Promise<ImportOutcome | TranslateOutcome> {
		// Read at every model call, so a withdrawal while the page is fetched stops the model call.
		const consent = () => this.consentVersion();
		const ai = this.models();
		if (job.kind === "translation") {
			const t = JSON.parse(job.input!) as { language: TranslationLanguage; source: string };
			return translateRecipe(ai, consent, JSON.parse(t.source) as RecipeInput, t.language);
		}
		if (job.kind === "import_url") {
			const page = await fetchPage(job.input!);
			if (!page.ok) {
				if (page.transient) throw new Error(`fetch failed: ${page.reason}`);
				return { status: "no_recipe" };
			}
			return structureRecipe(ai, consent, page.text, { sourceType: "url", sourceUrl: job.input });
		}
		if (job.kind === "import_photo") {
			const transcript = await transcribePhoto(ai, consent, job.input_blob!);
			if (transcript.status !== "text") return { status: transcript.status };
			return structureRecipe(ai, consent, transcript.text, { sourceType: "photo", sourceUrl: null });
		}
		return structureRecipe(ai, consent, job.input!, { sourceType: "photo", sourceUrl: null });
	}

	/** Alarm task: runs a job. A transient error is retried by the dispatcher; the third failure ends it as failed. */
	async runJob(jobKey: string, now: number, attempts: number): Promise<void> {
		const job = this.job(jobKey);
		if (!job || !OPEN.includes(job.status)) return;
		// Tries already used up (also by runs the runtime killed), timed out, or consent withdrawn: end it now.
		if (attempts >= JOB_TRIES || now - job.created_at >= JOB_TIMEOUT_MS || this.consentVersion() !== AI_NOTICE_VERSION) {
			this.finish(job, { status: "failed" }, now);
			return;
		}
		this.sql.exec("UPDATE jobs SET status = 'running' WHERE job_key = ?", jobKey);
		let outcome: ImportOutcome | TranslateOutcome;
		try {
			outcome = await this.pipeline(job);
		} catch (e) {
			if (e instanceof ConsentRequired || attempts + 1 >= JOB_TRIES) {
				// Re-read: the job may have ended (timeout) while the pipeline was awaiting.
				const current = this.job(jobKey);
				if (current && OPEN.includes(current.status)) this.finish(current, { status: "failed" }, Date.now());
				return;
			}
			throw e;
		}
		// The job may have timed out while the model was running; the first outcome wins.
		const current = this.job(jobKey);
		if (current && OPEN.includes(current.status)) this.finish(current, outcome, Date.now());
	}

	timeoutJob(jobKey: string, now: number): void {
		const job = this.job(jobKey);
		if (job && OPEN.includes(job.status)) this.finish(job, { status: "failed" }, now);
	}

	// ---- subscription (plan 4.6) ----

	private storedEntitlement(): Entitlement {
		return { ...NONE, ...(JSON.parse(this.meta("entitlement") ?? "{}") as Partial<Entitlement>) };
	}

	/** The RevenueCat REST client; null without an API key. The local entry's subclass supplies a stand-in there. */
	protected entitlementSource(): EntitlementSource | null {
		return this.env.REVENUECAT_API_KEY ? revenueCat(this.env) : null;
	}

	/**
	 * Reads the subscriber record and stores it. Null when RevenueCat is unreachable; the stored entitlement then stays.
	 * `now` is when this refresh started: an answer is stored only if no refresh that started later has stored one already,
	 * so a slow older answer (active) cannot overwrite a newer one (refunded). The stored record is returned in that case.
	 */
	protected async refreshEntitlement(now: number, trigger: string): Promise<Entitlement | null> {
		const source = this.entitlementSource();
		// The object's name is the user id (api.ts userObject), which is also the RevenueCat app user id (Purchases.logIn).
		const userId = this.ctx.id.name;
		this.setMeta("entitlement_tried_at", String(now));
		const fresh = source && userId ? await source(userId, now) : null;
		this.event({ event: "entitlement_refresh", trigger, outcome: fresh ? (fresh.active ? "active" : "inactive") : "unavailable" });
		if (!fresh) {
			this.setMeta("entitlement_failed_at", String(now));
			return null;
		}
		if (now < Number(this.meta("entitlement_at") ?? "0")) return this.storedEntitlement();
		this.setMeta("entitlement", JSON.stringify(fresh));
		this.setMeta("entitlement_at", String(now));
		this.sql.exec("DELETE FROM meta WHERE key = 'entitlement_failed_at'");
		return fresh;
	}

	/**
	 * Entitled now? An active record is re-read from RevenueCat when it has run out (so a renewal counts before its
	 * webhook) and otherwise at most once a day (so a refund whose webhook was lost still ends access within a day).
	 */
	private async entitledNow(now: number): Promise<boolean> {
		const stored = this.storedEntitlement();
		if (!stored.active) return false;
		const end = endOf(stored);
		const runOut = end !== null && now >= end;
		const due = runOut
			? now - Number(this.meta("entitlement_failed_at") ?? "0") >= REFRESH_RETRY_MS
			: now - Number(this.meta("entitlement_tried_at") ?? "0") >= RECHECK_MS;
		const fresh = due ? await this.refreshEntitlement(now, runOut ? "stale" : "daily") : null;
		return fresh ? entitled(fresh, now, true) : entitled(stored, now, false);
	}

	refreshFromClient(secretHash: string): Promise<Result> {
		return this.run("billing", secretHash, async (now) => {
			if (now - Number(this.meta("entitlement_client_at") ?? "0") < CLIENT_REFRESH_MS) return error(429, "rate_limited", "refresh at most every 5 seconds");
			this.setMeta("entitlement_client_at", String(now));
			const fresh = await this.refreshEntitlement(now, "client");
			if (!fresh) {
				await this.scheduleRefresh(Date.now());
				return error(503, "billing_unavailable", "the subscription service could not be reached; retried in the background");
			}
			return { status: 200, body: this.billingView(entitled(fresh, now, true), now) };
		}, true);
	}

	private scheduleRefresh(now: number): Promise<void> {
		return schedule(this.ctx.storage, { id: "entitlement_refresh", kind: "entitlement_refresh", dueAt: now }, true);
	}

	/**
	 * Webhook trigger: the payload is not trusted, only this call. An id without an account gets no tables and a
	 * deleting account is left alone; both answer false. Replays and out-of-order events only cause another refresh.
	 */
	async entitlementChanged(): Promise<boolean> {
		if (schemaVersion(this.sql) === 0) return false;
		const state = this.meta("state");
		if (state === null || state === "deleting") return false;
		await this.scheduleRefresh(Date.now());
		return true;
	}

	/** Alarm task: a refresh that fails is retried by the dispatcher with backoff, at most REFRESH_TASK_TRIES times. */
	async refreshTask(now: number, attempts: number): Promise<void> {
		if (this.meta("state") === "deleting") return;
		if (attempts >= REFRESH_TASK_TRIES) {
			this.event({ event: "entitlement_refresh", trigger: "webhook", outcome: "gave_up" });
			return;
		}
		if (!(await this.refreshEntitlement(now, "webhook"))) throw new Error("subscription service unavailable");
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
		this.sql.exec("DELETE FROM attempts WHERE day < ?", day(now - DAY_MS));
		this.sql.exec("DELETE FROM jobs WHERE created_at <= ? AND status NOT IN ('pending', 'running')", now - JOB_RESULT_DAYS * DAY_MS);
		this.sql.exec("DELETE FROM reports WHERE at <= ?", now - REPORT_DAYS * DAY_MS);
		const horizon = now - MARKER_TTL_MS;
		const purged = this.sql.exec<{ rev: number | null }>("SELECT max(rev) AS rev FROM recipes WHERE deleted = 1 AND updated_at <= ?", horizon).one().rev;
		if (purged !== null) {
			this.setMeta("purged_rev", String(Math.max(purged, Number(this.meta("purged_rev") ?? "0"))));
			this.sql.exec("DELETE FROM recipes WHERE deleted = 1 AND updated_at <= ?", horizon);
			// Markers hold no JSON, so purging them leaves recipe_bytes unchanged.
		}
		const next = this.sql
			.exec<{ at: number | null }>(
				`SELECT min(at) AS at FROM (
					SELECT min(expires_at) AS at FROM sessions UNION ALL
					SELECT min(expires_at) FROM used_nonces UNION ALL
					SELECT min(updated_at) + ? FROM recipes WHERE deleted = 1 UNION ALL
					SELECT min(created_at) + ? FROM jobs UNION ALL
					SELECT min(at) + ? FROM reports)`,
				MARKER_TTL_MS,
				JOB_RESULT_DAYS * DAY_MS,
				REPORT_DAYS * DAY_MS,
			)
			.one().at;
		if (next !== null) await schedule(this.ctx.storage, { id: "expiry", kind: "expiry", dueAt: next });
	}

	async alarm(): Promise<void> {
		await runDueTasks(
			this.ctx.storage,
			{
				expiry: (_payload, now) => this.expire(now),
				job: (payload, now, attempts) => this.runJob(payload!, now, attempts),
				job_timeout: (payload, now) => this.timeoutJob(payload!, now),
				entitlement_refresh: (_payload, now, attempts) => this.refreshTask(now, attempts),
			},
			Date.now(),
		);
	}
}
