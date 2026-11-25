import { env, runDurableObjectAlarm } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import suspendedFixture from "../../../contract/fixtures/error_suspended.json";
import meSuspendedFixture from "../../../contract/fixtures/me_suspended.json";
import { type Session, call, inObject, interceptOutbound, signIn, userStub, uuid } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => {
	vi.restoreAllMocks();
	(env as unknown as { AI?: unknown }).AI = undefined;
});

const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
const body = (v: unknown) => ({ body: JSON.stringify(v), headers: { "content-type": "application/json" } });
const suspend = (userId: string, reason: "abusive_content" | null = "abusive_content") => userStub(userId).setSuspended(reason, "operator@example.org");

async function withRecipeAndImage(): Promise<{ s: Session; recipeId: string; imageId: string }> {
	const s = await signIn();
	const recipeId = uuid();
	const imageId = uuid();
	expect((await call(`/recipes/${recipeId}`, { method: "PUT", token: s.token, ...body({ ...inputFixture, id: recipeId, imageIds: [] }), headers: { "content-type": "application/json", "if-none-match": "*" } })).status).toBe(200);
	expect((await call(`/recipes/${recipeId}/images/${imageId}`, { method: "PUT", token: s.token, body: JPEG, headers: { "content-type": "image/jpeg" } })).status).toBe(201);
	return { s, recipeId, imageId };
}

describe("suspension (plan 4.4, AC43)", () => {
	it("403 with the reason code on every route except the exempt ones, which keep working", async () => {
		const { s, recipeId, imageId } = await withRecipeAndImage();
		expect(await suspend(s.userId)).toEqual({ status: 200, body: { state: "suspended", reason: "abusive_content" } });

		const blocked: [string, RequestInit][] = [
			[`/recipes/${recipeId}`, { method: "PUT", ...body({ ...inputFixture, id: recipeId }), headers: { "content-type": "application/json", "if-match": '"1"' } }],
			[`/recipes/${recipeId}`, { method: "DELETE" }],
			[`/recipes/${recipeId}/images/${uuid()}`, { method: "PUT", body: JPEG, headers: { "content-type": "image/jpeg" } }],
			["/me/consent", { method: "PUT", ...body({ version: 1 }) }],
			["/me/consent", { method: "DELETE" }],
			["/imports", { method: "POST", ...body({ kind: "text", text: "Pancakes" }), headers: { "content-type": "application/json", "idempotency-key": uuid() } }],
			[`/jobs/${uuid()}`, {}],
			[`/recipes/${recipeId}/translations`, { method: "POST", ...body({ language: "en" }), headers: { "content-type": "application/json", "idempotency-key": uuid() } }],
			[`/recipes/${recipeId}/shares`, { method: "POST" }],
			["/shares/0123456789abcdef0123456789abcdef", { method: "DELETE" }],
			[`/recipes/${recipeId}/pdf`, {}],
			["/reports", { method: "POST", ...body({ kind: "import_result", jobId: uuid(), reason: "other" }) }],
			["/entitlement/refresh", { method: "POST" }],
		];
		for (const [path, init] of blocked) {
			const res = await call(path, { ...init, token: s.token });
			expect([path, init.method ?? "GET", res.status]).toEqual([path, init.method ?? "GET", 403]);
			const err = (await res.json()) as typeof suspendedFixture;
			expect(Object.keys(err.error).sort()).toEqual(Object.keys(suspendedFixture.error).sort());
			expect(err.error).toMatchObject({ code: "account_suspended", reason: "abusive_content" });
		}

		const me = await call("/me", { token: s.token });
		expect(me.status).toBe(200);
		const meBody = (await me.json()) as typeof meSuspendedFixture;
		expect(Object.keys(meBody)).toEqual(Object.keys(meSuspendedFixture));
		expect(meBody).toMatchObject({ state: "suspended", suspension: { reason: "abusive_content" } });
		const list = (await (await call("/recipes", { token: s.token })).json()) as { recipes: { id: string }[] };
		expect(list.recipes.map((r) => r.id)).toEqual([recipeId]);
		expect((await call(`/images/${imageId}`, { token: s.token })).status).toBe(200);
		expect((await call("/session", { method: "DELETE", token: s.token })).status).toBe(204);
	});

	it("DELETE /account still works for a suspended user", async () => {
		const s = await signIn();
		await suspend(s.userId);
		expect((await call("/account", { method: "DELETE", token: s.token })).status).toBe(202);
	});

	it("unsuspending restores access; each admin action writes one audit row and one metric", async () => {
		const s = await signIn();
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		await suspend(s.userId);
		expect((await call("/me/consent", { method: "DELETE", token: s.token })).status).toBe(403);
		expect(await suspend(s.userId, null)).toEqual({ status: 200, body: { state: "active", reason: null } });
		expect((await call("/me/consent", { method: "DELETE", token: s.token })).status).toBe(204);
		const audit = await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT actor, action, reason FROM audit ORDER BY id").toArray());
		expect(audit).toEqual([
			{ actor: "operator@example.org", action: "suspend", reason: "abusive_content" },
			{ actor: "operator@example.org", action: "unsuspend", reason: null },
		]);
		const admin = write.mock.calls.map((c) => c[0]?.blobs).filter((b) => b?.[0] === "admin_action");
		expect(admin).toEqual([
			["admin_action", "suspend", "abusive_content"],
			["admin_action", "unsuspend", "none"],
		]);
		expect(JSON.stringify(write.mock.calls)).not.toContain("operator@example.org");
	});

	it("an id without an account answers 404 and gets no storage", async () => {
		const id = "Z".repeat(43);
		expect(await suspend(id)).toMatchObject({ status: 404 });
		expect(await inObject(id, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM sqlite_master").one().n)).toBe(0);
	});

	it("a job already running at suspension finishes and is charged; no new job starts", async () => {
		const s = await signIn();
		let release: (v: unknown) => void = () => undefined;
		const answer = new Promise((r) => (release = r));
		(env as unknown as { AI?: unknown }).AI = {
			run: async (model: string) => {
				await answer;
				return model.includes("llama-3")
					? { response: { is_recipe: true, title: "Soup", description: "", servings: 2, prepMinutes: null, cookMinutes: null, ingredients: [], steps: ["Cook."], tags: [], language: "en" } }
					: { response: "{}" };
			},
		};
		await call("/me/consent", { method: "PUT", token: s.token, ...body({ version: 1 }) });
		const key = uuid();
		expect((await call("/imports", { method: "POST", token: s.token, ...body({ kind: "text", text: "Soup\nCook." }), headers: { "content-type": "application/json", "idempotency-key": key } })).status).toBe(202);
		const run = runDurableObjectAlarm(userStub(s.userId));
		await vi.waitFor(async () => expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT status FROM jobs").one().status)).toBe("running"), { timeout: 5000, interval: 20 });
		await suspend(s.userId, "quota_abuse");
		release(null);
		await run;
		const job = await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT status, charge FROM jobs").one());
		expect(job).toEqual({ status: "done", charge: "free" });
		const again = await call("/imports", { method: "POST", token: s.token, ...body({ kind: "text", text: "More" }), headers: { "content-type": "application/json", "idempotency-key": uuid() } });
		expect(again.status).toBe(403);
	});
});
