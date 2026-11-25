import { env, runDurableObjectAlarm } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import translatedFixture from "../../../contract/fixtures/recipe_translated.json";
import pendingFixture from "../../../contract/fixtures/translation_job_pending.json";
import requestFixture from "../../../contract/fixtures/translation_request.json";
import { GATEWAY_HEADERS } from "../../src/ai/gateway";
import { STRUCTURE_MODELS } from "../../src/ai/models";
import { toTranslation } from "../../src/ai/translate";
import { RecipeInput } from "../../src/recipes";
import { type Session, call, inObject, interceptOutbound, signIn, userStub, uuid } from "./helpers";

type Job = typeof pendingFixture & { status: string };
type Recipe = typeof translatedFixture;

const english = translatedFixture.translations[0];
const answer = { title: english.title, description: english.description, ingredients: english.ingredients, steps: english.steps, tags: english.tags };

let calls: { model: string; inputs: Record<string, unknown>; options: Record<string, unknown> }[] = [];
function fakeAi(plan: (inputs: Record<string, unknown>) => unknown) {
	calls = [];
	(env as unknown as { AI?: unknown }).AI = {
		run: async (model: string, inputs: Record<string, unknown>, options: Record<string, unknown>) => {
			calls.push({ model, inputs, options });
			return plan(inputs);
		},
	};
}

beforeEach(() => {
	interceptOutbound();
	fakeAi(() => ({ response: answer }));
});
afterEach(() => {
	vi.restoreAllMocks();
	(env as unknown as { AI?: unknown }).AI = undefined;
});

const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;
const setMeta = (s: Session, key: string, value: string) =>
	inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value));
const consent = (s: Session) => call("/me/consent", { method: "PUT", token: s.token, body: JSON.stringify({ version: 1 }), headers: { "content-type": "application/json" } });
const subscribe = (s: Session) => setMeta(s, "entitlement", JSON.stringify({ active: true, expiresAt: null }));
const createRecipe = async (s: Session, id = uuid()) => {
	const res = await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, id }), headers: { "if-none-match": "*" } });
	expect(res.status).toBe(200);
	return id;
};
const translate = (s: Session, recipeId: string, key = uuid(), body: unknown = requestFixture) =>
	call(`/recipes/${recipeId}/translations`, { method: "POST", token: s.token, body: JSON.stringify(body), headers: { "content-type": "application/json", "idempotency-key": key } });
const job = async (s: Session, key: string) => (await call(`/jobs/${key}`, { token: s.token })).json() as Promise<Job>;
const settle = async (s: Session, key: string) => {
	await vi.waitFor(async () => expect(["pending", "running"]).not.toContain((await job(s, key)).status), { timeout: 5000, interval: 20 });
	return job(s, key);
};
const recipes = async (s: Session, since = 0) => ((await (await call(`/recipes?since=${since}`, { token: s.token })).json()) as { recipes: Recipe[]; rev: number }).recipes;
const monthUsed = async (s: Session) => ((await (await call("/me", { token: s.token })).json()) as { quota: { monthUsed: number; freeImportUsed: boolean } }).quota;
const attempts = (s: Session) => inObject(s.userId, (_i, st) => (st.storage.sql.exec("SELECT count FROM attempts").toArray()[0]?.count as number | undefined) ?? 0);
/** Signed in, consent given, subscribed, one recipe. */
const ready = async () => {
	const s = await signIn();
	expect((await consent(s)).status).toBe(200);
	await subscribe(s);
	return { s, recipeId: await createRecipe(s) };
};

