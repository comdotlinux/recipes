import { env, runDurableObjectAlarm } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import refreshFixture from "../../../contract/fixtures/entitlement_refresh.json";
import billingIssue from "../fixtures/revenuecat/billing_issue.json";
import cancellation from "../fixtures/revenuecat/cancellation.json";
import expiration from "../fixtures/revenuecat/expiration.json";
import initialPurchase from "../fixtures/revenuecat/initial_purchase.json";
import refund from "../fixtures/revenuecat/refund.json";
import renewal from "../fixtures/revenuecat/renewal.json";
import subscriber from "../fixtures/revenuecat/subscriber.json";
import transfer from "../fixtures/revenuecat/transfer.json";
import { FREE_CAP_BYTES, SUBSCRIBER_CAP_BYTES } from "../../src/images";
import { rowCounts } from "../../src/storage/migrations";
import { REFRESH_TASK_TRIES } from "../../src/user-do";
import { REVENUECAT, type Session, call, inObject, interceptOutbound, signIn, userStub, uuid } from "./helpers";

const HOUR = 3_600_000;
const iso = (ms: number) => new Date(ms).toISOString();
const WEBHOOK_AUTH = "Bearer test-webhook-secret-not-a-secret";
const FIXTURE_USER = "A".repeat(43);
const FIXTURE_OTHER = "B".repeat(43);

// ---- RevenueCat stand-in at the fetch level: `rc` decides the subscriber record per user id; null = unreachable ----

type RcAnswer = { expires?: number | null; grace?: number | null; refunded?: number | null } | "none" | null;
let rc: (userId: string) => RcAnswer = () => null;
let rcCalls: string[] = [];
const record = (r: Exclude<RcAnswer, null>) => {
	const body = structuredClone(subscriber) as unknown as {
		subscriber: { entitlements: { [k: string]: Record<string, unknown> }; subscriptions: { [k: string]: Record<string, unknown> } };
	};
	if (r === "none") body.subscriber.entitlements = {};
	else {
		const e = body.subscriber.entitlements.recipes_pro;
		e.expires_date = r.expires === null ? null : iso(r.expires ?? Date.now() + 30 * 24 * HOUR);
		e.grace_period_expires_date = r.grace ? iso(r.grace) : null;
		body.subscriber.subscriptions.monthly.refunded_at = r.refunded ? iso(r.refunded) : null;
	}
	return body;
};

beforeEach(() => {
	rc = () => null;
	rcCalls = [];
	const spy = interceptOutbound();
	const base = spy.getMockImplementation()!;
	spy.mockImplementation(async (input, init) => {
		const url = new Request(input, init).url;
		if (!url.startsWith(REVENUECAT)) return base(input, init);
		const userId = decodeURIComponent(url.split("/").pop()!);
		rcCalls.push(userId);
		const answer = rc(userId);
		return answer === null ? new Response(null, { status: 503 }) : Response.json(record(answer));
	});
});
afterEach(() => {
	vi.restoreAllMocks();
	(env as unknown as { AI?: unknown }).AI = undefined;
});

// ---- helpers ----

const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;
type Me = { entitlement: { active: boolean; expiresAt: string | null }; quota: { freeImportUsed: boolean; monthUsed: number; monthLimit: number }; storage: { usedBytes: number; capBytes: number } };
const me = async (s: Session) => (await call("/me", { token: s.token })).json() as Promise<Me>;
const refresh = (s: Session) => call("/entitlement/refresh", { method: "POST", token: s.token });
const meta = (s: Session, key: string) =>
	inObject(s.userId, (_i, st) => (st.storage.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0]?.value ?? null));
const setMeta = (s: Session, key: string, value: string) =>
	inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value));
const store = (s: Session, e: { active: boolean; expiresAt: number | null }) =>
	setMeta(s, "entitlement", JSON.stringify({ active: e.active, expiresAt: e.expiresAt === null ? null : iso(e.expiresAt), gracePeriodExpiresAt: null, productId: "monthly" }));
