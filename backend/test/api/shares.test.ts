import { env, listDurableObjectIds } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import acceptFixture from "../../../contract/fixtures/share_accept_response.json";
import createdFixture from "../../../contract/fixtures/share_created.json";
import previewFixture from "../../../contract/fixtures/share_preview.json";
import shareReportFixture from "../../../contract/fixtures/report_share.json";
import tokenBodyFixture from "../../../contract/fixtures/share_token_body.json";
import listFixture from "../../../contract/fixtures/shares_list.json";
import { SCREEN_PROMPT } from "../../src/ai/screen-image";
import { newSessionToken } from "../../src/auth";
import { SHARE_RECIPIENT_LIMIT, derivedId, newShareId, newShareToken, openReportReference, openShareToken, reportReference } from "../../src/share";
import { LOCAL_UNSAFE_PICTURE } from "../../src/testing/local-ai";
import { type Session, call, inObject, interceptOutbound, json, signIn, uuid } from "./helpers";

type Created = typeof createdFixture;
type Recipe = typeof acceptFixture & { translations?: unknown };

let modelCalls = 0;
beforeEach(() => {
	interceptOutbound();
	modelCalls = 0;
});
afterEach(() => {
	vi.restoreAllMocks();
	(env as unknown as { AI?: unknown }).AI = undefined;
});
/** Counts model calls; every picture is judged by `safe`. */
const fakeScreen = (safe: boolean | (() => never)) => {
	(env as unknown as { AI?: unknown }).AI = {
		run: async (_m: string, inputs: { messages: { content: { text?: string }[] }[] }) => {
			modelCalls++;
			expect(inputs.messages[0].content[0].text).toBe(SCREEN_PROMPT);
			if (typeof safe === "function") safe();
			return { response: JSON.stringify({ safe }) };
		},
	};
};

const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;
const sql = (s: Session, query: string, ...values: unknown[]) => inObject(s.userId, (_i, st) => st.storage.sql.exec(query, ...values).toArray());
const setMeta = (s: Session, key: string, value: string) =>
	sql(s, "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value);
const subscribe = (s: Session) => setMeta(s, "entitlement", JSON.stringify({ active: true, expiresAt: null }));
const consent = (s: Session) => call("/me/consent", { method: "PUT", token: s.token, body: JSON.stringify({ version: 1 }), headers: { "content-type": "application/json" } });
const jpeg = (extra = "") => {
	const tail = new TextEncoder().encode(`picture ${uuid()} ${extra}`);
	const b = new Uint8Array(4 + tail.length);
	b.set([0xff, 0xd8, 0xff, 0xe0]);
	b.set(tail, 4);
	return b;
};
const put = (s: Session, id: string, body: object, headers: Record<string, string>) =>
	call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, ...body, id }), headers });
/** Updates a recipe at its current revision. */
const update = async (s: Session, id: string, body: object) => {
	const [{ rev }] = (await sql(s, "SELECT rev FROM recipes WHERE id = ?", id)) as { rev: number }[];
	return put(s, id, body, { "if-match": `"${rev}"` });
};
/** A recipe of `s`, optionally with pictures (uploaded, then attached). */
const recipe = async (s: Session, pictures: Uint8Array[] = [], body: object = {}) => {
	const id = uuid();
	expect((await put(s, id, body, { "if-none-match": "*" })).status).toBe(200);
	if (pictures.length === 0) return id;
	const imageIds = [];
	for (const p of pictures) {
		const imageId = uuid();
		expect((await call(`/recipes/${id}/images/${imageId}`, { method: "PUT", token: s.token, body: p, headers: { "content-type": "image/jpeg" } })).status).toBe(201);
		imageIds.push(imageId);
	}
	expect((await update(s, id, { ...body, imageIds })).status).toBe(200);
	return id;
};
const owner = async () => {
	const s = await signIn();
	await subscribe(s);
	await consent(s);
	return s;
};
const share = async (s: Session, recipeId: string) => call(`/recipes/${recipeId}/shares`, { method: "POST", token: s.token });
const created = async (s: Session, recipeId: string) => {
	const res = await share(s, recipeId);
	expect(res.status).toBe(201);
	return (await res.json()) as Created;
};
const preview = (s: Session, token: string) => call("/shares/open", { method: "POST", token: s.token, ...json({ ...tokenBodyFixture, shareToken: token }) });
const accept = (s: Session, token: string) => call("/shares/accept", { method: "POST", token: s.token, ...json({ ...tokenBodyFixture, shareToken: token }) });
const list = async (s: Session) => ((await (await call("/recipes", { token: s.token })).json()) as { recipes: Recipe[] }).recipes;
const report = (s: Session, shareToken: string) =>
	call("/reports", { method: "POST", token: s.token, body: JSON.stringify({ ...shareReportFixture, shareToken }), headers: { "content-type": "application/json" } });