describe("translation (AC22)", () => {
	it("202 pending in the contract shape, then done; the recipe carries the translation with the same counts and quantities", async () => {
		const { s, recipeId } = await ready();
		const key = uuid();
		const res = await translate(s, recipeId, key);
		expect(res.status).toBe(202);
		const started = (await res.json()) as Job;
		expect(Object.keys(started)).toEqual(Object.keys(pendingFixture));
		expect({ ...started, createdAt: "T" }).toEqual({ ...pendingFixture, id: key, recipeId, createdAt: "T" });
		expect((await settle(s, key)).status).toBe("done");

		const [recipe] = await recipes(s);
		expect(Object.keys(recipe)).toEqual(Object.keys(translatedFixture));
		expect({ ...recipe, id: "X", rev: 0, updatedAt: "T" }).toEqual({ ...translatedFixture, id: "X", rev: 0, updatedAt: "T" });
		expect(recipe.rev).toBe(2); // the translation is a new revision of the recipe, so a delta pull delivers it
		const t = recipe.translations[0];
		expect([t.ingredients.length, t.steps.length, t.tags.length]).toEqual([recipe.ingredients.length, recipe.steps.length, recipe.tags.length]);
		expect(t.ingredients.map((i) => [i.quantity, i.unit])).toEqual(recipe.ingredients.map((i) => [i.quantity, i.unit]));
		expect((await recipes(s, 1)).map((r) => r.id)).toEqual([recipeId]);
		expect(await monthUsed(s)).toEqual({ freeImportUsed: false, monthUsed: 1, monthLimit: 30 });
	});

	it("the request goes through the gateway without logging or cache, recipe text as data, no tools; input gone afterwards", async () => {
		const { s, recipeId } = await ready();
		const key = uuid();
		await translate(s, recipeId, key);
		await settle(s, key);
		expect(calls.map((c) => c.model)).toEqual([STRUCTURE_MODELS.primary]);
		expect(calls[0].options).toMatchObject({ gateway: { id: "recipes", skipCache: true, metadata: { feature: "translate" } }, extraHeaders: GATEWAY_HEADERS });
		const messages = calls[0].inputs.messages as { role: string; content: string }[];
		expect(messages.map((m) => m.role)).toEqual(["system", "user"]);
		expect(messages[0].content).toContain("into English");
		expect(JSON.parse(messages[1].content)).toEqual({ title: "Kartoffelsalat", description: "", ingredients: inputFixture.ingredients, steps: inputFixture.steps, tags: inputFixture.tags });
		expect(calls[0].inputs).not.toHaveProperty("tools");
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT input FROM jobs").one().input)).toBeNull();
	});

	it("is marked stale when the source changes, and not when it is saved unchanged", async () => {
		const { s, recipeId } = await ready();
		const key = uuid();
		await translate(s, recipeId, key);
		await settle(s, key);
		const put = (title: string, rev: number) =>
			call(`/recipes/${recipeId}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, id: recipeId, title }), headers: { "if-match": `"${rev}"` } });
		const same = (await (await put(inputFixture.title, 2)).json()) as Recipe;
		expect(same.translations[0].stale).toBe(false);
		const changed = (await (await put("Kartoffelsalat mit Gurke", 3)).json()) as Recipe;
		expect(changed.translations[0].stale).toBe(true);
		expect((await recipes(s))[0].translations[0].stale).toBe(true);
		// Translating again replaces it with a fresh one.
		const again = uuid();
		await translate(s, recipeId, again);
		expect((await settle(s, again)).status).toBe("done");
		expect((await recipes(s))[0].translations.map((t) => [t.language, t.stale])).toEqual([["en", false]]);
		// Deleting the recipe removes its translations.
		await call(`/recipes/${recipeId}`, { method: "DELETE", token: s.token });
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM translations").one().n)).toBe(0);
	});

	it("a recipe changed while the job ran gets the translation already marked stale; a deleted one ends as failed with a refund", async () => {
		const { s, recipeId } = await ready();
		// A plain flag polled with timers: a promise resolved from the test would be I/O of another context.
		let released = false;
		const release = () => (released = true);
		const held = async () => {
			while (!released) await new Promise((r) => setTimeout(r, 10));
			return { response: answer };
		};
		fakeAi(held);
		const key = uuid();
		await translate(s, recipeId, key);
		await vi.waitFor(() => expect(calls).toHaveLength(1), { timeout: 5000, interval: 20 });
		const body = JSON.stringify({ ...inputFixture, id: recipeId, steps: ["Kartoffeln schälen und kochen."] });
		expect((await call(`/recipes/${recipeId}`, { method: "PUT", token: s.token, body, headers: { "if-match": '"1"' } })).status).toBe(200);
		release();
		expect((await settle(s, key)).status).toBe("done");
		expect((await recipes(s))[0].translations[0]).toMatchObject({ language: "en", stale: true });

		const deleted = await createRecipe(s);
		released = false;
		fakeAi(held);
		const key2 = uuid();
		await translate(s, deleted, key2);
		await vi.waitFor(() => expect(calls).toHaveLength(1), { timeout: 5000, interval: 20 });
		await call(`/recipes/${deleted}`, { method: "DELETE", token: s.token });
		release();
		expect((await settle(s, key2)).status).toBe("failed");
		expect((await monthUsed(s)).monthUsed).toBe(1);
	});

	it("finishes after the client disconnected and is charged exactly once; a retry with the same key charges nothing", async () => {
		const { s, recipeId } = await ready();
		const key = uuid();
		await translate(s, recipeId, key); // the response is never read again: the alarm runs the job
		await settle(s, key);
		const again = await translate(s, recipeId, key);
		expect([again.status, ((await again.json()) as Job).status]).toEqual([202, "done"]);
		expect(calls).toHaveLength(1);
		expect((await monthUsed(s)).monthUsed).toBe(1);
		// The report action is reachable for a translation (AC33).
		const report = JSON.stringify({ kind: "translation", jobId: key, reason: "wrong_content", note: "" });
		expect((await call("/reports", { method: "POST", token: s.token, body: report, headers: { "content-type": "application/json" } })).status).toBe(204);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT kind, job_key FROM reports").toArray())).toEqual([{ kind: "translation", job_key: key }]);
	});
});

describe("translation charging and refusals", () => {
	it("403 consent_required without consent; nothing is called or charged", async () => {
		const s = await signIn();
		await subscribe(s);
		const recipeId = await createRecipe(s);
		const res = await translate(s, recipeId);
		expect([res.status, await code(res)]).toEqual([403, "consent_required"]);
		expect(calls).toEqual([]);
	});

	it("402 subscription_required for a free user, even with the free import unused (a translation never takes it)", async () => {
		const s = await signIn();
		await consent(s);
		const recipeId = await createRecipe(s);
		const res = await translate(s, recipeId);
		expect([res.status, await code(res)]).toEqual([402, "subscription_required"]);
		expect(await monthUsed(s)).toMatchObject({ freeImportUsed: false, monthUsed: 0 });
	});

	it("402 quota_exhausted once 30 monthly units are used", async () => {
		const { s, recipeId } = await ready();
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO usage (period, count) VALUES (?, 30)", new Date().toISOString().slice(0, 7)));
		const res = await translate(s, recipeId);
		expect([res.status, await code(res)]).toEqual([402, "quota_exhausted"]);
	});

	it("a guardrail block ends as guard_block with a refund and one counted attempt", async () => {
		const { s, recipeId } = await ready();
		fakeAi(() => {
			throw new Error("AiError: Prompt blocked due to security configurations");
		});
		const key = uuid();
		await translate(s, recipeId, key);
		expect((await settle(s, key)).status).toBe("guard_block");
		expect(calls).toHaveLength(1); // a block is not retried on the fallback model
		expect((await monthUsed(s)).monthUsed).toBe(0);
		expect(await attempts(s)).toBe(1);
		expect((await recipes(s))[0]).not.toHaveProperty("translations");
	});

	it("an answer with other counts or changed quantities ends as invalid with a refund", async () => {
		const { s, recipeId } = await ready();
		fakeAi(() => ({ response: { ...answer, ingredients: [{ ...answer.ingredients[0], quantity: "2.2", unit: "lb" }] } }));
		const key = uuid();
		await translate(s, recipeId, key);
		expect((await settle(s, key)).status).toBe("invalid");
		expect((await monthUsed(s)).monthUsed).toBe(0);
		expect(await attempts(s)).toBe(1);
	});

	it("a model failing 3 times ends as failed with a refund", async () => {
		const { s, recipeId } = await ready();
		fakeAi(() => {
			throw new Error("upstream 500");
		});
		const key = uuid();
		await translate(s, recipeId, key);
		await vi.waitFor(() => expect(calls).toHaveLength(2), { timeout: 5000, interval: 20 });
		for (let i = 0; i < 2; i++) {
			await inObject(s.userId, async (_i, st) => {
				st.storage.sql.exec("UPDATE tasks SET due_at = ? WHERE kind = 'job'", Date.now() - 1);
				await st.storage.setAlarm(Date.now() - 1);
			});
			await runDurableObjectAlarm(userStub(s.userId));
		}
		expect((await job(s, key)).status).toBe("failed");
		expect((await monthUsed(s)).monthUsed).toBe(0);
	});

	it("404 for another user's recipe id, an unknown id, and a deleted recipe; 400 for a bad id, key or language", async () => {
		const a = await ready();
		const b = await ready();
		const foreign = await translate(a.s, b.recipeId);
		expect([foreign.status, await code(foreign)]).toEqual([404, "not_found"]);
		expect((await translate(a.s, uuid())).status).toBe(404);
		await call(`/recipes/${a.recipeId}`, { method: "DELETE", token: a.s.token });
		expect((await translate(a.s, a.recipeId)).status).toBe(404);
		expect(await code(await translate(a.s, "nope"))).toBe("invalid_id");
		expect(await code(await translate(a.s, b.recipeId, "nope"))).toBe("invalid_id");
		expect(await code(await translate(a.s, b.recipeId, uuid(), { language: "xx" }))).toBe("invalid_body");
		expect(calls).toEqual([]);
		expect((await monthUsed(a.s)).monthUsed).toBe(0);
		expect((await recipes(b.s))[0]).not.toHaveProperty("translations");
	});

	it("409 job_running while another job of the user is open", async () => {
		const { s, recipeId } = await ready();
		fakeAi(() => new Promise(() => {}));
		expect((await translate(s, recipeId)).status).toBe(202);
		const res = await translate(s, recipeId);
		expect([res.status, await code(res)]).toEqual([409, "job_running"]);
	});
});

describe("toTranslation", () => {
	const source = RecipeInput.parse({ ...inputFixture, ingredients: [{ quantity: "1", unit: "kg", item: "Kartoffeln", group: "Salat" }] });
	it("keeps the source quantities, units and group presence; cleans the text like import drafts", () => {
		const out = toTranslation(
			{ ...answer, title: "Potato <b>salad</b>", ingredients: [{ quantity: " 1 ", unit: "kg", item: "Potatoes", group: "Salad" }] },
			source,
			"en",
		);
		expect(out).toEqual({ status: "done", translation: { ...answer, title: "Potato salad", ingredients: [{ quantity: "1", unit: "kg", item: "Potatoes", group: "Salad" }] } });
	});
	it("is invalid for a wrong shape, other counts, an emptied step or a converted unit", () => {
		expect(toTranslation({ nonsense: true }, source, "en").status).toBe("invalid");
		expect(toTranslation({ ...answer, steps: [] }, source, "en").status).toBe("invalid");
		expect(toTranslation({ ...answer, steps: ["<b></b>"] }, source, "en").status).toBe("invalid");
		expect(toTranslation({ ...answer, ingredients: [{ ...answer.ingredients[0], unit: "lb" }] }, source, "en").status).toBe("invalid");
	});
});

describe("local AI stand-in translates on purpose", () => {
	it("returns the source with the language name in front", async () => {
		(env as unknown as { AI?: unknown }).AI = undefined;
		const { s, recipeId } = await ready();
		const key = uuid();
		await translate(s, recipeId, key, { language: "de" });
		expect((await settle(s, key)).status).toBe("done");
		expect((await recipes(s))[0].translations[0]).toMatchObject({ language: "de", title: "[German] Kartoffelsalat", tags: ["Beilage"] });
	});
});
