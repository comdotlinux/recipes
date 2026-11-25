import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import { call, interceptOutbound, signIn, uuid } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => vi.restoreAllMocks());

/** AC5: user A's token on user B's ids gives 404 on every route that takes an id, and never changes B's data. */
describe("IDOR (AC5)", () => {
	it("every id route returns 404 for B's ids with A's token", async () => {
		const a = await signIn("idor-a");
		const b = await signIn("idor-b");
		const recipeId = uuid();
		const imageId = uuid();
		const body = JSON.stringify({ ...inputFixture, id: recipeId });
		expect((await call(`/recipes/${recipeId}`, { method: "PUT", token: b.token, body, headers: { "if-none-match": "*" } })).status).toBe(200);
		const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
		expect((await call(`/recipes/${recipeId}/images/${imageId}`, { method: "PUT", token: b.token, body: jpeg, headers: { "content-type": "image/jpeg" } })).status).toBe(201);

		const asA: [string, RequestInit][] = [
			[`/recipes/${recipeId}`, { method: "PUT", body, headers: { "if-match": '"1"' } }],
			[`/recipes/${recipeId}`, { method: "DELETE" }],
			[`/recipes/${recipeId}/images/${uuid()}`, { method: "PUT", body: jpeg, headers: { "content-type": "image/jpeg" } }],
			[`/images/${imageId}`, { method: "GET" }],
		];
		for (const [path, init] of asA) {
			const res = await call(path, { ...init, token: a.token });
			expect([path, init.method, res.status]).toEqual([path, init.method, 404]);
			expect(((await res.json()) as { error: { code: string } }).error.code).toBe("not_found");
		}
		const listA = (await (await call("/recipes", { token: a.token })).json()) as { recipes: unknown[] };
		expect(listA.recipes).toEqual([]);

		// A create with B's id lands in A's own space and leaves B's recipe as it was.
		expect((await call(`/recipes/${recipeId}`, { method: "PUT", token: a.token, body: JSON.stringify({ ...inputFixture, id: recipeId, title: "A" }), headers: { "if-none-match": "*" } })).status).toBe(200);
		const listB = (await (await call("/recipes", { token: b.token })).json()) as { recipes: { title: string; rev: number }[] };
		expect(listB.recipes.map((r) => [r.title, r.rev])).toEqual([[inputFixture.title, 1]]);
		expect((await call(`/images/${imageId}`, { token: b.token })).status).toBe(200);
	});

	it("a token whose user id part is swapped for B's id is rejected", async () => {
		const a = await signIn("swap-a");
		const b = await signIn("swap-b");
		const forged = `v1.${b.userId}.${a.token.split(".")[2]}`;
		expect((await call("/me", { token: forged })).status).toBe(401);
		expect((await call("/recipes", { token: forged })).status).toBe(401);
	});
});