const month = () => new Date().toISOString().slice(0, 7);
const setMonthUsed = (s: Session, n: number) =>
	inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO usage (period, count) VALUES (?, ?) ON CONFLICT (period) DO UPDATE SET count = excluded.count", month(), n));

const hook = (body: unknown, init: { auth?: string | null; headers?: Record<string, string> } = {}) => {
	const headers: Record<string, string> = { "content-type": "application/json", ...init.headers };
	if (init.auth !== null) headers.authorization = init.auth ?? WEBHOOK_AUTH;
	return call("/webhooks/revenuecat", { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body), headers });
};
/** A documented event (fixture) about the given accounts. */
const event = (fixture: unknown, userId: string, other = FIXTURE_OTHER) =>
	JSON.parse(JSON.stringify(fixture).replaceAll(FIXTURE_USER, userId).replaceAll(FIXTURE_OTHER, other));
/** Sends the webhook and runs the refresh task it scheduled. */
const deliver = async (fixture: unknown, s: Session) => {
	expect((await hook(event(fixture, s.userId))).status).toBe(200);
	await runDurableObjectAlarm(userStub(s.userId));
};

// Imports: the model answers with a recipe unless a test says otherwise.
function fakeAi(fail = false) {
	(env as unknown as { AI?: unknown }).AI = {
		run: async (model: string) => {
			if (fail) throw new Error("upstream 500");
			return model.includes("llama-3")
				? { response: { is_recipe: true, title: "Suppe", description: "", servings: 2, prepMinutes: null, cookMinutes: null, ingredients: [], steps: ["Kochen."], tags: [], language: "de" } }
				: { response: "{}" };
		},
	};
}
const startImport = (s: Session, key = uuid()) =>
	call("/imports", { method: "POST", token: s.token, body: JSON.stringify({ kind: "text", text: "Suppe\nKochen." }), headers: { "content-type": "application/json", "idempotency-key": key } });
const importDone = async (s: Session) => {
	const key = uuid();
	const res = await startImport(s, key);
	expect(res.status).toBe(202);
	await vi.waitFor(async () => expect(((await (await call(`/jobs/${key}`, { token: s.token })).json()) as { status: string }).status).toBe("done"), { timeout: 5000, interval: 20 });
};
const ready = async () => {
	fakeAi();
	const s = await signIn();
	await call("/me/consent", { method: "PUT", token: s.token, body: JSON.stringify({ version: 1 }), headers: { "content-type": "application/json" } });
	return s;
};
const subscribed = async (s: Session, r: Exclude<RcAnswer, null | "none"> = {}) => {
	rc = () => r;
	expect((await refresh(s)).status).toBe(200);
	await setMeta(s, "entitlement_client_at", "0");
};

// ---- images ----

const JPEG = [0xff, 0xd8, 0xff, 0xe0];
const jpeg = (size: number) => {
	const b = new Uint8Array(size);
	b.set(JPEG);
	return b;
};
const newRecipe = async (s: Session) => {
	const id = uuid();
	const input = { id, title: "T", description: "", servings: null, prepMinutes: null, cookMinutes: null, ingredients: [], steps: [], tags: [], sourceUrl: null, sourceType: "manual", language: "de", imageIds: [] };
	expect((await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify(input), headers: { "if-none-match": "*" } })).status).toBe(200);
	return id;
};
const upload = (s: Session, recipeId: string, bytes: Uint8Array, imageId = uuid()) =>
	call(`/recipes/${recipeId}/images/${imageId}`, { method: "PUT", token: s.token, body: bytes, headers: { "content-type": "image/jpeg" } });
const fillStorage = (s: Session, bytes: number) =>
	inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO images (id, recipe_id, mime, bytes, size) VALUES (?, ?, 'image/jpeg', x'00', ?)", uuid(), uuid(), bytes));

