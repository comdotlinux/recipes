import { DurableObject } from "cloudflare:workers";
import { type Result, error } from "./http";
import { FREE_CAP_BYTES, SUBSCRIBER_CAP_BYTES } from "./images";
import { systemEvent, userEvent } from "./metrics";
import { MAX_IMAGES_PER_RECIPE, MAX_RECIPES, RecipeInput, type TranslationView, isoTime, stripControls, toRecipe } from "./recipes";
import { SCREENS_PER_DAY, SHARES_PER_DAY, SHARE_RECIPIENT_LIMIT, SHARE_TTL_MS, derivedId, newShareId, newShareToken, reportReference } from "./share";
import { sha256Hex } from "./auth/encoding";
import { PDF_FAILURES_PER_DAY, PDF_PER_DAY, renderPdf } from "./pdf";
import { pdfHtml } from "./pdf-template";
import { MIGRATIONS, accountState, dropUserTables, migrate, rowCounts, schemaVersion } from "./storage/migrations";
import { armAlarm, runDueTasks, schedule } from "./storage/tasks";
import { fetchPage } from "./ai/fetch-url";
import { AI_NOTICE_VERSION, ConsentRequired, type ModelRunner } from "./ai/gateway";
import { type ImportOutcome, structureRecipe } from "./ai/structure";
import { screenImage } from "./ai/screen-image";
import { transcribePhoto } from "./ai/transcribe";
import { type TranslateOutcome, type TranslationLanguage, translateRecipe } from "./ai/translate";
import { type Entitlement, type EntitlementSource, NONE, deleteCustomer, endOf, revenueCat } from "./billing/revenuecat";
import { exchangeAppleCode, revokeAppleToken } from "./auth/apple";

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
/**
 * How long a tombstone stays after erasure (plan 4.7, 4.9): 12 months, then the whole object is wiped. It holds only
 * the account state, its own expiry, when the free import was used and the refund count, so a returning provider
 * account gets no second free import. Nothing about recipes, pictures, sessions, the subscription or the provider.
 */
export const TOMBSTONE_TTL_MS = 365 * DAY_MS;
/** Meta keys a tombstone keeps; everything else is erased. */
export const TOMBSTONE_KEYS = ["state", "tombstone_until", "free_import_used_at", "free_import_refunds", "suspension_reason", "month_usage"] as const;
/** Third-party deletion steps are retried this long; then the erasure runs anyway, so the user's data never waits on them. */
export const THIRD_PARTY_DEADLINE_MS = 7 * DAY_MS;
/** An Apple authorization code is valid for 5 minutes; an older one is not exchanged any more. */
export const APPLE_CODE_TTL_MS = 5 * 60 * 1000;
/** External deletion steps, each retried until done (plan 4.7); the erasure runs once all are done. */
export const DELETION_STEPS = ["revenuecat", "apple"] as const;

/** Suspension grounds (plan 4.9; the terms of use name them): the code is shown to the user and sent with every 403. */
export const SUSPENSION_REASONS = ["illegal_content", "abusive_content", "quota_abuse", "payment_abuse"] as const;
export type SuspensionReason = (typeof SUSPENSION_REASONS)[number];

/** A Sign in with Apple authorization code and the client id it was issued to (bundle id, or Services ID on the web). */
export type AppleCode = { code: string; clientId: string; redirectUri?: string };

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
type ShareRow = { share_id: string; recipe_id: string; created_at: number; expires_at: number; revoked: number };
type SharedImage = { id: string; mime: string; bytes: ArrayBuffer };

/** The users' objects, pinned to the configured jurisdiction. The only way to address an object by user id. */
export const userNamespace = (env: Env) => (env.DO_JURISDICTION ? env.USER_DO.jurisdiction(env.DO_JURISDICTION) : env.USER_DO);

/** What another user's request may call on an owner's object: exactly these three (plan 4.4). */
type OwnerCalls = Pick<UserDO, "readShare" | "reserveAccept" | "revokeShare">;

/** The shared recipe's text with control and bidirectional characters removed; empty items, steps and tags dropped. */
function sharedText(json: string) {
	const r = JSON.parse(json) as RecipeInput;
	const t = (v: string) => stripControls(v).trim();
	return {
		title: t(r.title),
		description: t(r.description),
		servings: r.servings,
		prepMinutes: r.prepMinutes,
		cookMinutes: r.cookMinutes,
		ingredients: r.ingredients.map((i) => ({ quantity: t(i.quantity), unit: t(i.unit), item: t(i.item), group: i.group === null ? null : t(i.group) })).filter((i) => i.item),
		steps: r.steps.map(t).filter((v) => v),
		tags: r.tags.map(t).filter((v) => v),
		language: r.language,
	};
}

const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);
const INTERNAL = error(500, "internal_error", "object without a user id");
/**
 * Answer of an operation whose account started deletion (or was erased) during one of its awaits: nothing more is
 * written, so a late request leaves only the plain tombstone or empty storage (AC28). Every write after an await in an
 * operation is preceded by this check in the same synchronous step.
 */
const GONE = error(401, "unauthenticated", "the account is being deleted");

