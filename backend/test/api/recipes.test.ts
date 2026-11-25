import { runDurableObjectAlarm } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import errorFixture from "../../../contract/fixtures/error.json";
import recipeFixture from "../../../contract/fixtures/recipe.json";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import listFixture from "../../../contract/fixtures/recipes_list.json";
import { MAX_RECIPES } from "../../src/recipes";
import { MARKER_TTL_MS, PAGE_SIZE, RECIPE_STORAGE_CAP } from "../../src/user-do";
import { env } from "cloudflare:test";
import { type Session, call, inObject, interceptOutbound, signIn, userStub, uuid } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => vi.restoreAllMocks());

type R = typeof recipeFixture & { deleted: boolean };
type Page = { recipes: R[]; rev: number; more: boolean };
const input = (id = uuid(), patch: Record<string, unknown> = {}) => ({ ...inputFixture, id, ...patch });
const put = (s: Session, body: { id: string } & Record<string, unknown>, headers: Record<string, string> = { "if-none-match": "*" }) =>
	call(`/recipes/${body.id}`, { method: "PUT", token: s.token, body: JSON.stringify(body), headers: { "content-type": "application/json", ...headers } });
const list = async (s: Session, since = 0) => (await call(`/recipes?since=${since}`, { token: s.token })).json() as Promise<Page>;
const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;

