import { env, listDurableObjectIds } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { newSessionToken } from "../../src/auth";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import { app } from "../../src/index";
import { call, inObject, interceptOutbound, signIn, uuid } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => vi.restoreAllMocks());

const forged = (userId = "Z".repeat(42) + "w") => `v1.${userId}.${"S".repeat(42)}w.${"T".repeat(42)}w`;
const ns = () => (env.DO_JURISDICTION ? env.USER_DO.jurisdiction(env.DO_JURISDICTION) : env.USER_DO);

describe("forged session tokens (security review S1)", () => {
	it("401 without touching any Durable Object: no object id is created for the forged user id", async () => {
		const userId = "F".repeat(42) + "w";
		for (const path of ["/me", "/recipes", `/images/${uuid()}`]) {
			const res = await call(path, { token: forged(userId) });
			expect([path, res.status]).toEqual([path, 401]);
		}
		const target = ns().idFromName(userId).toString();
		expect((await listDurableObjectIds(env.USER_DO)).map((id) => id.toString())).not.toContain(target);
	});

	it("a forged token with a real user's id is refused before the object, and that object's data is untouched", async () => {
		const s = await signIn();
		expect((await call("/me", { token: forged(s.userId) })).status).toBe(401);
		const sessions = await inObject(s.userId, (_i, state) => state.storage.sql.exec("SELECT count(*) AS n FROM sessions").one().n);
		expect(sessions).toBe(1);
	});

	it("a valid tag with an unknown secret reaches the object and gets 401", async () => {
		const s = await signIn();
		const { token } = await newSessionToken(s.userId, env);
		const res = await call("/me", { token });
		expect(res.status).toBe(401);
	});

	it("an object that never had a session has no tables, even when a valid-tag token reaches it", async () => {
		const { token } = await newSessionToken("N".repeat(42) + "w", env);
		expect((await call("/recipes", { token })).status).toBe(401);
		const tables = await inObject("N".repeat(42) + "w", (_i, state) =>
			state.storage.sql.exec("SELECT count(*) AS n FROM sqlite_master WHERE type = 'table' AND name NOT GLOB '_cf_*' AND name NOT GLOB 'sqlite_*'").one().n,
		);
		expect(tables).toBe(0);
	});

	it("a wrong tag on the image upload is refused before the body is read", async () => {
		const s = await signIn();
		const recipeId = uuid();
		const recipe = { ...inputFixture, id: recipeId };
		expect((await call(`/recipes/${recipeId}`, { method: "PUT", token: s.token, body: JSON.stringify(recipe), headers: { "if-none-match": "*" } })).status).toBe(200);
		const upload = async (token: string) => {
			let pulled = false;
			// highWaterMark 0: pull() runs only when someone reads, not eagerly at construction.
			const body = new ReadableStream<Uint8Array>(
				{
					pull(controller) {
						pulled = true;
						controller.enqueue(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]));
						controller.close();
					},
				},
				{ highWaterMark: 0 },
			);
			// Called in this isolate (no service-binding hop, which would pump the stream across), so only the handler reads it.
			const request = new Request(`https://recipes.test/v1/recipes/${recipeId}/images/${uuid()}`, {
				method: "PUT",
				body,
				headers: { authorization: `Bearer ${token}`, "content-type": "image/jpeg", "content-length": "4", "cf-connecting-ip": "203.0.113.77" },
			});
			const res = await app.request(request, undefined, env);
			return { status: res.status, pulled };
		};
		expect(await upload(forged(s.userId))).toEqual({ status: 401, pulled: false });
		// Control: the same request with the real token is read and stored.
		expect(await upload(s.token)).toEqual({ status: 201, pulled: true });
	});

	it("tokens from before the tag (three parts) are refused", async () => {
		const s = await signIn();
		const [v, userId, secret] = s.token.split(".");
		expect((await call("/me", { token: `${v}.${userId}.${secret}` })).status).toBe(401);
	});
});