describe("billing rules (plan 4.6, AC20)", () => {
	it("active means now is before the stored expiry", async () => {
		const s = await signIn();
		const expires = Date.now() + HOUR;
		await subscribed(s, { expires });
		expect((await me(s)).entitlement).toEqual({ active: true, expiresAt: iso(expires).replace(/\.\d+Z$/, "Z") });
		// The stored expiry has passed and RevenueCat confirms it: inactive.
		await store(s, { active: true, expiresAt: Date.now() - 1000 });
		rc = () => ({ expires: Date.now() - 1000 });
		expect((await me(s)).entitlement).toEqual({ active: false, expiresAt: null });
	});

	it("grace period counts as active", async () => {
		const s = await ready();
		await importDone(s); // the free unit
		const grace = Date.now() + 3 * 24 * HOUR;
		rc = () => ({ expires: Date.now() - HOUR, grace });
		await deliver(billingIssue, s);
		expect((await me(s)).entitlement).toEqual({ active: true, expiresAt: iso(grace).replace(/\.\d+Z$/, "Z") });
		await importDone(s);
		expect((await me(s)).quota.monthUsed).toBe(1);
	});

	it("refund or revocation deactivates at once and used units are not refunded", async () => {
		const s = await ready();
		await subscribed(s);
		await importDone(s);
		await importDone(s);
		expect((await me(s)).quota).toMatchObject({ freeImportUsed: true, monthUsed: 1 });
		rc = () => ({ refunded: Date.now() - 1000 }); // expiry still a month ahead
		await deliver(refund, s);
		const after = await me(s);
		expect(after.entitlement.active).toBe(false);
		expect(after.quota).toMatchObject({ freeImportUsed: true, monthUsed: 1 });
		expect(await code(await startImport(s))).toBe("quota_exhausted");
	});

	it("expiry in the middle of a month blocks new reservations", async () => {
		const s = await ready();
		await subscribed(s);
		await importDone(s);
		await importDone(s);
		rc = () => ({ expires: Date.now() - 1000 });
		await deliver(expiration, s);
		const res = await startImport(s);
		expect([res.status, await code(res)]).toEqual([402, "quota_exhausted"]);
		expect((await me(s)).quota.monthUsed).toBe(1);
		// Without a webhook: the stored expiry passes, the reservation asks RevenueCat, which confirms the lapse.
		await store(s, { active: true, expiresAt: Date.now() - 1000 });
		expect((await startImport(s)).status).toBe(402);
		expect(rcCalls.length).toBeGreaterThan(0);
	});

	it("resubscribing in the same month keeps that month's count", async () => {
		const s = await ready();
		await setMeta(s, "free_import_used_at", "1");
		await setMonthUsed(s, 12);
		expect((await me(s)).entitlement.active).toBe(false);
		rc = () => ({});
		await deliver(initialPurchase, s);
		expect(await me(s)).toMatchObject({ entitlement: { active: true }, quota: { monthUsed: 12 } });
		await importDone(s);
		expect((await me(s)).quota.monthUsed).toBe(13);
	});

	it("the free lifetime import is used first, also for subscribers, and does not count against the 30", async () => {
		const s = await ready();
		await subscribed(s);
		await importDone(s);
		expect((await me(s)).quota).toEqual({ freeImportUsed: true, monthUsed: 0, monthLimit: 30 });
		await setMonthUsed(s, 29);
		await importDone(s);
		expect((await me(s)).quota.monthUsed).toBe(30);
		expect(await code(await startImport(s))).toBe("quota_exhausted");
	});

	it("with RevenueCat unreachable the stored entitlement is honoured until 24 hours after its expiry, then denied", async () => {
		const s = await ready();
		await setMeta(s, "free_import_used_at", "1");
		rc = () => null;
		await store(s, { active: true, expiresAt: Date.now() - 23 * HOUR });
		expect((await me(s)).entitlement.active).toBe(true);
		expect(rcCalls).toHaveLength(2); // one try and one retry
		await importDone(s);
		// A failed call is not repeated for a minute: the next requests use the stored entitlement directly.
		expect(rcCalls).toHaveLength(2);
		await store(s, { active: true, expiresAt: Date.now() - 25 * HOUR });
		expect((await me(s)).entitlement.active).toBe(false);
		expect(await code(await startImport(s))).toBe("quota_exhausted");
	});

	it("a lapsed subscriber above the free storage cap keeps everything and only new picture uploads answer 413", async () => {
		const s = await signIn();
		await subscribed(s);
		const recipeId = await newRecipe(s);
		const kept = uuid();
		await fillStorage(s, 60 * 1024 * 1024);
		expect((await upload(s, recipeId, jpeg(64), kept)).status).toBe(201);
		rc = () => ({ expires: Date.now() - 1000 });
		await deliver(expiration, s);
		expect((await me(s)).storage.capBytes).toBe(FREE_CAP_BYTES);
		expect((await call(`/images/${kept}`, { token: s.token })).status).toBe(200);
		expect((await call("/recipes", { token: s.token })).status).toBe(200);
		const res = await upload(s, recipeId, jpeg(64));
		expect([res.status, await code(res)]).toEqual([413, "storage_cap"]);
	});
});