describe("share tokens", () => {
	const ownerId = "A".repeat(42) + "w";
	it("open to the owner and share id; tampering, another key and other token kinds open to nothing", async () => {
		const shareId = newShareId();
		const token = await newShareToken(ownerId, shareId, env);
		expect(token).toMatch(/^[A-Za-z0-9_-]{107}$/);
		expect(await openShareToken(token, env)).toEqual({ ownerId, shareId });
		for (const i of [0, 10, 50, 106]) {
			const flipped = token.slice(0, i) + (token[i] === "A" ? "B" : "A") + token.slice(i + 1);
			expect(await openShareToken(flipped, env)).toBeNull();
		}
		expect(await openShareToken(token, { SERVER_KEY: "another-key" })).toBeNull();
		expect(await openShareToken(token.slice(1), env)).toBeNull();
		const { token: session } = await newSessionToken(ownerId, env);
		expect(await openShareToken(session, env)).toBeNull();
		expect(await openShareToken("../../me", env)).toBeNull();
		// Non-canonical base64url: the same bytes with the two unused low bits of the last character set.
		const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
		const lastBits = alphabet.indexOf(token[106]);
		expect(lastBits % 4).toBe(0);
		expect(await openShareToken(token.slice(0, 106) + alphabet[lastBits + 1], env)).toBeNull();
	});

	it("a token made with the previous key still opens during a rotation (AC34)", async () => {
		const shareId = newShareId();
		const old = await newShareToken(ownerId, shareId, { SERVER_KEY: "old-key" });
		expect(await openShareToken(old, { SERVER_KEY: "new-key" })).toBeNull();
		expect(await openShareToken(old, { SERVER_KEY: "new-key", SERVER_KEY_PREVIOUS: "old-key" })).toEqual({ ownerId, shareId });
	});

	it("report references open for 30 days and not after", async () => {
		const now = Date.now();
		const ref = await reportReference(ownerId, now, env);
		expect(ref).not.toContain(ownerId);
		expect(await openReportReference(ref, env, now + 29 * 86_400_000)).toBe(ownerId);
		expect(await openReportReference(ref, env, now + 30 * 86_400_000)).toBeNull();
		expect(await openReportReference(ref, { SERVER_KEY: "x", SERVER_KEY_PREVIOUS: env.SERVER_KEY }, now)).toBe(ownerId);
		expect(await openReportReference(ref.slice(2), env, now)).toBeNull();
		expect(await openReportReference("!", env, now)).toBeNull();
	});
});

describe("creating and revoking links", () => {
	it("201 in the contract shape with a link on this host; 30 days; no consent needed without pictures", async () => {
		const s = await signIn();
		await subscribe(s);
		const res = await share(s, await recipe(s));
		expect(res.status).toBe(201);
		const body = (await res.json()) as Created;
		expect(Object.keys(body)).toEqual(Object.keys(createdFixture));
		expect(body.url).toBe(`http://localhost/s#${body.token}`);
		expect(body.shareId).toMatch(/^[0-9a-f]{32}$/);
		expect(Date.parse(body.expiresAt) - Date.now()).toBeGreaterThan(29.9 * 86_400_000);
		expect(await openShareToken(body.token, env)).toEqual({ ownerId: s.userId, shareId: body.shareId });
	});

	it("402 subscription_required for a free user; 404 for another user's or an unknown recipe", async () => {
		const free = await signIn();
		const res = await share(free, await recipe(free));
		expect([res.status, await code(res)]).toEqual([402, "subscription_required"]);
		const a = await owner();
		const b = await owner();
		const foreign = await share(a, await recipe(b));
		expect([foreign.status, await code(foreign)]).toEqual([404, "not_found"]);
		expect((await share(a, uuid())).status).toBe(404);
		expect(await code(await share(a, "nope"))).toBe("invalid_id");
	});

	it("the eleventh link of a UTC day answers 429 share_limit", async () => {
		const s = await owner();
		const id = await recipe(s);
		for (let i = 0; i < 10; i++) await created(s, id);
		const res = await share(s, id);
		expect([res.status, await code(res)]).toEqual([429, "share_limit"]);
	});

	it("DELETE revokes the owner's link (then 410 for everyone); 404 for another user's share id; 400 for a malformed id", async () => {
		const a = await owner();
		const link = await created(a, await recipe(a));
		const b = await signIn();
		expect((await call(`/shares/${link.shareId}`, { method: "DELETE", token: b.token })).status).toBe(404);
		expect((await preview(b, link.token)).status).toBe(200);
		expect((await call(`/shares/${link.shareId}`, { method: "DELETE", token: a.token })).status).toBe(204);
		const gone = await preview(b, link.token);
		expect([gone.status, await code(gone)]).toEqual([410, "share_gone"]);
		expect((await call(`/shares/${newShareId()}`, { method: "DELETE", token: a.token })).status).toBe(404);
		expect(await code(await call("/shares/xyz", { method: "DELETE", token: a.token }))).toBe("invalid_id");
	});
});