describe("recipes CRUD (AC7)", () => {
	it("create returns the contract Recipe", async () => {
		const s = await signIn();
		const res = await put(s, input(recipeFixture.id));
		expect(res.status).toBe(200);
		const body = (await res.json()) as R;
		expect(body).toEqual({ ...recipeFixture, rev: 1, updatedAt: body.updatedAt });
		expect(Object.keys(body)).toEqual(Object.keys(recipeFixture));
		expect(body.updatedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
	});

	it("update with If-Match, delete, and a second client sees each change through since", async () => {
		const s = await signIn("two-clients");
		const other = await signIn("two-clients");
		const id = uuid();
		const created = (await (await put(s, input(id))).json()) as R;
		const cursor = (await list(other)).rev;
		const updated = await put(s, input(id, { title: "Neu" }), { "if-match": `"${created.rev}"` });
		expect(updated.status).toBe(200);
		expect(((await updated.json()) as R).rev).toBe(created.rev + 1);
		let pulled = await list(other, cursor);
		expect(pulled.recipes.map((r) => [r.id, r.title])).toEqual([[id, "Neu"]]);
		expect((await call(`/recipes/${id}`, { method: "DELETE", token: s.token })).status).toBe(204);
		pulled = await list(other, pulled.rev);
		expect(pulled.recipes).toEqual([{ id, rev: created.rev + 2, deleted: true, updatedAt: pulled.recipes[0].updatedAt }]);
	});

	it("list equals the contract shape: one recipe and one deletion marker, ordered by rev", async () => {
		const s = await signIn();
		const kept = recipeFixture.id;
		const gone = listFixture.recipes[1].id;
		await put(s, input(kept));
		await put(s, input(gone));
		await call(`/recipes/${gone}`, { method: "DELETE", token: s.token });
		await put(s, input(kept), { "if-match": '"1"' });
		const body = await list(s);
		const normalised = { ...body, recipes: body.recipes.map((r) => ({ ...r, updatedAt: "T" })) };
		const expected = { rev: 4, more: false, recipes: [{ ...listFixture.recipes[1], rev: 3, updatedAt: "T" }, { ...listFixture.recipes[0], rev: 4, updatedAt: "T" }] };
		expect(normalised).toEqual(expected);
	});

	it("stale If-Match and a duplicate create return 412", async () => {
		const s = await signIn();
		const id = uuid();
		await put(s, input(id));
		const dup = await put(s, input(id));
		expect(dup.status).toBe(412);
		expect(await code(dup)).toBe("precondition_failed");
		expect((await put(s, input(id), { "if-match": '"99"' })).status).toBe(412);
		expect((await put(s, input(id), { "if-match": "garbage" })).status).toBe(412);
		expect((await put(s, input(id), { "if-none-match": "abc" })).status).toBe(412);
	});

	it("428 without a precondition header", async () => {
		const s = await signIn();
		const res = await put(s, input(), {});
		expect(res.status).toBe(428);
		expect(await code(res)).toBe("precondition_required");
	});

	it("a create on a deletion marker revives the recipe; If-Match on the marker is 412", async () => {
		const s = await signIn();
		const id = uuid();
		await put(s, input(id));
		await call(`/recipes/${id}`, { method: "DELETE", token: s.token });
		expect((await put(s, input(id), { "if-match": '"2"' })).status).toBe(412);
		const revived = await put(s, input(id, { title: "Wieder da" }));
		expect(revived.status).toBe(200);
		expect(((await revived.json()) as R).deleted).toBe(false);
	});

	it("DELETE is 204 again on a deleted recipe and 404 for an id that never existed", async () => {
		const s = await signIn();
		const id = uuid();
		await put(s, input(id));
		expect((await call(`/recipes/${id}`, { method: "DELETE", token: s.token })).status).toBe(204);
		expect((await call(`/recipes/${id}`, { method: "DELETE", token: s.token })).status).toBe(204);
		const missing = await call(`/recipes/${uuid()}`, { method: "DELETE", token: s.token });
		expect(missing.status).toBe(404);
		expect(await missing.json()).toEqual({ error: { code: errorFixture.error.code, message: "recipe not found" } });
	});

	it("an update of an id this user never had is 404", async () => {
		const s = await signIn();
		expect((await put(s, input(), { "if-match": '"1"' })).status).toBe(404);
	});
});

describe("validation", () => {
	const bad: [string, Record<string, unknown>][] = [
		["empty title", { title: "" }],
		["title over 200", { title: "x".repeat(201) }],
		["servings 0", { servings: 0 }],
		["101 ingredients", { ingredients: Array.from({ length: 101 }, () => ({ quantity: "", unit: "", item: "a", group: null })) }],
		["unknown field", { rev: 3 }],
		["unknown sourceType", { sourceType: "ai" }],
		["six imageIds", { imageIds: Array.from({ length: 6 }, uuid) }],
		["bad language", { language: "x" }],
		["imageId not uploaded", { imageIds: [uuid()] }],
		["duplicate imageIds", { imageIds: ["7f1e2d3c-4b5a-4968-8776-655443322110", "7f1e2d3c-4b5a-4968-8776-655443322110"] }],
	];
	for (const [name, patch] of bad) {
		it(`400 invalid_body: ${name}`, async () => {
			const s = await signIn();
			const res = await put(s, input(uuid(), patch));
			expect(res.status).toBe(400);
			expect(await code(res)).toBe("invalid_body");
		});
	}

	it("400 invalid_body when the body id differs from the path, or the body is not JSON", async () => {
		const s = await signIn();
		const id = uuid();
		const other = await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify(input()), headers: { "if-none-match": "*" } });
		expect(await code(other)).toBe("invalid_body");
		const broken = await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: "{", headers: { "if-none-match": "*" } });
		expect(await code(broken)).toBe("invalid_body");
	});

	it("400 invalid_id for ids that are not lowercase UUIDs, and for a bad since", async () => {
		const s = await signIn();
		const upper = uuid().toUpperCase();
		expect(await code(await call(`/recipes/${upper}`, { method: "PUT", token: s.token, body: "{}", headers: { "if-none-match": "*" } }))).toBe("invalid_id");
		expect(await code(await call("/recipes/abc", { method: "DELETE", token: s.token }))).toBe("invalid_id");
		expect(await code(await call("/recipes?since=-1", { token: s.token }))).toBe("invalid_body");
	});

	it("413 recipe_too_large over 64 KB", async () => {
		const s = await signIn();
		const big = input(uuid(), { steps: Array.from({ length: 20 }, () => "x".repeat(4000)) });
		const res = await put(s, big);
		expect(res.status).toBe(413);
		expect(await code(res)).toBe("recipe_too_large");
	});

	it("409 recipe_limit at 2,000 recipes; deletion markers do not count", async () => {
		const s = await signIn();
		await inObject(s.userId, (_i, state) => {
			for (let i = 0; i <= MAX_RECIPES; i++) {
				state.storage.sql.exec("INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, 0, ?, 0, ?)", uuid(), i === 0 ? 1 : 0, i === 0 ? null : "{}");
			}
		});
		const res = await put(s, input());
		expect(res.status).toBe(409);
		expect(await code(res)).toBe("recipe_limit");
		await inObject(s.userId, (_i, state) => state.storage.sql.exec("UPDATE recipes SET deleted = 1, json = NULL WHERE rowid = (SELECT max(rowid) FROM recipes)"));
		expect((await put(s, input())).status).toBe(200);
	});

	it("401 on every recipe route without a valid token", async () => {
		const id = uuid();
		for (const [path, method] of [["/recipes", "GET"], [`/recipes/${id}`, "PUT"], [`/recipes/${id}`, "DELETE"]] as const) {
			expect((await call(path, { method, token: `v1.${"A".repeat(43)}.${"B".repeat(43)}` })).status).toBe(401);
		}
	});
});