describe("storage cap for subscribers (AC8)", () => {
	it("a subscriber's upload beyond the subscriber storage cap answers 413", async () => {
		const s = await signIn();
		await subscribed(s);
		const recipeId = await newRecipe(s);
		await fillStorage(s, SUBSCRIBER_CAP_BYTES - 100);
		const res = await upload(s, recipeId, jpeg(101));
		expect([res.status, await code(res)]).toEqual([413, "storage_cap"]);
		expect((await upload(s, recipeId, jpeg(100))).status).toBe(201);
	});
});

describe("failed imports on monthly units (docs/api.md M3)", () => {
	it("a failed import on a monthly unit is refunded and counts one attempt; on the free unit it counts none", async () => {
		const s = await ready();
		fakeAi(true);
		const free = uuid();
		await startImport(s, free);
		await inObject(s.userId, (instance) => instance.timeoutJob(free, Date.now()));
		const attempts = () => inObject(s.userId, (_i, st) => (st.storage.sql.exec<{ count: number }>("SELECT count FROM attempts").toArray()[0]?.count ?? 0));
		expect(await attempts()).toBe(0);
		await setMeta(s, "free_import_used_at", "1");
		await subscribed(s);
		const monthly = uuid();
		await startImport(s, monthly);
		await inObject(s.userId, (instance) => instance.timeoutJob(monthly, Date.now()));
		expect(await attempts()).toBe(1);
		expect((await me(s)).quota.monthUsed).toBe(0);
		// So the tenth counted attempt ends a subscriber's failing imports for the day.
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("UPDATE attempts SET count = 10"));
		const res = await startImport(s);
		expect([res.status, await code(res)]).toEqual([429, "attempt_limit"]);
	});
});

describe("POST /entitlement/refresh", () => {
	it("401 without a session", async () => {
		expect((await call("/entitlement/refresh", { method: "POST" })).status).toBe(401);
	});

	it("reads RevenueCat now and answers the entitlement part of GET /me in the contract shape", async () => {
		const s = await signIn();
		rc = () => ({});
		const res = await refresh(s);
		expect(res.status).toBe(200);
		const body = (await res.json()) as typeof refreshFixture;
		expect(Object.keys(body)).toEqual(Object.keys(refreshFixture));
		expect(Object.keys(body.entitlement)).toEqual(Object.keys(refreshFixture.entitlement));
		expect(body).toMatchObject({ entitlement: { active: true }, storage: { capBytes: SUBSCRIBER_CAP_BYTES } });
		const m = await me(s);
		expect([m.entitlement, m.quota, m.storage]).toEqual([body.entitlement, body.quota, body.storage]);
		expect(rcCalls).toEqual([s.userId]);
	});

	it("is limited to one call per 5 seconds per user", async () => {
		const s = await signIn();
		rc = () => "none";
		expect((await refresh(s)).status).toBe(200);
		const again = await refresh(s);
		expect([again.status, await code(again)]).toEqual([429, "rate_limited"]);
	});

	it("503 billing_unavailable when RevenueCat cannot be reached; a background refresh is scheduled", async () => {
		const s = await signIn();
		const res = await refresh(s);
		expect([res.status, await code(res)]).toEqual([503, "billing_unavailable"]);
		const task = await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT kind FROM tasks WHERE id = 'entitlement_refresh'").toArray());
		expect(task).toEqual([{ kind: "entitlement_refresh" }]);
	});
});