describe("GET /shares", () => {
	type List = typeof listFixture;
	const listOf = async (s: Session) => {
		const res = await call("/shares", { token: s.token });
		expect(res.status).toBe(200);
		return (await res.json()) as List;
	};

	it("lists the caller's working links newest first, with recipients and no tokens", async () => {
		const a = await owner();
		const id = await recipe(a);
		const first = await created(a, id);
		const second = await created(a, id);
		const b = await signIn();
		await accept(b, second.token);
		const list = await listOf(a);
		expect(Object.keys(list.shares[0])).toEqual(Object.keys(listFixture.shares[0]));
		expect(list.shares.map((x) => [x.shareId, x.recipeId, x.recipients, x.expiresAt])).toEqual([
			[second.shareId, id, 1, second.expiresAt],
			[first.shareId, id, 0, first.expiresAt],
		]);
		expect(JSON.stringify(list)).not.toContain(first.token.slice(0, 20));
		// Only the caller's own links.
		expect((await listOf(b)).shares).toEqual([]);
	});

	it("leaves out expired and revoked links and links of a deleted recipe", async () => {
		const a = await owner();
		const id = await recipe(a);
		const expired = await created(a, id);
		const revoked = await created(a, id);
		const kept = await created(a, id);
		const gone = await recipe(a);
		await created(a, gone);
		await sql(a, "UPDATE shares SET expires_at = ? WHERE share_id = ?", Date.now() - 1, expired.shareId);
		await call(`/shares/${revoked.shareId}`, { method: "DELETE", token: a.token });
		await call(`/recipes/${gone}`, { method: "DELETE", token: a.token });
		expect((await listOf(a)).shares.map((x) => x.shareId)).toEqual([kept.shareId]);
	});

	it("a suspended owner's list is empty; an account being deleted gets 401", async () => {
		const a = await owner();
		await created(a, await recipe(a));
		await setMeta(a, "state", "suspended");
		expect((await listOf(a)).shares).toEqual([]);
		await setMeta(a, "state", "deleting");
		expect((await call("/shares", { token: a.token })).status).toBe(401);
	});
});