describe("deletion markers and the purge horizon (AC7)", () => {
	it("markers are purged after 90 days by the alarm; an older cursor gets 410, a full pull matches the server", async () => {
		const s = await signIn();
		const keep = uuid();
		const drop = uuid();
		await put(s, input(keep));
		await put(s, input(drop));
		const oldCursor = (await list(s)).rev;
		await call(`/recipes/${drop}`, { method: "DELETE", token: s.token });
		await inObject(s.userId, (_i, state) => {
			state.storage.sql.exec("UPDATE recipes SET updated_at = updated_at - ? WHERE deleted = 1", MARKER_TTL_MS + 1000);
			state.storage.sql.exec("UPDATE tasks SET due_at = ? WHERE id = 'expiry'", Date.now() - 1);
		});
		await inObject(s.userId, async (_i, state) => state.storage.setAlarm(Date.now() - 1));
		expect(await runDurableObjectAlarm(userStub(s.userId))).toBe(true);
		const expired = await call(`/recipes?since=${oldCursor}`, { token: s.token });
		expect(expired.status).toBe(410);
		expect(await code(expired)).toBe("cursor_expired");
		const full = await list(s, 0);
		expect(full.recipes.map((r) => r.id)).toEqual([keep]);
		expect((await call(`/recipes?since=${full.rev}`, { token: s.token })).status).toBe(200);
	});
});

describe("paging (GET /recipes)", () => {
	/** Inserts `n` recipes with revs 1..n straight into the object, as a fixture. */
	const seed = (s: Session, n: number) =>
		inObject(s.userId, (_i, state) => {
			const ids: string[] = [];
			for (let rev = 1; rev <= n; rev++) {
				const id = uuid();
				ids.push(id);
				state.storage.sql.exec("INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, ?, 0, ?, ?)", id, rev, Date.now(), JSON.stringify(input(id)));
			}
			state.storage.sql.exec("INSERT INTO meta (key, value) VALUES ('rev', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", String(n));
			return ids;
		});

	it("201 recipes come in two pages with no gap and no duplicate", async () => {
		const s = await signIn();
		const ids = await seed(s, PAGE_SIZE + 1);
		const first = await list(s, 0);
		expect([first.recipes.length, first.more, first.rev]).toEqual([PAGE_SIZE, true, PAGE_SIZE]);
		const second = await list(s, first.rev);
		expect([second.recipes.length, second.more, second.rev]).toEqual([1, false, PAGE_SIZE + 1]);
		expect([...first.recipes, ...second.recipes].map((r) => r.id)).toEqual(ids);
	});

	it("exactly one page: more is false and rev is the current revision", async () => {
		const s = await signIn();
		await seed(s, PAGE_SIZE);
		const page = await list(s, 0);
		expect([page.recipes.length, page.more, page.rev]).toEqual([PAGE_SIZE, false, PAGE_SIZE]);
	});

	it("a change made between two pages shows up in a later page", async () => {
		const s = await signIn();
		const ids = await seed(s, PAGE_SIZE + 1);
		const first = await list(s, 0);
		const edited = await put(s, input(ids[4], { title: "Geändert" }), { "if-match": '"5"' });
		expect(edited.status).toBe(200);
		const second = await list(s, first.rev);
		expect(second.recipes.map((r) => [r.id, r.rev, r.title])).toEqual([
			[ids[PAGE_SIZE], PAGE_SIZE + 1, inputFixture.title],
			[ids[4], PAGE_SIZE + 2, "Geändert"],
		]);
		expect(second.more).toBe(false);
	});
});

