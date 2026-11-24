import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import uploadFixture from "../../../contract/fixtures/image_upload_response.json";
import inputFixture from "../../../contract/fixtures/recipe_input.json";
import { FREE_CAP_BYTES, MAX_IMAGE_BYTES } from "../../src/images";
import { type Session, call, inObject, interceptOutbound, signIn, uuid } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => vi.restoreAllMocks());

const JPEG = [0xff, 0xd8, 0xff, 0xe0];
const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const WEBP = [0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50];
const image = (magic: number[], size = 64) => {
	const b = new Uint8Array(size);
	b.set(magic);
	return b;
};
const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;

async function recipe(s: Session, imageIds: string[] = [], id = uuid(), header: Record<string, string> = { "if-none-match": "*" }) {
	const res = await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify({ ...inputFixture, id, imageIds }), headers: header });
	return { id, res };
}
const upload = (s: Session, recipeId: string, bytes: Uint8Array, mime = "image/jpeg", imageId = uuid()) =>
	call(`/recipes/${recipeId}/images/${imageId}`, { method: "PUT", token: s.token, body: bytes, headers: { "content-type": mime } });

describe("image upload and download", () => {
	for (const [mime, magic] of [["image/jpeg", JPEG], ["image/png", PNG], ["image/webp", WEBP]] as const) {
		it(`${mime}: 201 in the contract shape, then GET returns the bytes`, async () => {
			const s = await signIn();
			const { id } = await recipe(s);
			const imageId = uuid();
			const bytes = image(magic, 300);
			const res = await upload(s, id, bytes, mime, imageId);
			expect(res.status).toBe(201);
			const body = await res.json();
			expect(Object.keys(body as object)).toEqual(Object.keys(uploadFixture));
			expect(body).toEqual({ id: imageId, size: 300, mime });
			const got = await call(`/images/${imageId}`, { token: s.token });
			expect(got.status).toBe(200);
			expect(got.headers.get("content-type")).toBe(mime);
			expect(got.headers.get("cache-control")).toBe("private, immutable, max-age=31536000");
			expect(got.headers.get("x-content-type-options")).toBe("nosniff");
			expect(got.headers.get("content-security-policy")).toBe("sandbox");
			expect(new Uint8Array(await got.arrayBuffer())).toEqual(bytes);
		});
	}

	it("the recipe's imageIds decide what stays: unlisted images are removed on save", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		const a = uuid();
		const b = uuid();
		await upload(s, id, image(JPEG), "image/jpeg", a);
		await upload(s, id, image(JPEG), "image/jpeg", b);
		expect((await recipe(s, [a], id, { "if-match": '"1"' })).res.status).toBe(200);
		expect((await call(`/images/${a}`, { token: s.token })).status).toBe(200);
		expect((await call(`/images/${b}`, { token: s.token })).status).toBe(404);
	});

	it("deleting a recipe removes its images", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		const a = uuid();
		await upload(s, id, image(JPEG), "image/jpeg", a);
		await call(`/recipes/${id}`, { method: "DELETE", token: s.token });
		expect((await call(`/images/${a}`, { token: s.token })).status).toBe(404);
	});

	it("re-uploading the same image id replaces it; an image id of another recipe is 404", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		const { id: other } = await recipe(s);
		const a = uuid();
		await upload(s, id, image(JPEG, 10), "image/jpeg", a);
		expect((await upload(s, id, image(PNG, 20), "image/png", a)).status).toBe(201);
		expect((await call(`/images/${a}`, { token: s.token })).headers.get("content-type")).toBe("image/png");
		expect((await upload(s, other, image(JPEG), "image/jpeg", a)).status).toBe(404);
	});

	it("404 when the recipe does not exist or is deleted, and for an unknown image", async () => {
		const s = await signIn();
		expect((await upload(s, uuid(), image(JPEG))).status).toBe(404);
		expect((await call(`/images/${uuid()}`, { token: s.token })).status).toBe(404);
	});
});

describe("limits (AC8)", () => {
	it("413 image_too_large above 1,572,864 bytes; exactly the limit is accepted", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		const res = await upload(s, id, image(JPEG, MAX_IMAGE_BYTES + 1));
		expect(res.status).toBe(413);
		expect(await code(res)).toBe("image_too_large");
		expect((await upload(s, id, image(JPEG, MAX_IMAGE_BYTES))).status).toBe(201);
	});

	it("409 image_limit for a sixth image", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		for (let i = 0; i < 5; i++) expect((await upload(s, id, image(JPEG))).status).toBe(201);
		const res = await upload(s, id, image(JPEG));
		expect(res.status).toBe(409);
		expect(await code(res)).toBe("image_limit");
	});

	it("415 when the magic bytes do not match the declared type, or the type is not allowed", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		for (const [bytes, mime] of [[image(PNG), "image/jpeg"], [image([0x47, 0x49, 0x46, 0x38]), "image/gif"], [new TextEncoder().encode("<svg/>"), "image/png"], [new Uint8Array(0), "image/jpeg"]] as const) {
			const res = await upload(s, id, bytes, mime);
			expect(res.status).toBe(415);
			expect(await code(res)).toBe("unsupported_media_type");
		}
	});

	const fillStorage = (s: Session, bytes: number, active: boolean) =>
		inObject(s.userId, (_i, state) => {
			state.storage.sql.exec("INSERT INTO images (id, recipe_id, mime, bytes, size) VALUES (?, ?, 'image/jpeg', x'00', ?)", uuid(), uuid(), bytes);
			state.storage.sql.exec("INSERT INTO meta (key, value) VALUES ('entitlement', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", JSON.stringify({ active, expiresAt: null }));
		});

	it("413 storage_cap above 50 MB for a free user", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		await fillStorage(s, FREE_CAP_BYTES - 100, false);
		const res = await upload(s, id, image(JPEG, 101));
		expect(res.status).toBe(413);
		expect(await code(res)).toBe("storage_cap");
		expect((await upload(s, id, image(JPEG, 100))).status).toBe(201);
	});

	it("a subscriber may go above 50 MB; after lapsing, data stays and only new uploads get 413", async () => {
		const s = await signIn();
		const { id } = await recipe(s);
		const kept = uuid();
		await fillStorage(s, 60 * 1024 * 1024, true);
		expect((await upload(s, id, image(JPEG), "image/jpeg", kept)).status).toBe(201);
		const me = (await (await call("/me", { token: s.token })).json()) as { storage: { usedBytes: number; capBytes: number } };
		expect(me.storage.capBytes).toBe(524_288_000);
		await fillStorage(s, 0, false);
		expect((await call(`/images/${kept}`, { token: s.token })).status).toBe(200);
		expect((await call("/recipes", { token: s.token })).status).toBe(200);
		const res = await upload(s, id, image(JPEG));
		expect(res.status).toBe(413);
		expect(await code(res)).toBe("storage_cap");
	});
});