describe("share-time picture check", () => {
	it("a recipe with pictures needs consent (AC12)", async () => {
		const s = await signIn();
		await subscribe(s);
		const id = await recipe(s, [jpeg()]);
		const res = await share(s, id);
		expect([res.status, await code(res)]).toEqual([403, "consent_required"]);
		await consent(s);
		expect((await share(s, id)).status).toBe(201);
	});

	it("a picture that fails the check blocks the link with 422 (local stand-in marker); the result is kept per picture", async () => {
		const s = await owner();
		const id = await recipe(s, [jpeg(), jpeg(LOCAL_UNSAFE_PICTURE)]);
		const res = await share(s, id);
		expect([res.status, await code(res)]).toEqual([422, "picture_rejected"]);
		expect((await sql(s, "SELECT screened_ok FROM images ORDER BY screened_ok")).map((r) => r.screened_ok)).toEqual([0, 1]);
		expect(await sql(s, "SELECT count(*) AS n FROM shares")).toEqual([{ n: 0 }]);
	});

	it("each picture is checked once; new bytes under the same id are checked again", async () => {
		const s = await owner();
		fakeScreen(true);
		const id = await recipe(s, [jpeg()]);
		await created(s, id);
		await created(s, id);
		expect(modelCalls).toBe(1);
		const [{ id: imageId }] = (await sql(s, "SELECT id FROM images")) as { id: string }[];
		await call(`/recipes/${id}/images/${imageId}`, { method: "PUT", token: s.token, body: jpeg(), headers: { "content-type": "image/jpeg" } });
		await created(s, id);
		expect(modelCalls).toBe(2);
	});

	it("an unreadable answer or a model failure answers 503 and checks nothing; a guardrail block counts as not safe", async () => {
		const s = await owner();
		const id = await recipe(s, [jpeg()]);
		(env as unknown as { AI?: unknown }).AI = { run: async () => ({ response: "no json" }) };
		const res = await share(s, id);
		expect([res.status, await code(res)]).toEqual([503, "screening_unavailable"]);
		expect(await sql(s, "SELECT screened_ok FROM images")).toEqual([{ screened_ok: null }]);
		fakeScreen(() => {
			throw new Error("AiError: Prompt blocked due to security configurations");
		});
		expect(await code(await share(s, id))).toBe("picture_rejected");
		expect(modelCalls).toBe(1);
	});

	it("picture checks are capped at 30 a day per user, also when every share fails with 422 (security review M5)", async () => {
		const s = await owner();
		fakeScreen(false);
		const id = await recipe(s, [jpeg()]);
		const [{ id: imageId }] = (await sql(s, "SELECT id FROM images")) as { id: string }[];
		const statuses: number[] = [];
		// New bytes under the same id reset the check, so each round calls the model again until the cap.
		for (let i = 0; i < 32; i++) {
			await call(`/recipes/${id}/images/${imageId}`, { method: "PUT", token: s.token, body: jpeg(), headers: { "content-type": "image/jpeg" } });
			statuses.push((await share(s, id)).status);
		}
		expect(modelCalls).toBe(30);
		expect(statuses.slice(0, 30).every((n) => n === 422)).toBe(true);
		const res = await share(s, id);
		expect([res.status, await code(res)]).toEqual([429, "share_limit"]);
		expect(statuses.slice(30)).toEqual([429, 429]);
	});

	it("uses its own global rate-limit key", async () => {
		const s = await owner();
		const id = await recipe(s, [jpeg()]);
		const limit = vi.spyOn(env.RL_GLOBAL, "limit").mockResolvedValue({ success: false });
		const res = await share(s, id);
		expect([res.status, await code(res)]).toEqual([429, "rate_limited"]);
		expect(limit).toHaveBeenCalledWith({ key: "screen-image" });
	});
});