describe("RevenueCat webhook (AC20)", () => {
	const fixtures = { initialPurchase, renewal, billingIssue, cancellation, refund, expiration, transfer };

	it("401 for a missing or wrong authorization, before the body is read", async () => {
		const s = await signIn();
		const body = event(initialPurchase, s.userId);
		expect((await hook(body, { auth: null })).status).toBe(401);
		expect((await hook(body, { auth: "Bearer wrong" })).status).toBe(401);
		const stream = new ReadableStream({ start: (c) => (c.enqueue(new TextEncoder().encode("{}")), c.close()) });
		const res = await call("/webhooks/revenuecat", { method: "POST", body: stream, headers: { authorization: "Bearer wrong" }, duplex: "half" } as RequestInit);
		expect(res.status).toBe(401);
		expect(rcCalls).toEqual([]);
	});

	it("411 without Content-Length, 413 over 64 KB, 400 for a body that is not an event", async () => {
		const stream = new ReadableStream({ start: (c) => (c.enqueue(new TextEncoder().encode("{}")), c.close()) });
		const res = await call("/webhooks/revenuecat", { method: "POST", body: stream, headers: { authorization: WEBHOOK_AUTH }, duplex: "half" } as RequestInit);
		expect([res.status, await code(res)]).toEqual([411, "length_required"]);
		expect((await hook("x".repeat(64 * 1024 + 1))).status).toBe(413);
		expect((await hook("{")).status).toBe(400);
		expect((await hook({ event: { app_user_id: FIXTURE_USER } })).status).toBe(400);
	});

	for (const [name, fixture] of Object.entries(fixtures)) {
		it(`${name}: 200, then a refresh reads the REST record instead of trusting the payload`, async () => {
			const s = await signIn();
			rc = () => ({});
			const res = await hook(event(fixture, s.userId));
			expect([res.status, await res.json()]).toEqual([200, { received: true }]);
			expect(rcCalls).toEqual([]); // answered before RevenueCat is asked
			await runDurableObjectAlarm(userStub(s.userId));
			expect(rcCalls).toContain(s.userId);
			expect((await me(s)).entitlement.active).toBe(true);
		});
	}

	it("a transfer refreshes both accounts", async () => {
		const [from, to] = [await signIn(), await signIn()];
		rc = (id) => (id === to.userId ? {} : "none");
		await store(from, { active: true, expiresAt: Date.now() + HOUR });
		expect((await hook(event(transfer, to.userId, from.userId))).status).toBe(200);
		await runDurableObjectAlarm(userStub(from.userId));
		await runDurableObjectAlarm(userStub(to.userId));
		expect(rcCalls.sort()).toEqual([from.userId, to.userId].sort());
		expect([(await me(from)).entitlement.active, (await me(to)).entitlement.active]).toEqual([false, true]);
	});

	it("ignores ids of another shape and never creates storage for an id without an account", async () => {
		const unknown = "C".repeat(43);
		expect((await hook(event(initialPurchase, "$RCAnonymousID:8069238d6049ce87cc529853916d624c"))).status).toBe(200);
		expect((await hook(event(initialPurchase, unknown))).status).toBe(200);
		expect(await inObject(unknown, (_i, st) => rowCounts(st.storage.sql).size)).toBe(0);
		expect(rcCalls).toEqual([]);
	});

	it("replays and out-of-order events are harmless: the state is RevenueCat's, nothing is charged", async () => {
		const s = await signIn();
		rc = () => ({ expires: Date.now() - 1000 });
		// Newest first (expiration), then an older purchase and a replay of it.
		await deliver(expiration, s);
		await deliver(initialPurchase, s);
		await deliver(initialPurchase, s);
		expect(await me(s)).toMatchObject({ entitlement: { active: false }, quota: { freeImportUsed: false, monthUsed: 0 } });
	});

	it("an account being deleted is left alone", async () => {
		const s = await signIn();
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("UPDATE meta SET value = 'deleting' WHERE key = 'state'"));
		expect((await hook(event(renewal, s.userId))).status).toBe(200);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM tasks WHERE id = 'entitlement_refresh'").one().n)).toBe(0);
	});

	it("a refresh that fails is retried with backoff, and given up after the last try", async () => {
		const s = await signIn();
		expect((await hook(event(renewal, s.userId))).status).toBe(200);
		await runDurableObjectAlarm(userStub(s.userId));
		const task = () => inObject(s.userId, (_i, st) => st.storage.sql.exec<{ attempts: number; due_at: number }>("SELECT attempts, due_at FROM tasks WHERE id = 'entitlement_refresh'").toArray()[0]);
		const first = await task();
		expect(first?.attempts).toBe(1);
		expect(first!.due_at).toBeGreaterThan(Date.now());
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		await inObject(s.userId, async (_i, st) => {
			st.storage.sql.exec("UPDATE tasks SET attempts = ?, due_at = ? WHERE id = 'entitlement_refresh'", REFRESH_TASK_TRIES, Date.now() - 1);
			await st.storage.setAlarm(Date.now() - 1);
		});
		await runDurableObjectAlarm(userStub(s.userId));
		expect(await task()).toBeUndefined();
		expect(write.mock.calls.map((c) => c[0]?.blobs)).toContainEqual(["entitlement_refresh", "webhook", "gave_up"]);
	});

	it("metrics carry event names and codes only: no user id, no price, no payload", async () => {
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		const s = await signIn();
		rc = () => ({});
		await deliver(initialPurchase, s);
		expect((await hook({ event: { type: "SOMETHING_NEW", app_user_id: s.userId } })).status).toBe(200);
		const blobs = write.mock.calls.map((c) => c[0]?.blobs);
		expect(blobs).toContainEqual(["billing_webhook", "INITIAL_PURCHASE"]);
		expect(blobs).toContainEqual(["billing_webhook", "OTHER"]);
		expect(blobs).toContainEqual(["entitlement_refresh", "webhook", "active"]);
		const all = JSON.stringify(write.mock.calls);
		for (const secret of [s.userId, "monthly", "1000000000000001", "EUR", "SANDBOX"]) expect(all).not.toContain(secret);
	});
});