/** One instance per user, named by the user id. The only stateful component. */
export class UserDO extends DurableObject<Env> {
	private readonly sql: SqlStorage;

	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		this.sql = ctx.storage.sql;
		void ctx.blockConcurrencyWhile(async () => {
			// An id that never had a session gets no tables: only createSession() makes the schema.
			if (rowCounts(this.sql).size === 0) return;
			// A tombstone keeps only meta and tasks (plan 4.7); migrating it would recreate the user tables of an erased account.
			if (accountState(this.sql) !== "tombstone") migrate(ctx.storage);
			// Repair: a task row without a matching alarm (for example after a crash between both writes, or between the
			// erasure and its alarm) gets its alarm back.
			await armAlarm(ctx.storage);
		});
	}

	protected meta(key: string): string | null {
		return this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null;
	}

	protected setMeta(key: string, value: string): void {
		this.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value);
	}

	/**
	 * This object's user id (its name; api.ts addresses objects only by name). Null, with a metric, if the runtime ever
	 * hands over an object without one: the callers then answer 500 instead of hashing or comparing an empty id.
	 */
	private selfId(): string | null {
		const id = this.ctx.id.name;
		if (id) return id;
		systemEvent(this.env, { event: "internal_error", reason: "object_without_name" });
		return null;
	}

	private event(e: Parameters<typeof userEvent>[2]): void {
		const id = this.meta("metrics_id");
		if (id) userEvent(this.env, id, e);
	}

	/** An account that is in use: active or suspended. Deleting, erased and never-created objects are not. */
	private live(): boolean {
		if (schemaVersion(this.sql) === 0) return false;
		const state = this.meta("state");
		return state === "active" || state === "suspended";
	}

	/** Valid session: extends it at most once per day. Deleting and erased accounts accept no session (plan 4.7). */
	private authenticate(secretHash: string, now: number): boolean {
		if (!this.live()) return false;
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

	/**
	 * The routes a suspended user keeps (plan 4.4): GET /me, GET /recipes, GET /images/:id, DELETE /session and
	 * DELETE /account (web deletion does not use a session), so a backup export and erasure stay possible.
	 */
	private runExempt(group: string, secretHash: string, op: (now: number) => Result | Promise<Result>): Promise<Result> {
		return this.run(group, secretHash, op, false, true);
	}

	protected async run(group: string, secretHash: string, op: (now: number) => Result | Promise<Result>, write = false, suspendedOk = false): Promise<Result> {
		const now = Date.now();
		if (!this.authenticate(secretHash, now)) return error(401, "unauthenticated", "missing, unknown or expired session");
		if (!suspendedOk && this.meta("state") === "suspended") {
			this.event({ event: "request", group, status: 403 });
			const reason = this.meta("suspension_reason") ?? "other";
			return { status: 403, body: { error: { code: "account_suspended", message: `account suspended: ${reason}`, reason } } };
		}
		// Per-user write limit, applied only after the session is accepted; the object id stands for the user.
		const limited = write && !(await this.env.RL_USER.limit({ key: this.ctx.id.toString() })).success;
		// The await above lets other requests in; a DELETE /session in between must win.
		if (write && !this.authenticate(secretHash, now)) return error(401, "unauthenticated", "session revoked");
		const result = limited ? error(429, "rate_limited", "too many writes") : await op(now);
		// Job polling is not counted (plan 4.10); nor is anything once the account is being deleted or erased.
		if (group === "jobs" || !this.live()) return result;
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

	/**
	 * Running total of the stored JSON bytes of live recipes and their translations; set up from the tables once for
	 * objects that predate it.
	 */
	private recipeBytes(): number {
		const stored = this.meta("recipe_bytes");
		if (stored !== null) return Number(stored);
		const total = this.sql
			.exec<{ n: number }>(
				"SELECT (SELECT coalesce(sum(length(CAST(json AS BLOB))), 0) FROM recipes WHERE deleted = 0) + (SELECT coalesce(sum(length(CAST(json AS BLOB))), 0) FROM translations) AS n",
			)
			.one().n;
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

	async createSession(a: { nonceId: string; nonceExpiresAt: number; secretHash: string; platform: string; apple?: AppleCode }): Promise<Result> {
		const now = Date.now();
		if (accountState(this.sql) === "deleting") return error(409, "account_deleting", "account deletion is running");
		migrate(this.ctx.storage);
		const used = this.sql.exec("INSERT INTO used_nonces (nonce_id, expires_at) VALUES (?, ?) ON CONFLICT DO NOTHING", a.nonceId, a.nonceExpiresAt);
		if (used.rowsWritten === 0) return error(401, "invalid_nonce", "nonce already used");
		const state = this.meta("state");
		if (state === null || state === "tombstone") {
			// A new account, or one returning within 12 months of its erasure: the tombstone's free-import facts stay
			// (no second free import, AC18), its wipe is cancelled, and the metrics id is new so old rows stay unlinkable.
			this.sql.exec("DELETE FROM meta WHERE key = 'tombstone_until'");
			this.sql.exec("DELETE FROM tasks WHERE kind = 'tombstone_expiry'");
			// Deleting and returning neither lifts a suspension nor resets this month's quota.
			const usage = this.meta("month_usage");
			if (usage !== null) {
				const { period, count } = JSON.parse(usage) as { period: string; count: number };
				if (period === month(now)) this.sql.exec("INSERT INTO usage (period, count) VALUES (?, ?) ON CONFLICT (period) DO UPDATE SET count = max(count, excluded.count)", period, count);
				this.sql.exec("DELETE FROM meta WHERE key = 'month_usage'");
			}
			this.setMeta("state", this.meta("suspension_reason") === null ? "active" : "suspended");
			this.setMeta("metrics_id", crypto.randomUUID());
			this.setMeta("created_at", String(now));
			this.event({ event: "account_created" });
		}
		if (a.apple) await this.keepAppleCode(a.apple, now);
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
		return this.runExempt("session", secretHash, () => {
			this.sql.exec("DELETE FROM sessions WHERE token_hash = ?", secretHash);
			return { status: 204 };
		});
	}

	me(secretHash: string, userId: string): Promise<Result> {
		return this.runExempt("me", secretHash, async (now) => {
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
		return this.runExempt("recipes", secretHash, () => {
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
				const translated = this.sql.exec<{ n: number }>("SELECT coalesce(sum(length(CAST(json AS BLOB))), 0) AS n FROM translations WHERE recipe_id = ?", id).one().n;
				const freed = (existing.json === null ? 0 : utf8Length(existing.json)) + translated;
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
			if (!this.live()) return GONE;
			if (!this.sql.exec("SELECT 1 FROM recipes WHERE id = ? AND deleted = 0", recipeId).toArray().length) return error(404, "not_found", "recipe not found");
			const owner = this.sql.exec<{ recipe_id: string }>("SELECT recipe_id FROM images WHERE id = ?", imageId).toArray()[0];
			if (owner && owner.recipe_id !== recipeId) return error(404, "not_found", "image belongs to another recipe");
			const count = this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM images WHERE recipe_id = ? AND id != ?", recipeId, imageId).one().n;
			if (count >= MAX_IMAGES_PER_RECIPE) return error(409, "image_limit", `at most ${MAX_IMAGES_PER_RECIPE} images per recipe`);
			// A lapsed subscriber above the free cap keeps everything; only new uploads are refused (plan 4.6, AC8).
			if (this.usedBytes(imageId) + bytes.byteLength > this.capBytes(subscribed)) return error(413, "storage_cap", "storage cap reached");
			// New bytes under the same id must be screened again before they are shared.
			this.sql.exec(
				"INSERT INTO images (id, recipe_id, mime, bytes, size) VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET mime = excluded.mime, bytes = excluded.bytes, size = excluded.size, screened_ok = NULL",
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
		return this.runExempt("images", secretHash, () => {
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
			if (!this.live()) return GONE;
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
			if (!this.live()) return GONE;
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
			// A SELECT, not rowsWritten: an INSERT ... ON CONFLICT DO NOTHING into this AUTOINCREMENT table reports a write even
			// when it inserted nothing. One report per job and kind; a repeat changes nothing.
			if (this.sql.exec("SELECT 1 FROM reports WHERE job_key = ? AND kind = ?", r.jobId, r.kind).toArray().length) return { status: 204 };
			this.sql.exec("INSERT INTO reports (at, kind, job_key, reason, note) VALUES (?, ?, ?, ?, ?)", now, r.kind, r.jobId, r.reason, r.note);
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
				const json = JSON.stringify(outcome.translation);
				const replaced = this.sql.exec<{ json: string }>("SELECT json FROM translations WHERE recipe_id = ? AND lang = ?", recipeId, language).toArray()[0];
				const total = this.recipeBytes() - (replaced ? utf8Length(replaced.json) : 0) + utf8Length(json);
				if (total > RECIPE_STORAGE_CAP) {
					// Translations count toward the 20 MB of recipe text like the recipes themselves.
					outcome = { status: "invalid" };
				} else {
				// Changed while the job ran: the translation is kept, already marked stale (AC22).
				this.sql.exec(
					"INSERT INTO translations (recipe_id, lang, stale, json) VALUES (?, ?, ?, ?) ON CONFLICT (recipe_id, lang) DO UPDATE SET stale = excluded.stale, json = excluded.json",
					recipeId,
					language,
					current.json === source ? 0 : 1,
					json,
				);
				this.setMeta("recipe_bytes", String(total));
				// A new revision, so the delta pull delivers the recipe with its translation.
				this.sql.exec("UPDATE recipes SET rev = ?, updated_at = ? WHERE id = ?", this.nextRev(), now, recipeId);
				}
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
		// A job of an account that is being deleted never calls a model.
		if (!this.live()) return;
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
		// Deletion may have started while the model ran: the erasure takes it from here.
		if (!this.live()) return;
		// The job may have timed out while the model was running; the first outcome wins.
		const current = this.job(jobKey);
		if (current && OPEN.includes(current.status)) this.finish(current, outcome, Date.now());
	}

	timeoutJob(jobKey: string, now: number): void {
		const job = this.job(jobKey);
		if (job && OPEN.includes(job.status)) this.finish(job, { status: "failed" }, now);
	}

	// ---- sharing (plan 4.4, docs/api.md Milestone M5) ----

	private liveRecipe(id: string): RecipeRow | undefined {
		return this.sql.exec<RecipeRow>("SELECT id, rev, deleted, updated_at, json FROM recipes WHERE id = ? AND deleted = 0", id).toArray()[0];
	}

	private sharesToday(now: number): number {
		return this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM shares WHERE created_at >= ?", Date.parse(day(now))).one().n;
	}

	/** The recipe's pictures that passed the share-time check, in display order; only those are ever shared. */
	private sharedPictureIds(recipe: RecipeInput): string[] {
		return recipe.imageIds.filter((id) => this.sql.exec("SELECT 1 FROM images WHERE id = ? AND recipe_id = ? AND screened_ok = 1", id, recipe.id).toArray().length);
	}

	/** POST /recipes/:id/shares: owner, subscriber, at most SHARES_PER_DAY a day. */
	createShare(secretHash: string, recipeId: string): Promise<Result> {
		return this.run("shares", secretHash, async (now) => {
			const owner = this.selfId();
			if (!owner) return INTERNAL;
			const subscribed = await this.entitledNow(now);
			if (!this.live()) return GONE;
			if (!this.liveRecipe(recipeId)) return error(404, "not_found", "recipe not found");
			if (!subscribed) {
				this.event({ event: "share", outcome: "subscription_required" });
				return error(402, "subscription_required", "sharing needs the subscription");
			}
			if (this.sharesToday(now) >= SHARES_PER_DAY) {
				this.event({ event: "share", outcome: "share_limit" });
				return error(429, "share_limit", `at most ${SHARES_PER_DAY} new links a day`);
			}
			const screened = await this.screenPictures(recipeId, now);
			if (screened) return screened;
			if (!this.live()) return GONE;
			// Checked again after the awaits above; from here on everything runs in one step.
			if (!this.liveRecipe(recipeId)) return error(404, "not_found", "recipe not found");
			if (this.sharesToday(now) >= SHARES_PER_DAY) return error(429, "share_limit", `at most ${SHARES_PER_DAY} new links a day`);
			const shareId = newShareId();
			const expiresAt = now + SHARE_TTL_MS;
			this.sql.exec("INSERT INTO shares (share_id, recipe_id, created_at, expires_at) VALUES (?, ?, ?, ?)", shareId, recipeId, now, expiresAt);
			await this.scheduleExpiry(expiresAt);
			this.event({ event: "share", outcome: "created" });
			return { status: 201, body: { shareId, token: await newShareToken(owner, shareId, this.env), expiresAt: isoTime(expiresAt) } };
		}, true);
	}

	/**
	 * Share-time picture check (plan 4.5): every listed picture not checked yet goes to the model once, and the result is
	 * kept with the picture. Null when the recipe may be shared, otherwise the answer to give.
	 */
	private async screenPictures(recipeId: string, now: number): Promise<Result | null> {
		const recipe = JSON.parse(this.liveRecipe(recipeId)!.json!) as RecipeInput;
		if (recipe.imageIds.length === 0) return null;
		// Only a recipe with pictures makes a model call, so only then is consent needed (AC12).
		if (this.consentVersion() !== AI_NOTICE_VERSION) return error(403, "consent_required", "accept the AI notice first");
		const picture = (id: string) =>
			this.sql
				.exec<{ mime: string; bytes: ArrayBuffer; screened_ok: number | null }>("SELECT mime, bytes, screened_ok FROM images WHERE id = ? AND recipe_id = ?", id, recipeId)
				.toArray()[0];
		for (const id of recipe.imageIds) {
			const image = picture(id);
			if (!image || image.screened_ok !== null) continue;
			// Per user and day, so retries after a 422 or 503 (which create no share) cannot call the model without end.
			if (this.counter(now, "screen_calls") >= SCREENS_PER_DAY) {
				this.event({ event: "screen", outcome: "limited" });
				return error(429, "share_limit", `at most ${SCREENS_PER_DAY} picture checks a day`);
			}
			this.bump(now, "screen_calls", 1);
			// Its own global key, so share checks cannot use up the ceiling that paid imports depend on.
			if (!(await this.env.RL_GLOBAL.limit({ key: "screen-image" })).success) {
				systemEvent(this.env, { event: "rate_limited", route: "screen-image" });
				return error(429, "rate_limited", "picture checks are busy; try again later");
			}
			if (!this.live()) return GONE;
			let safe: boolean;
			try {
				safe = await screenImage(this.models(), () => this.consentVersion(), image.bytes, image.mime);
			} catch (e) {
				if (e instanceof ConsentRequired) return error(403, "consent_required", "accept the AI notice first");
				this.event({ event: "screen", outcome: "unavailable" });
				return error(503, "screening_unavailable", "the picture check is not available; try again later");
			}
			if (!this.live()) return GONE;
			// Only for the bytes that were checked: a picture replaced meanwhile stays unchecked.
			this.sql.exec("UPDATE images SET screened_ok = ? WHERE id = ? AND bytes = ?", safe ? 1 : 0, id, image.bytes);
			this.event({ event: "screen", outcome: safe ? "safe" : "not_safe" });
		}
		if (recipe.imageIds.some((id) => picture(id)?.screened_ok === 0)) {
			this.event({ event: "share", outcome: "picture_rejected" });
			return error(422, "picture_rejected", "a picture of this recipe cannot be shared");
		}
		return null;
	}

	/**
	 * GET /shares: the owner's links that still work (not expired, not revoked, recipe not deleted), newest first, with
	 * the number of recipients. No tokens: the server keeps none. A suspended owner's links answer 410, so none is listed.
	 */
	listShares(secretHash: string): Promise<Result> {
		return this.run("shares", secretHash, (now) => {
			if (this.meta("state") !== "active") return { status: 200, body: { shares: [] } };
			const rows = this.sql
				.exec<{ share_id: string; recipe_id: string; created_at: number; expires_at: number; recipients: number }>(
					`SELECT s.share_id, s.recipe_id, s.created_at, s.expires_at, (SELECT count(*) FROM share_accepts a WHERE a.share_id = s.share_id) AS recipients
					 FROM shares s WHERE s.revoked = 0 AND s.expires_at > ? AND EXISTS (SELECT 1 FROM recipes r WHERE r.id = s.recipe_id AND r.deleted = 0)
					 ORDER BY s.created_at DESC, s.rowid DESC`,
					now,
				)
				.toArray();
			const shares = rows.map((r) => ({ shareId: r.share_id, recipeId: r.recipe_id, createdAt: isoTime(r.created_at), expiresAt: isoTime(r.expires_at), recipients: r.recipients }));
			return { status: 200, body: { shares } };
		});
	}

	/** DELETE /shares/:shareId: the owner revokes a link; a revoked link answers 410 to everyone. */
	deleteShare(secretHash: string, shareId: string): Promise<Result> {
		return this.run("shares", secretHash, () => {
			if (this.sql.exec("UPDATE shares SET revoked = 1 WHERE share_id = ?", shareId).rowsWritten === 0) return error(404, "not_found", "share not found");
			this.event({ event: "share", outcome: "revoked" });
			return { status: 204 };
		}, true);
	}

	/**
	 * The share if it can be used now: an unknown share is 404; an expired or revoked one, a deleted recipe or an owner who
	 * is not active is 410. An object without tables (no account) answers 404 and stays without tables.
	 */
	private usableShare(shareId: string, now: number): { share: ShareRow; recipe: RecipeInput } | Result {
		if (schemaVersion(this.sql) === 0) return error(404, "not_found", "share not found");
		const share = this.sql.exec<ShareRow>("SELECT * FROM shares WHERE share_id = ?", shareId).toArray()[0];
		if (!share) return error(404, "not_found", "share not found");
		const row = this.liveRecipe(share.recipe_id);
		if (this.meta("state") !== "active" || share.revoked || share.expires_at <= now || !row) {
			this.event({ event: "share", outcome: "gone" });
			return error(410, "share_gone", "the link expired or was revoked");
		}
		return { share, recipe: JSON.parse(row.json!) as RecipeInput };
	}

	private acceptedBy(shareId: string, recipientHash: string): boolean {
		return this.sql.exec("SELECT 1 FROM share_accepts WHERE share_id = ? AND recipient_hash = ?", shareId, recipientHash).toArray().length > 0;
	}

	private overRecipientLimit(shareId: string, recipientHash: string): boolean {
		if (this.acceptedBy(shareId, recipientHash)) return false;
		return this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM share_accepts WHERE share_id = ?", shareId).one().n >= SHARE_RECIPIENT_LIMIT;
	}

	/** Owner call 1 of 3, for another user: the preview of one shared recipe. */
	readShare(shareId: string, recipientHash: string): Result {
		const usable = this.usableShare(shareId, Date.now());
		if ("status" in usable) return usable;
		if (this.overRecipientLimit(shareId, recipientHash)) return error(410, "share_gone", "the link reached its recipient limit");
		const { title, description, servings, prepMinutes, cookMinutes, ingredients, steps, tags, language } = sharedText(JSON.stringify(usable.recipe));
		return {
			status: 200,
			body: {
				recipe: { title, description, servings, prepMinutes, cookMinutes, ingredients, steps, tags, language },
				imageCount: this.sharedPictureIds(usable.recipe).length,
				expiresAt: isoTime(usable.share.expires_at),
			},
		};
	}

	/**
	 * Owner call 2 of 3, for another user: counts the recipient (atomic, keyed by the hash of the recipient's user id,
	 * so a retry does not count twice and a race cannot pass the limit) and hands out the recipe and its screened pictures.
	 */
	reserveAccept(shareId: string, recipientHash: string): Result {
		const now = Date.now();
		const usable = this.usableShare(shareId, now);
		if ("status" in usable) return usable;
		if (this.overRecipientLimit(shareId, recipientHash)) {
			this.event({ event: "share", outcome: "recipient_limit" });
			return error(410, "share_gone", "the link reached its recipient limit");
		}
		this.sql.exec("INSERT INTO share_accepts (share_id, recipient_hash, accepted_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", shareId, recipientHash, now);
		const images = this.sharedPictureIds(usable.recipe).map((id) => this.sql.exec<SharedImage>("SELECT id, mime, bytes FROM images WHERE id = ?", id).one());
		return { status: 200, body: { recipe: JSON.stringify(usable.recipe), images } };
	}

	/** Owner call 3 of 3, for another user's report: revokes the link. */
	revokeShare(shareId: string): Result {
		if (schemaVersion(this.sql) === 0) return error(404, "not_found", "share not found");
		if (this.sql.exec("UPDATE shares SET revoked = 1 WHERE share_id = ?", shareId).rowsWritten === 0) return error(404, "not_found", "share not found");
		this.event({ event: "share", outcome: "reported" });
		return { status: 204 };
	}

	private owner(ownerId: string): OwnerCalls {
		const ns = userNamespace(this.env);
		return ns.get(ns.idFromName(ownerId)) as unknown as OwnerCalls;
	}

	/** GET /shares/:token for the signed-in recipient: authenticated here first, then the owner is asked. */
	previewShare(secretHash: string, ownerId: string, shareId: string): Promise<Result> {
		return this.run("shares", secretHash, async () => {
			const me = this.selfId();
			if (!me) return INTERNAL;
			if (ownerId === me) return error(409, "own_share", "this is your own link");
			return this.owner(ownerId).readShare(shareId, await sha256Hex(me));
		});
	}

	/**
	 * POST /shares/:token/accept: an independent copy in the recipient's storage, with an id derived from the share and
	 * the recipient. A repeat returns the existing copy untouched. The copy counts against the recipient's caps; when
	 * it does not fit, nothing is copied.
	 */
	acceptShare(secretHash: string, ownerId: string, shareId: string): Promise<Result> {
		return this.run("shares", secretHash, async (now) => {
			const me = this.selfId();
			if (!me) return INTERNAL;
			if (ownerId === me) return error(409, "own_share", "this is your own link");
			const copyId = await derivedId(`${shareId}:${me}`);
			const existing = this.liveRecipe(copyId);
			if (existing) {
				this.event({ event: "share", outcome: "accept_existing" });
				return { status: 200, body: this.recipeViews([existing])[0] };
			}
			// Coarse checks before a recipient slot is taken, so a recipient at a limit does not use up one of the ten.
			if (this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM recipes WHERE deleted = 0").one().n >= MAX_RECIPES) {
				return error(409, "recipe_limit", `at most ${MAX_RECIPES} recipes`);
			}
			if (this.recipeBytes() >= RECIPE_STORAGE_CAP) return error(413, "recipe_storage_cap", "stored recipe text is at 20 MB");
			const subscribed = await this.entitledNow(now);
			if (!this.live()) return GONE;
			const reserved = await this.owner(ownerId).reserveAccept(shareId, await sha256Hex(me));
			if (reserved.status !== 200) return reserved;
			const { recipe, images } = reserved.body as { recipe: string; images: SharedImage[] };
			const imageIds = await Promise.all(images.map((i) => derivedId(`${copyId}:${i.id}`)));
			// No await below: the checks and every write form one step, so a copy is never partial.
			if (!this.live()) return GONE;
			const raced = this.liveRecipe(copyId);
			if (raced) return { status: 200, body: this.recipeViews([raced])[0] };
			const copy = RecipeInput.safeParse({ id: copyId, ...sharedText(recipe), sourceUrl: null, sourceType: "shared", imageIds });
			if (!copy.success) return error(422, "share_unreadable", "the shared recipe has no readable text");
			if (this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM recipes WHERE deleted = 0").one().n >= MAX_RECIPES) {
				return error(409, "recipe_limit", `at most ${MAX_RECIPES} recipes`);
			}
			const json = JSON.stringify(copy.data);
			const total = this.recipeBytes();
			if (total + utf8Length(json) > RECIPE_STORAGE_CAP) return error(413, "recipe_storage_cap", "stored recipe text would exceed 20 MB");
			const added = images.reduce((n, i) => n + i.bytes.byteLength, 0);
			if (added > 0 && this.usedBytes() + added > this.capBytes(subscribed)) return error(413, "storage_cap", "storage cap reached");
			images.forEach((image, n) =>
				this.sql.exec(
					"INSERT INTO images (id, recipe_id, mime, bytes, size, screened_ok) VALUES (?, ?, ?, ?, ?, 1) ON CONFLICT (id) DO UPDATE SET recipe_id = excluded.recipe_id, mime = excluded.mime, bytes = excluded.bytes, size = excluded.size, screened_ok = 1",
					imageIds[n],
					copyId,
					image.mime,
					image.bytes,
					image.bytes.byteLength,
				),
			);
			const rev = this.nextRev();
			this.sql.exec(
				"INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, ?, 0, ?, ?) ON CONFLICT (id) DO UPDATE SET rev = excluded.rev, deleted = 0, updated_at = excluded.updated_at, json = excluded.json",
				copyId,
				rev,
				now,
				json,
			);
			this.setMeta("recipe_bytes", String(total + utf8Length(json)));
			this.event({ event: "share", outcome: "accepted" });
			return { status: 200, body: this.recipeViews([{ id: copyId, rev, deleted: 0, updated_at: now, json }])[0] };
		}, true);
	}

	/** POST /reports with kind "share": recorded here, and the owner's link is revoked. */
	reportShare(secretHash: string, ownerId: string, shareId: string, reason: string, note: string): Promise<Result> {
		return this.run("reports", secretHash, async (now) => {
			const me = this.selfId();
			if (!me) return INTERNAL;
			if (ownerId === me) return error(409, "own_share", "this is your own link");
			const revoked = await this.owner(ownerId).revokeShare(shareId);
			if (revoked.status !== 204) return revoked;
			if (!this.live()) return GONE;
			// One report per share and reporter; a repeat changes nothing.
			if (!this.sql.exec("SELECT 1 FROM reports WHERE job_key = ? AND kind = 'share'", shareId).toArray().length) {
				this.sql.exec("INSERT INTO reports (at, kind, job_key, reason, note) VALUES (?, 'share', ?, ?, ?)", now, shareId, reason, note);
				this.event({ event: "report", kind: "share", reason });
				// The operator learns which account was reported only through this reference (plan 4.10).
				systemEvent(this.env, { event: "share_report", reason, reference: await reportReference(ownerId, now, this.env) });
				await this.scheduleExpiry(now + REPORT_DAYS * DAY_MS);
			}
			return { status: 204 };
		}, true);
	}

	// ---- PDF (docs/api.md Milestone M5) ----

	/** The Browser Run binding; tests replace it, env.local has none. */
	protected browser(): Pick<BrowserRun, "quickAction"> | undefined {
		return this.env.BROWSER;
	}

	/** A per-day counter of this user in daily_counters (also kept 90 days, like the request counts). */
	private counter(now: number, key: string): number {
		return this.sql.exec<{ count: number }>("SELECT count FROM daily_counters WHERE day = ? AND key = ?", day(now), key).toArray()[0]?.count ?? 0;
	}

	private bump(now: number, key: string, delta: number): void {
		this.sql.exec(
			"INSERT INTO daily_counters (day, key, count) VALUES (?, ?, ?) ON CONFLICT (day, key) DO UPDATE SET count = count + excluded.count",
			day(now),
			key,
			delta,
		);
	}

	/**
	 * GET /recipes/:id/pdf: free, PDF_PER_DAY per user and UTC day; a failed render does not count against that, but
	 * against PDF_FAILURES_PER_DAY, so repeated failures cannot use browser time without end.
	 */
	pdf(secretHash: string, recipeId: string): Promise<Result> {
		return this.run("pdf", secretHash, async (now) => {
			const row = this.liveRecipe(recipeId);
			if (!row) return error(404, "not_found", "recipe not found");
			const browser = this.browser();
			if (!browser) return error(503, "pdf_unavailable", "PDF rendering is not configured in this environment");
			const limited = () => this.counter(now, "pdf_rendered") >= PDF_PER_DAY || this.counter(now, "pdf_failed") >= PDF_FAILURES_PER_DAY;
			if (limited()) {
				this.event({ event: "pdf", outcome: "limited" });
				return error(429, "pdf_limit", `at most ${PDF_PER_DAY} PDFs a day`);
			}
			// A global key as well (plan 4.8), so the account-wide render budget has a ceiling.
			if (!(await this.env.RL_GLOBAL.limit({ key: "pdf" })).success) {
				systemEvent(this.env, { event: "rate_limited", route: "pdf" });
				return error(429, "rate_limited", "PDF rendering is busy; try again later");
			}
			if (!this.live()) return GONE;
			// Counted as both before the render, so parallel requests cannot pass either limit; the outcome then takes one back.
			if (limited()) return error(429, "pdf_limit", `at most ${PDF_PER_DAY} PDFs a day`);
			this.bump(now, "pdf_rendered", 1);
			this.bump(now, "pdf_failed", 1);
			const recipe = JSON.parse(row.json!) as RecipeInput;
			const pictures = recipe.imageIds.flatMap((id) =>
				this.sql.exec<{ mime: string; bytes: ArrayBuffer }>("SELECT mime, bytes FROM images WHERE id = ? AND recipe_id = ?", id, recipeId).toArray(),
			);
			const bytes = await renderPdf(browser, pdfHtml(recipe, pictures));
			if (!this.live()) return GONE;
			if (!bytes) {
				this.bump(now, "pdf_rendered", -1);
				this.event({ event: "pdf", outcome: "unavailable" });
				return error(503, "pdf_unavailable", "the PDF could not be made; try again later");
			}
			this.bump(now, "pdf_failed", -1);
			this.event({ event: "pdf", outcome: "rendered" });
			return {
				status: 200,
				bytes,
				headers: {
					"content-type": "application/pdf",
					"content-disposition": 'attachment; filename="recipe.pdf"',
					"cache-control": "private, no-store",
					"x-content-type-options": "nosniff",
				},
			};
		}, true);
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
		// Deletion may have started (or ended) while RevenueCat answered: a late answer changes nothing (AC28).
		if (!this.live()) return null;
		this.event({ event: "entitlement_refresh", trigger, outcome: fresh ? (fresh.active ? "active" : "inactive") : "unavailable" });
		if (!fresh) {
			this.setMeta("entitlement_failed_at", String(now));
			return null;
		}
		if (now < Number(this.meta("entitlement_at") ?? "0")) return this.storedEntitlement();
		// A scheduled re-read that changes the state means the webhook that should have changed it never came (plan 4.10).
		if ((trigger === "daily" || trigger === "stale") && fresh.active !== this.storedEntitlement().active) systemEvent(this.env, { event: "entitlement_mismatch", trigger });
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
				if (this.live()) await this.scheduleRefresh(Date.now());
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
		// Only active and suspended accounts; a deleting account or a tombstone is left alone (AC6, AC28).
		if (!this.live()) return false;
		await this.scheduleRefresh(Date.now());
		return true;
	}

	/** Alarm task: a refresh that fails is retried by the dispatcher with backoff, at most REFRESH_TASK_TRIES times. */
	async refreshTask(now: number, attempts: number): Promise<void> {
		if (!this.live()) return;
		if (attempts >= REFRESH_TASK_TRIES) {
			this.event({ event: "entitlement_refresh", trigger: "webhook", outcome: "gave_up" });
			return;
		}
		if (!(await this.refreshEntitlement(now, "webhook"))) throw new Error("subscription service unavailable");
	}

	// ---- suspension (plan 4.4, 4.12; docs/api.md Milestone M6) ----

	/**
	 * Admin call, reached by user id from the admin host only: suspends with a reason code, or lifts the suspension when
	 * `reason` is null. Writes one audit row here and one admin_action metric. Jobs already running finish and are
	 * charged; nothing new starts while suspended. An id without an account (or a deleting or erased one) answers 404.
	 */
	setSuspended(reason: SuspensionReason | null, actor: string): Result {
		if (!this.live()) return error(404, "not_found", "no account");
		const now = Date.now();
		this.ctx.storage.transactionSync(() => {
			if (reason === null) {
				this.setMeta("state", "active");
				this.sql.exec("DELETE FROM meta WHERE key = 'suspension_reason'");
			} else {
				this.setMeta("state", "suspended");
				this.setMeta("suspension_reason", reason);
			}
			this.sql.exec("INSERT INTO audit (at, actor, action, reason) VALUES (?, ?, ?, ?)", now, actor, reason ? "suspend" : "unsuspend", reason);
		});
		this.event({ event: "admin_action", action: reason ? "suspend" : "unsuspend", reason: reason ?? "none" });
		return { status: 200, body: { state: reason ? "suspended" : "active", reason } };
	}

	/**
	 * Admin call (plan 4.12): counters and states only, never recipe text, pictures or provider identity. Reading does
	 * not create storage; an id without an account answers 404, an erased one its tombstone state only.
	 */
	adminSummary(): Result {
		const state = accountState(this.sql);
		if (state === null) return error(404, "not_found", "no account");
		if (state === "tombstone") return { status: 200, body: { state, tombstoneUntil: isoTime(Number(this.meta("tombstone_until"))) } };
		const now = Date.now();
		const e = this.storedEntitlement();
		const created = this.meta("created_at");
		return {
			status: 200,
			body: {
				state,
				suspensionReason: this.meta("suspension_reason"),
				createdAt: created === null ? null : isoTime(Number(created)),
				entitlement: { active: entitled(e, now, true), expiresAt: endOf(e) === null ? null : isoTime(endOf(e)!) },
				monthUsed: this.monthUsed(now),
				freeImportUsed: this.meta("free_import_used_at") !== null,
				freeImportRefunds: Number(this.meta("free_import_refunds") ?? "0"),
				storageBytes: this.usedBytes(),
				recipes: this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM recipes WHERE deleted = 0").one().n,
				pictures: this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM images").one().n,
				sessions: this.sql.exec<{ n: number }>("SELECT count(*) AS n FROM sessions").one().n,
				recentJobs: this.sql
					.exec<{ kind: string; status: string; created_at: number }>("SELECT kind, status, created_at FROM jobs ORDER BY created_at DESC LIMIT 20")
					.toArray()
					.map((j) => ({ kind: j.kind, status: j.status, createdAt: isoTime(j.created_at) })),
				requests: this.sql.exec<{ day: string; key: string; count: number }>("SELECT day, key, count FROM daily_counters ORDER BY day DESC, key").toArray(),
				audit: this.sql
					.exec<{ at: number; actor: string; action: string; reason: string | null }>("SELECT at, actor, action, reason FROM audit ORDER BY id DESC LIMIT 50")
					.toArray()
					.map((a) => ({ at: isoTime(a.at), actor: a.actor, action: a.action, reason: a.reason })),
			},
		};
	}

	// ---- account deletion (plan 4.7, docs/api.md Milestone M6) ----

	/** Keeps an Apple authorization code until it is exchanged for the refresh token that deletion revokes. */
	private async keepAppleCode(apple: AppleCode, now: number): Promise<void> {
		this.addAppleCode(apple, now);
		await schedule(this.ctx.storage, { id: "apple_token", kind: "apple_token", dueAt: now });
	}

	/**
	 * Exchanges a stored authorization code for a refresh token. True when nothing is left to do (exchanged, refused for
	 * good, or older than 5 minutes, which leaves nothing to revoke); false when Apple or the key was unavailable.
	 */
	/** Codes and refresh tokens are kept per client id: the app (bundle id) and the web page (Services ID) each get their own. */
	private appleMap<T>(key: "apple_auth_codes" | "apple_refresh_tokens"): Record<string, T> {
		return JSON.parse(this.meta(key) ?? "{}") as Record<string, T>;
	}

	private setAppleMap(key: "apple_auth_codes" | "apple_refresh_tokens", value: Record<string, unknown>): void {
		if (Object.keys(value).length === 0) this.sql.exec("DELETE FROM meta WHERE key = ?", key);
		else this.setMeta(key, JSON.stringify(value));
	}

	private addAppleCode(apple: AppleCode, now: number): void {
		this.setAppleMap("apple_auth_codes", { ...this.appleMap("apple_auth_codes"), [apple.clientId]: { code: apple.code, redirectUri: apple.redirectUri, at: now } });
	}

	private async exchangeStoredAppleCode(now: number): Promise<boolean> {
		let done = true;
		for (const [clientId, { code, redirectUri, at }] of Object.entries(this.appleMap<{ code: string; redirectUri?: string; at: number }>("apple_auth_codes"))) {
			const token = now - at < APPLE_CODE_TTL_MS ? await exchangeAppleCode(this.env, clientId, code, now, redirectUri) : "invalid";
			if (token === null) {
				done = false;
				continue;
			}
			// Re-read after the await: another sign-in may have added a code or token meanwhile.
			const codes = this.appleMap("apple_auth_codes");
			delete codes[clientId];
			this.setAppleMap("apple_auth_codes", codes);
			if (token !== "invalid") this.setAppleMap("apple_refresh_tokens", { ...this.appleMap("apple_refresh_tokens"), [clientId]: token });
		}
		return done;
	}

	/** Alarm task after a sign-in with Apple; retried with backoff until the code is exchanged or has expired. */
	async appleTokenTask(now: number): Promise<void> {
		if (!(await this.exchangeStoredAppleCode(now))) throw new Error("Apple token exchange unavailable");
	}

	/** DELETE /account: the state changes first, so every other call answers 401 at once; the steps run as alarm tasks. */
	deleteAccount(secretHash: string): Promise<Result> {
		return this.runExempt("account", secretHash, async (now) => {
			await this.startDeletion(now);
			return { status: 202, body: { state: "deleting" } };
		});
	}

	/**
	 * POST /account/delete-web, after the Worker verified Turnstile, the nonce and the provider's id token. Never creates
	 * storage: an id without an account, or a tombstone, answers 404; a deletion already running answers 202 again.
	 */
	async deleteAccountWeb(a: { nonceId: string; nonceExpiresAt: number; apple?: AppleCode }): Promise<Result> {
		const now = Date.now();
		if (accountState(this.sql) === "deleting") return { status: 202, body: { state: "deleting" } };
		if (!this.live()) return error(404, "no_account", "no account for this sign-in");
		// A SELECT, not rowsWritten: the nonce must be single use whatever the driver reports for ON CONFLICT DO NOTHING.
		if (this.sql.exec("SELECT 1 FROM used_nonces WHERE nonce_id = ?", a.nonceId).toArray().length) return error(401, "invalid_nonce", "nonce already used");
		this.sql.exec("INSERT INTO used_nonces (nonce_id, expires_at) VALUES (?, ?)", a.nonceId, a.nonceExpiresAt);
		// The apple step of the deletion exchanges this code and revokes the token it yields, next to any token of the app.
		if (a.apple) this.addAppleCode(a.apple, now);
		await this.startDeletion(now);
		return { status: 202, body: { state: "deleting" } };
	}

	/** One synchronous step: state, sessions, share links and pending work, then the deletion task. */
	protected async startDeletion(now: number): Promise<void> {
		this.event({ event: "account_deletion", outcome: "started" });
		this.ctx.storage.transactionSync(() => {
			this.setMeta("state", "deleting");
			this.setMeta("deletion_started_at", String(now));
			this.sql.exec("DELETE FROM sessions");
			this.sql.exec("UPDATE shares SET revoked = 1");
			// Open jobs, refreshes, expiry and a pending Apple exchange are dropped; the apple step does that exchange itself.
			this.sql.exec("DELETE FROM tasks");
			this.sql.exec("INSERT INTO tasks (id, kind, due_at, attempts, payload) VALUES ('deletion', 'deletion', ?, 0, NULL)", now);
		});
		await armAlarm(this.ctx.storage);
	}

	/** Revokes every stored Apple refresh token (one per client id); a revoked one is forgotten at once. */
	private async revokeApple(now: number): Promise<boolean> {
		let done = await this.exchangeStoredAppleCode(now);
		for (const [clientId, token] of Object.entries(this.appleMap<string>("apple_refresh_tokens"))) {
			if (!(await revokeAppleToken(this.env, clientId, token, now))) {
				done = false;
				continue;
			}
			const tokens = this.appleMap("apple_refresh_tokens");
			delete tokens[clientId];
			this.setAppleMap("apple_refresh_tokens", tokens);
		}
		return done;
	}

	/**
	 * Alarm task: every external step not done yet, then the erasure. While a step is not done it throws, so the task is
	 * retried with backoff (at most an hour apart) until it is; done steps are not repeated.
	 */
	async runDeletion(now: number): Promise<void> {
		if (accountState(this.sql) !== "deleting") return;
		const userId = this.selfId();
		if (!userId) throw new Error("object without a user id");
		const steps: Record<(typeof DELETION_STEPS)[number], () => Promise<boolean>> = {
			revenuecat: () => deleteCustomer(this.env, userId),
			apple: () => this.revokeApple(now),
		};
		// After THIRD_PARTY_DEADLINE_MS a step that still fails is abandoned with a metric; the erasure does not wait.
		const overdue = now - Number(this.meta("deletion_started_at") ?? now) >= THIRD_PARTY_DEADLINE_MS;
		let pending = 0;
		for (const step of DELETION_STEPS) {
			if (this.meta(`deletion_done_${step}`) !== null) continue;
			if (await steps[step]()) this.setMeta(`deletion_done_${step}`, String(now));
			else if (overdue) systemEvent(this.env, { event: "account_deletion", outcome: "third_party_abandoned", step });
			else pending++;
		}
		if (pending > 0) {
			this.event({ event: "account_deletion", outcome: "step_retry" });
			throw new Error(`${pending} deletion step(s) not done yet`);
		}
		this.event({ event: "account_deletion", outcome: "erased" });
		await this.erase(now);
	}

	/**
	 * The only wipe of this object (plan 4.7). Allowed while the account is being deleted, or once its tombstone expired;
	 * anything else throws and writes a metric. When a tombstone is needed (the free import was used or a refund was
	 * counted), one transactionSync() drops every table, re-creates meta and tasks and writes the tombstone with its wipe
	 * task; spike S11 showed that to be all or nothing. Otherwise deleteAll() removes everything, which is atomic on its own.
	 */
	async erase(now: number): Promise<void> {
		const state = accountState(this.sql);
		const expired = state === "tombstone" && now >= Number(this.meta("tombstone_until"));
		if (state !== "deleting" && !expired) {
			systemEvent(this.env, { event: "internal_error", reason: "erase_refused" });
			throw new Error(`erase refused in state ${state ?? "none"}`);
		}
		const usedAt = this.meta("free_import_used_at");
		const refunds = this.meta("free_import_refunds");
		const suspension = expired ? null : this.meta("suspension_reason");
		const used = expired ? 0 : this.monthUsed(now);
		// A tombstone is kept when it prevents something: a second free import, escaping a suspension, a fresh monthly quota.
		if (expired || (usedAt === null && Number(refunds ?? "0") === 0 && suspension === null && used === 0)) {
			await this.ctx.storage.deleteAll();
			// deleteAll() also clears the alarm at this compatibility date (spike S11); explicit anyway.
			await this.ctx.storage.deleteAlarm();
			return;
		}
		const until = now + TOMBSTONE_TTL_MS;
		this.ctx.storage.transactionSync(() => {
			dropUserTables(this.sql);
			MIGRATIONS[0](this.sql);
			this.setMeta("state", "tombstone");
			this.setMeta("tombstone_until", String(until));
			if (usedAt !== null) this.setMeta("free_import_used_at", usedAt);
			if (refunds !== null) this.setMeta("free_import_refunds", refunds);
			if (suspension !== null) this.setMeta("suspension_reason", suspension);
			if (used > 0) this.setMeta("month_usage", JSON.stringify({ period: month(now), count: used }));
			this.sql.exec("INSERT INTO tasks (id, kind, due_at, attempts, payload) VALUES ('tombstone_expiry', 'tombstone_expiry', ?, 0, NULL)", until);
		});
		await armAlarm(this.ctx.storage);
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
		this.sql.exec("DELETE FROM share_accepts WHERE share_id IN (SELECT share_id FROM shares WHERE expires_at <= ?)", now);
		this.sql.exec("DELETE FROM shares WHERE expires_at <= ?", now);
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
		// A separate query: the runtime's SQLite allows at most five terms in one compound SELECT.
		const share = this.sql.exec<{ at: number | null }>("SELECT min(expires_at) AS at FROM shares").one().at;
		const due = next === null ? share : share === null ? next : Math.min(next, share);
		if (due !== null) await schedule(this.ctx.storage, { id: "expiry", kind: "expiry", dueAt: due });
	}

	async alarm(): Promise<void> {
		await runDueTasks(
			this.ctx.storage,
			{
				expiry: (_payload, now) => this.expire(now),
				job: (payload, now, attempts) => this.runJob(payload!, now, attempts),
				job_timeout: (payload, now) => this.timeoutJob(payload!, now),
				entitlement_refresh: (_payload, now, attempts) => this.refreshTask(now, attempts),
				apple_token: (_payload, now) => this.appleTokenTask(now),
				deletion: (_payload, now) => this.runDeletion(now),
				tombstone_expiry: (_payload, now) => this.erase(now),
			},
			Date.now(),
		);
	}
}