describe("preview and accept (AC25)", () => {
	it("preview shows the recipe text without control characters and no pictures; accept makes an independent copy with the pictures", async () => {
		const a = await owner();
		const id = await recipe(a, [jpeg()], { title: "Kartoffel‮salat", steps: ["Kochen.\u0007", "​"] });
		const link = await created(a, id);
		const b = await signIn();
		const res = await preview(b, link.token);
		const body = (await res.json()) as typeof previewFixture;
		expect(Object.keys(body)).toEqual(Object.keys(previewFixture));
		expect(Object.keys(body.recipe)).toEqual(Object.keys(previewFixture.recipe));
		expect([body.recipe.title, body.recipe.steps, body.imageCount, body.expiresAt]).toEqual(["Kartoffelsalat", ["Kochen."], 1, link.expiresAt]);

		const accepted = await accept(b, link.token);
		expect(accepted.status).toBe(200);
		const copy = (await accepted.json()) as Recipe;
		expect(Object.keys(copy)).toEqual(Object.keys(acceptFixture));
		expect(copy.id).toBe(await derivedId(`${link.shareId}:${b.userId}`));
		expect([copy.title, copy.steps, copy.sourceType, copy.sourceUrl, copy.imageIds.length]).toEqual(["Kartoffelsalat", ["Kochen."], "shared", null, 1]);
		const picture = await call(`/images/${copy.imageIds[0]}`, { token: b.token });
		expect(picture.status).toBe(200);
		const original = (await sql(a, "SELECT bytes FROM images"))[0].bytes as ArrayBuffer;
		expect(new Uint8Array(await picture.arrayBuffer())).toEqual(new Uint8Array(original));
		// Independent: the owner's change does not reach the copy, and the copy's edit does not reach the owner.
		await update(a, id, { title: "Neu" });
		expect((await list(b))[0].title).toBe("Kartoffelsalat");
		expect((await list(a))[0].title).toBe("Neu");
	});

	it("a repeat returns the existing copy and leaves the recipient's edits untouched; the recipient is counted once", async () => {
		const a = await owner();
		const link = await created(a, await recipe(a));
		const b = await signIn();
		const first = (await (await accept(b, link.token)).json()) as Recipe;
		const edited = await put(b, first.id, { title: "Meins" }, { "if-match": `"${first.rev}"` });
		expect(edited.status).toBe(200);
		const again = await accept(b, link.token);
		expect([again.status, ((await again.json()) as Recipe).title]).toEqual([200, "Meins"]);
		expect(await sql(a, "SELECT count(*) AS n FROM share_accepts")).toEqual([{ n: 1 }]);
		// A deleted copy is created again on the next accept.
		await call(`/recipes/${first.id}`, { method: "DELETE", token: b.token });
		expect(((await (await accept(b, link.token)).json()) as Recipe).title).toBe(inputFixture.title);
	});

	it("the owner stores only a hash of each recipient", async () => {
		const a = await owner();
		const link = await created(a, await recipe(a));
		const b = await signIn();
		await accept(b, link.token);
		const rows = JSON.stringify(await sql(a, "SELECT * FROM share_accepts"));
		expect(rows).not.toContain(b.userId);
	});

	it(`the ${SHARE_RECIPIENT_LIMIT + 1}th distinct recipient gets 410; earlier ones still get their copy`, async () => {
		const a = await owner();
		const link = await created(a, await recipe(a));
		const recipients = await Promise.all(Array.from({ length: SHARE_RECIPIENT_LIMIT }, () => signIn()));
		for (const r of recipients) expect((await accept(r, link.token)).status).toBe(200);
		const late = await signIn();
		const res = await accept(late, link.token);
		expect([res.status, await code(res)]).toEqual([410, "share_gone"]);
		expect((await preview(late, link.token)).status).toBe(410);
		expect((await accept(recipients[0], link.token)).status).toBe(200);
		expect((await preview(recipients[1], link.token)).status).toBe(200);
	});

	it("expired and revoked links and links of a suspended owner answer 410; own links 409; unknown shares 404", async () => {
		const a = await owner();
		const b = await signIn();
		const expired = await created(a, await recipe(a));
		await sql(a, "UPDATE shares SET expires_at = ? WHERE share_id = ?", Date.now() - 1, expired.shareId);
		expect(await code(await accept(b, expired.token))).toBe("share_gone");
		const deletedRecipe = await recipe(a);
		const orphan = await created(a, deletedRecipe);
		await call(`/recipes/${deletedRecipe}`, { method: "DELETE", token: a.token });
		expect((await accept(b, orphan.token)).status).toBe(410);
		const own = await created(a, await recipe(a));
		for (const res of [await accept(a, own.token), await preview(a, own.token), await report(a, own.token)]) {
			expect([res.status, await code(res)]).toEqual([409, "own_share"]);
		}
		const unknown = await newShareToken(a.userId, newShareId(), env);
		expect((await accept(b, unknown)).status).toBe(404);
		await setMeta(a, "state", "suspended");
		expect((await accept(b, own.token)).status).toBe(410);
		expect((await preview(b, own.token)).status).toBe(410);
		expect(await sql(b, "SELECT count(*) AS n FROM recipes")).toEqual([{ n: 0 }]);
	});

	it("a link keeps working until it expires after the owner's subscription lapsed; a new link needs it again", async () => {
		const a = await owner();
		const id = await recipe(a);
		const link = await created(a, id);
		await setMeta(a, "entitlement", JSON.stringify({ active: false, expiresAt: null }));
		const b = await signIn();
		expect((await preview(b, link.token)).status).toBe(200);
		expect((await accept(b, link.token)).status).toBe(200);
		expect(await code(await share(a, id))).toBe("subscription_required");
	});

	it("a recipient at the recipe count or text cap is refused before taking one of the ten places", async () => {
		const a = await owner();
		const link = await created(a, await recipe(a));
		const b = await signIn();
		await setMeta(b, "recipe_bytes", String(20_971_520));
		expect(await code(await accept(b, link.token))).toBe("recipe_storage_cap");
		await setMeta(b, "recipe_bytes", "0");
		await inObject(b.userId, (_i, st) => {
			for (let i = 0; i < 2000; i++) st.storage.sql.exec("INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, 0, 0, 0, '{}')", `filler-${i}`);
		});
		expect(await code(await accept(b, link.token))).toBe("recipe_limit");
		expect(await sql(a, "SELECT count(*) AS n FROM share_accepts")).toEqual([{ n: 0 }]);
	});

	it("eleven concurrent accepts by distinct recipients: exactly ten get a copy, one gets 410", async () => {
		const a = await owner();
		const link = await created(a, await recipe(a));
		const recipients = await Promise.all(Array.from({ length: SHARE_RECIPIENT_LIMIT + 1 }, () => signIn()));
		const statuses = (await Promise.all(recipients.map((r) => accept(r, link.token)))).map((r) => r.status);
		expect(statuses.sort()).toEqual([...Array(SHARE_RECIPIENT_LIMIT).fill(200), 410]);
		expect(await sql(a, "SELECT count(*) AS n FROM share_accepts")).toEqual([{ n: SHARE_RECIPIENT_LIMIT }]);
	});

	it("an accept over the recipient's picture cap, recipe count or text cap copies nothing (413 / 409)", async () => {
		const a = await owner();
		const link = await created(a, await recipe(a, [jpeg()]));
		const b = await signIn();
		await recipe(b, [jpeg()]);
		const before = await sql(b, "SELECT (SELECT count(*) FROM recipes) AS r, (SELECT count(*) FROM images) AS i");
		await sql(b, "UPDATE images SET size = ?", 52_428_800);
		const full = await accept(b, link.token);
		expect([full.status, await code(full)]).toEqual([413, "storage_cap"]);
		await sql(b, "UPDATE images SET size = 10");
		await setMeta(b, "recipe_bytes", String(20_971_520));
		expect(await code(await accept(b, link.token))).toBe("recipe_storage_cap");
		await setMeta(b, "recipe_bytes", "0");
		await inObject(b.userId, (_i, st) => {
			for (let i = 0; i < 2000; i++) st.storage.sql.exec("INSERT INTO recipes (id, rev, deleted, updated_at, json) VALUES (?, 0, 0, 0, '{}')", `filler-${i}`);
		});
		expect(await code(await accept(b, link.token))).toBe("recipe_limit");
		await sql(b, "DELETE FROM recipes WHERE id GLOB 'filler-*'");
		expect(await sql(b, "SELECT (SELECT count(*) FROM recipes) AS r, (SELECT count(*) FROM images) AS i")).toEqual(before);
		expect((await accept(b, link.token)).status).toBe(200);
	});

	it("only pictures that passed the check are shared; a picture added after the link was made is left out", async () => {
		const a = await owner();
		const id = await recipe(a, [jpeg()]);
		const link = await created(a, id);
		const [{ id: first }] = (await sql(a, "SELECT id FROM images")) as { id: string }[];
		const later = uuid();
		await call(`/recipes/${id}/images/${later}`, { method: "PUT", token: a.token, body: jpeg(), headers: { "content-type": "image/jpeg" } });
		await update(a, id, { imageIds: [first, later] });
		const b = await signIn();
		expect(((await (await preview(b, link.token)).json()) as typeof previewFixture).imageCount).toBe(1);
		expect(((await (await accept(b, link.token)).json()) as Recipe).imageIds).toHaveLength(1);
	});
});