describe("local subscription switch (local entry only)", () => {
	const key = env.REVENUECAT_API_KEY;
	afterEach(() => {
		(env as { REVENUECAT_API_KEY?: string }).REVENUECAT_API_KEY = key;
	});
	const toggle = (s: Session | null, active: unknown) => {
		const body = JSON.stringify({ active });
		return call("/testing/entitlement", { method: "POST", token: s?.token, body, headers: { "content-type": "application/json" } });
	};

	it("without a RevenueCat key, POST /v1/testing/entitlement switches the signed-in user's subscription on and off", async () => {
		(env as { REVENUECAT_API_KEY?: string }).REVENUECAT_API_KEY = undefined;
		const s = await signIn();
		const on = await toggle(s, true);
		expect(on.status).toBe(200);
		expect(((await on.json()) as Me).entitlement.active).toBe(true);
		expect((await me(s)).storage.capBytes).toBe(SUBSCRIBER_CAP_BYTES);
		await setMeta(s, "entitlement_client_at", "0");
		expect(((await (await refresh(s)).json()) as Me).entitlement.active).toBe(true);
		expect((((await (await toggle(s, false)).json()) as Me).entitlement.active)).toBe(false);
		expect((await me(s)).entitlement.active).toBe(false);
		expect(rcCalls).toEqual([]);
	});

	it("401 without a session, 400 for a bad body", async () => {
		const s = await signIn();
		expect((await toggle(null, true)).status).toBe(401);
		expect((await toggle(s, "yes")).status).toBe(400);
	});
});
