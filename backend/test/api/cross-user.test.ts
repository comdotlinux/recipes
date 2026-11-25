import { env, listDurableObjectIds } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import { issueNonce } from "../../src/auth";
import { newShareId, newShareToken } from "../../src/share";
import { testIdToken } from "../../src/testing/test-idp";
import { TURNSTILE_OK, type Session, call, inObject, interceptOutbound, json, nonce, signIn, uuid } from "./helpers";

/** Milestones M4 and M5: AC5 (every new route answers 404 for another user's ids) and AC6 (every token kind). */

beforeEach(() => {
	interceptOutbound();
	(env as unknown as { BROWSER?: unknown }).BROWSER = {
		quickAction: async () => new Response(new TextEncoder().encode("%PDF"), { headers: { "content-type": "application/pdf" } }),
	};
	(env as unknown as { AI?: unknown }).AI = {
		run: async () => ({ response: { title: "Potato salad", description: "", ingredients: inputFixture.ingredients, steps: ["Boil."], tags: ["Side"] } }),
	};
});
afterEach(() => {
	vi.restoreAllMocks();
	(env as unknown as { BROWSER?: unknown }).BROWSER = undefined;
	(env as unknown as { AI?: unknown }).AI = undefined;
});

const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;
const objectIds = async () => (await listDurableObjectIds(env.USER_DO)).map((i) => i.toString());
const setMeta = (s: Session, key: string, value: string) =>
	inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value));