describe("Content-Length", () => {
	const stream = (text: string) =>
		new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(new TextEncoder().encode(text));
				controller.close();
			},
		});

	it("411 length_required for a chunked body on PUT /recipes/:id and the image upload", async () => {
		const s = await signIn();
		const id = uuid();
		const recipe = await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: stream(JSON.stringify(input(id))), headers: { "if-none-match": "*" } });
		expect([recipe.status, await code(recipe)]).toEqual([411, "length_required"]);
		await put(s, input(id));
		const image = await call(`/recipes/${id}/images/${uuid()}`, { method: "PUT", token: s.token, body: stream("\xff\xd8\xff"), headers: { "content-type": "image/jpeg" } });
		expect([image.status, await code(image)]).toEqual([411, "length_required"]);
	});
});

describe("session revoked while a write waits for the rate limiter", () => {
	it("the write answers 401 and changes nothing", async () => {
		const s = await signIn();
		const id = uuid();
		const original = env.RL_USER.limit.bind(env.RL_USER);
		vi.spyOn(env.RL_USER, "limit").mockImplementationOnce(async (options) => {
			expect((await call("/session", { method: "DELETE", token: s.token })).status).toBe(204);
			return original(options);
		});
		const res = await put(s, input(id));
		expect([res.status, await code(res)]).toEqual([401, "unauthenticated"]);
		const stored = await inObject(s.userId, (_i, state) => state.storage.sql.exec("SELECT count(*) AS n FROM recipes").one().n);
		expect(stored).toBe(0);
	});
});