describe("reporting a shared recipe", () => {
	it("revokes the link; the metric carries an encrypted reference, never a user id", async () => {
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		const a = await owner();
		const link = await created(a, await recipe(a));
		const b = await signIn();
		expect((await report(b, link.token)).status).toBe(204);
		expect((await report(b, link.token)).status).toBe(204); // a repeat changes nothing
		expect((await accept(b, link.token)).status).toBe(410);
		expect(await sql(b, "SELECT kind, job_key, reason FROM reports")).toEqual([{ kind: "share", job_key: link.shareId, reason: shareReportFixture.reason }]);
		const points = write.mock.calls.map((c) => c[0]!);
		const reports = points.filter((p) => p.blobs?.[0] === "share_report");
		expect(reports.map((p) => p.blobs)).toHaveLength(1);
		expect(reports[0].indexes).toBeUndefined();
		expect(await openReportReference(String(reports[0].blobs![2]), env, Date.now())).toBe(a.userId);
		expect(JSON.stringify(points)).not.toContain(a.userId);
		expect(JSON.stringify(points)).not.toContain(b.userId);
		expect(points.map((p) => p.blobs)).toContainEqual(["share", "reported"]);
	});

	it("a forged token answers 404 and reaches no object", async () => {
		const b = await signIn();
		const victim = "V".repeat(42) + "w";
		const token = await newShareToken(victim, newShareId(), { SERVER_KEY: "attacker-key" });
		expect((await report(b, token)).status).toBe(404);
		expect((await listDurableObjectIds(env.USER_DO)).map((i) => i.toString())).not.toContain(env.USER_DO.idFromName(victim).toString());
	});
});