/** Subscribed, consent given, one recipe. */
const ready = async () => {
	const s = await signIn();
	await setMeta(s, "entitlement", JSON.stringify({ active: true, expiresAt: null }));
	await setMeta(s, "consent_version", "1");
	const recipeId = uuid();
	await call(`/recipes/${recipeId}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, id: recipeId }), headers: { "if-none-match": "*" } });
	return { s, recipeId };
};
const shareOf = async (s: Session, recipeId: string) =>
	(await (await call(`/recipes/${recipeId}/shares`, { method: "POST", token: s.token })).json()) as { shareId: string; token: string };

describe("AC5: M4 and M5 routes with A's token on B's ids", () => {
	it("translation, share creation, share revocation, PDF and the translation job answer 404 and change nothing of B's", async () => {
		const a = await ready();
		const b = await ready();
		const jobKey = uuid();
		const started = await call(`/recipes/${b.recipeId}/translations`, { method: "POST", token: b.s.token, ...json({ language: "en" }), headers: { "content-type": "application/json", "idempotency-key": jobKey } });
		expect(started.status).toBe(202);
		const share = await shareOf(b.s, b.recipeId);

		const asA: [string, RequestInit][] = [
			[`/recipes/${b.recipeId}/translations`, { method: "POST", body: JSON.stringify({ language: "en" }), headers: { "content-type": "application/json", "idempotency-key": uuid() } }],
			[`/recipes/${b.recipeId}/shares`, { method: "POST" }],
			[`/shares/${share.shareId}`, { method: "DELETE" }],
			[`/recipes/${b.recipeId}/pdf`, { method: "GET" }],
			[`/jobs/${jobKey}`, { method: "GET" }],
		];
		for (const [path, init] of asA) {
			const res = await call(path, { ...init, token: a.s.token });
			expect([path, init.method, res.status, await code(res)]).toEqual([path, init.method, 404, "not_found"]);
		}
		// B's link still works, B's quota and data are untouched.
		const other = await signIn();
		expect((await call(`/shares/${share.token}`, { token: other.token })).status).toBe(200);
		const me = (await (await call("/me", { token: a.s.token })).json()) as { quota: { monthUsed: number } };
		expect(me.quota.monthUsed).toBe(0);
		expect(await inObject(a.s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM shares").one().n)).toBe(0);
	});
});

describe("AC6: share tokens", () => {
	it("forged (another key, flipped byte, wrong length) answers 404 on preview, accept and report, and reaches no object", async () => {
		const a = await signIn();
		const victim = "W".repeat(42) + "w";
		const genuine = await newShareToken(victim, newShareId(), env);
		const forged = [await newShareToken(victim, newShareId(), { SERVER_KEY: "attacker" }), `${genuine.slice(0, 50)}${genuine[50] === "a" ? "b" : "a"}${genuine.slice(51)}`, genuine.slice(0, 100)];
		for (const t of forged) {
			expect((await call(`/shares/${t}`, { token: a.token })).status).toBe(404);
			expect((await call(`/shares/${t}/accept`, { method: "POST", token: a.token })).status).toBe(404);
			expect((await call("/reports", { method: "POST", token: a.token, ...json({ kind: "share", shareToken: t, reason: "other" }) })).status).toBe(404);
		}
		expect(await objectIds()).not.toContain(env.USER_DO.idFromName(victim).toString());
		// A genuine token for an id without an account answers 404 and creates no tables there.
		expect((await call(`/shares/${genuine}`, { token: a.token })).status).toBe(404);
		const tables = await inObject(victim, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM sqlite_master WHERE type = 'table' AND name NOT GLOB '_cf_*' AND name NOT GLOB 'sqlite_*'").one().n);
		expect(tables).toBe(0);
	});

	it("expired and revoked answer 410 and never recipe data; a replayed accept returns the same copy and counts once", async () => {
		const { s, recipeId } = await ready();
		const b = await signIn();
		const link = await shareOf(s, recipeId);
		const first = (await (await call(`/shares/${link.token}/accept`, { method: "POST", token: b.token })).json()) as { id: string };
		const replay = (await (await call(`/shares/${link.token}/accept`, { method: "POST", token: b.token })).json()) as { id: string };
		expect(replay.id).toBe(first.id);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM share_accepts").one().n)).toBe(1);
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("UPDATE shares SET expires_at = ?", Date.now() - 1));
		const c = await signIn();
		const expired = await call(`/shares/${link.token}`, { token: c.token });
		expect([expired.status, await code(expired)]).toEqual([410, "share_gone"]);
		const revoked = await shareOf(s, recipeId);
		await call(`/shares/${revoked.shareId}`, { method: "DELETE", token: s.token });
		const res = await call(`/shares/${revoked.token}/accept`, { method: "POST", token: c.token });
		const text = await res.text();
		expect([res.status, text.includes("Kartoffel")]).toEqual([410, false]);
	});

	it("wrong route: a share token as a bearer token or a nonce, a session token or nonce as a share token, a share token as a share id", async () => {
		const { s, recipeId } = await ready();
		const link = await shareOf(s, recipeId);
		expect((await call("/me", { token: link.token })).status).toBe(401);
		const viaShare = await call("/session", { method: "POST", ...json({ provider: "test", idToken: testIdToken("x", link.token), nonce: link.token, turnstileToken: TURNSTILE_OK, ageConfirmed: true }) });
		expect([viaShare.status, await code(viaShare)]).toEqual([401, "invalid_nonce"]);
		const b = await signIn();
		expect((await call(`/shares/${b.token}`, { token: b.token })).status).toBe(404);
		expect((await call(`/shares/${await issueNonce(env, Date.now())}/accept`, { method: "POST", token: b.token })).status).toBe(404);
		expect(await code(await call(`/shares/${link.token}`, { method: "DELETE", token: s.token }))).toBe("invalid_id");
	});
});

describe("AC6: session tokens and nonces on the new routes", () => {
	const routes = (recipeId: string, token: string): [string, RequestInit][] => [
		[`/recipes/${recipeId}/translations`, { method: "POST", body: JSON.stringify({ language: "en" }), headers: { "content-type": "application/json", "idempotency-key": uuid() } }],
		[`/recipes/${recipeId}/shares`, { method: "POST" }],
		[`/shares/${newShareId()}`, { method: "DELETE" }],
		[`/shares/${token}`, { method: "GET" }],
		[`/shares/${token}/accept`, { method: "POST" }],
		[`/recipes/${recipeId}/pdf`, { method: "GET" }],
	];

	it("forged, expired and revoked (replayed after logout) session tokens get 401 on every new route", async () => {
		const { s, recipeId } = await ready();
		const link = await shareOf(s, recipeId);
		const b = await signIn();
		const forged = `v1.${b.userId}.${"S".repeat(42)}w.${"T".repeat(42)}w`;
		const expiring = await signIn();
		await inObject(expiring.userId, (_i, st) => st.storage.sql.exec("UPDATE sessions SET expires_at = ?", Date.now() - 1));
		const loggedOut = await signIn();
		await call("/session", { method: "DELETE", token: loggedOut.token });
		for (const token of [forged, expiring.token, loggedOut.token]) {
			for (const [path, init] of routes(recipeId, link.token)) {
				const res = await call(path, { ...init, token });
				expect([path, res.status]).toEqual([path, 401]);
			}
		}
		// None of these touched the owner's link.
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM share_accepts").one().n)).toBe(0);
	});

	it("a nonce is single use, expires, and is not a session token", async () => {
		const n = await nonce();
		expect((await call("/me", { token: n })).status).toBe(401);
		const signInWith = (sub: string, value: string) =>
			call("/session", { method: "POST", ...json({ provider: "test", idToken: testIdToken(sub, value), nonce: value, turnstileToken: TURNSTILE_OK, ageConfirmed: true }) });
		expect((await signInWith("n-user", n)).status).toBe(200);
		expect(await code(await signInWith("n-user", n))).toBe("invalid_nonce");
		const old = await issueNonce(env, Date.now() - 10 * 60 * 1000);
		expect(await code(await signInWith("n-user-2", old))).toBe("invalid_nonce");
		const forged = await issueNonce({ SERVER_KEY: "attacker" }, Date.now());
		expect(await code(await signInWith("n-user-3", forged))).toBe("invalid_nonce");
	});
});

describe("share expiry cleanup", () => {
	it("expired links and their recipients are removed by the expiry task", async () => {
		const { s, recipeId } = await ready();
		const link = await shareOf(s, recipeId);
		await call(`/shares/${link.token}/accept`, { method: "POST", token: (await signIn()).token });
		await inObject(s.userId, async (instance, st) => {
			st.storage.sql.exec("UPDATE shares SET expires_at = ?", Date.now() - 1);
			await instance.expire(Date.now());
			expect(st.storage.sql.exec("SELECT (SELECT count(*) FROM shares) AS s, (SELECT count(*) FROM share_accepts) AS a").one()).toEqual({ s: 0, a: 0 });
		});
	});
});