describe("stored recipe text cap (20 MB per user)", () => {
	const counter = (s: Session) => inObject(s.userId, (_i, st) => Number(st.storage.sql.exec("SELECT value FROM meta WHERE key = 'recipe_bytes'").one().value));
	/** Recipes and their translations (translations count toward the same 20 MB, security review M5). */
	const fresh = (s: Session) =>
		inObject(
			s.userId,
			(_i, st) =>
				st.storage.sql
					.exec(
						"SELECT (SELECT coalesce(sum(length(CAST(json AS BLOB))), 0) FROM recipes WHERE deleted = 0) + (SELECT coalesce(sum(length(CAST(json AS BLOB))), 0) FROM translations) AS n",
					)
					.one().n as number,
		);
	/** A translation through the real route; the local AI stand-in answers (env.AI is unset in this file). */
	const translate = async (s: Session, id: string, language: string) => {
		const key = uuid();
		const res = await call(`/recipes/${id}/translations`, { method: "POST", token: s.token, body: JSON.stringify({ language }), headers: { "content-type": "application/json", "idempotency-key": key } });
		expect(res.status).toBe(202);
		await vi.waitFor(async () => expect(((await (await call(`/jobs/${key}`, { token: s.token })).json()) as { status: string }).status).toBe("done"), { timeout: 5000, interval: 20 });
	};
	/** Fixture: stored recipes (1 MB each, a row's size limit is below 20 MB) filling the cap up to `free` bytes; returns their ids. */
	const fill = (s: Session, free: number) =>
		inObject(s.userId, (_i, st) => {
			const ids: string[] = [];
			for (let left = RECIPE_STORAGE_CAP - free; left > 0; left -= 1_000_000) {
				const id = uuid();
				ids.push(id);
				st.storage.sql.exec("INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, 0, 0, 0, ?)", id, "x".repeat(Math.min(left, 1_000_000)));
			}
			st.storage.sql.exec("INSERT INTO meta (key, value) VALUES ('recipe_bytes', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", String(RECIPE_STORAGE_CAP - free));
			return ids;
		});

	it("a create over the cap answers 413 recipe_storage_cap; deleting frees the space", async () => {
		const s = await signIn();
		const [big] = await fill(s, 100);
		const res = await put(s, input());
		expect([res.status, await code(res)]).toEqual([413, "recipe_storage_cap"]);
		expect((await call(`/recipes/${big}`, { method: "DELETE", token: s.token })).status).toBe(204);
		expect(await counter(s)).toBe(await fresh(s));
		expect((await put(s, input())).status).toBe(200);
	});

	it("an update that grows over the cap is refused; one that keeps or shrinks the size passes", async () => {
		const s = await signIn();
		const id = uuid();
		await put(s, input(id));
		const size = await counter(s);
		await fill(s, 10);
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("UPDATE meta SET value = ? WHERE key = 'recipe_bytes'", String(RECIPE_STORAGE_CAP - 10 + size)));
		const grow = await put(s, input(id, { description: "x".repeat(500) }), { "if-match": '"1"' });
		expect([grow.status, await code(grow)]).toEqual([413, "recipe_storage_cap"]);
		expect((await put(s, input(id), { "if-match": '"1"' })).status).toBe(200);
		expect((await put(s, input(id, { description: "" , title: "K" }), { "if-match": '"2"' })).status).toBe(200);
	});

	it("the running total equals a fresh sum after creates, updates, deletes, revives and a purge", async () => {
		const s = await signIn();
		const [a, b, c] = [uuid(), uuid(), uuid()];
		await put(s, input(a));
		await put(s, input(b, { title: "Größere Überschrift mit Umlauten äöü" }));
		await put(s, input(c, { steps: ["eins", "zwei", "drei"] }));
		await put(s, input(a, { description: "länger ".repeat(50) }), { "if-match": '"1"' });
		await call(`/recipes/${b}`, { method: "DELETE", token: s.token });
		await put(s, input(b));
		await call(`/recipes/${c}`, { method: "DELETE", token: s.token });
		expect(await counter(s)).toBe(await fresh(s));
		// Translations: added, replaced, and freed with their recipe.
		await inObject(s.userId, (_i, st) => {
			st.storage.sql.exec("INSERT INTO meta (key, value) VALUES ('consent_version', '1'), ('entitlement', ?)", JSON.stringify({ active: true, expiresAt: null }));
		});
		await translate(s, a, "en");
		await translate(s, a, "fr");
		await translate(s, b, "en");
		await put(s, input(a, { description: "kurz" }), { "if-match": `"${(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT rev FROM recipes WHERE id = ?", a).one().rev)) as number}"` });
		await translate(s, a, "en");
		expect(await counter(s)).toBe(await fresh(s));
		await call(`/recipes/${a}`, { method: "DELETE", token: s.token });
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM translations").one().n)).toBe(1);
		expect(await counter(s)).toBe(await fresh(s));
		await inObject(s.userId, async (instance, st) => {
			st.storage.sql.exec("UPDATE recipes SET updated_at = 0 WHERE deleted = 1");
			await instance.expire(Date.now());
		});
		expect(await counter(s)).toBe(await fresh(s));
		expect(await fresh(s)).toBeGreaterThan(0);
	});

	it("an object without a counter (stored before the cap existed) starts from the table", async () => {
		const s = await signIn();
		await put(s, input());
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("DELETE FROM meta WHERE key = 'recipe_bytes'"));
		await put(s, input());
		expect(await counter(s)).toBe(await fresh(s));
	});
});

