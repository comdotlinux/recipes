import { env, runDurableObjectAlarm } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import doneFixture from "../../../contract/fixtures/import_job_done.json";
import pendingFixture from "../../../contract/fixtures/import_job_pending.json";
import reportFixture from "../../../contract/fixtures/report.json";
import textFixture from "../../../contract/fixtures/import_request_text.json";
import { ConsentRequired, GATEWAY_HEADERS, runModel } from "../../src/ai/gateway";
import { STRUCTURE_MODELS, VISION_MODELS } from "../../src/ai/models";
import { type Session, call, inObject, interceptOutbound, signIn, userStub, uuid } from "./helpers";

type Job = typeof doneFixture;
const recipeAnswer = {
	is_recipe: true,
	title: "Kartoffelsalat <b>lecker</b>",
	description: "Siehe https://evil.example/x",
	servings: 4,
	prepMinutes: 20,
	cookMinutes: 25,
	ingredients: [{ quantity: "1", unit: "kg", item: "Kartoffeln", group: null }],
	steps: ["Kartoffeln kochen."],
	tags: [],
	language: "de",
};

/** Test double for the Workers AI binding: `plan` decides each answer by model id. */
type Plan = (model: string, inputs: Record<string, unknown>) => unknown;
let calls: { model: string; inputs: Record<string, unknown>; options: Record<string, unknown> }[] = [];
function fakeAi(plan: Plan) {
	calls = [];
	(env as unknown as { AI?: unknown }).AI = {
		run: async (model: string, inputs: Record<string, unknown>, options: Record<string, unknown>) => {
			calls.push({ model, inputs, options });
			return plan(model, inputs);
		},
	};
}
const answers = (structure: unknown, vision: unknown = { is_recipe: true, text: "Kartoffelsalat\n1 kg Kartoffeln" }): Plan => (model) =>
	model.includes("llama-3") ? { response: structure } : { response: JSON.stringify(vision) };

beforeEach(() => {
	interceptOutbound();
	fakeAi(answers(recipeAnswer));
});
afterEach(() => {
	vi.restoreAllMocks();
	(env as unknown as { AI?: unknown }).AI = undefined;
});

const consent = (s: Session) => call("/me/consent", { method: "PUT", token: s.token, body: JSON.stringify({ version: 1 }), headers: { "content-type": "application/json" } });
const startText = (s: Session, key = uuid(), text = textFixture.text) =>
	call("/imports", { method: "POST", token: s.token, body: JSON.stringify({ kind: "text", text }), headers: { "content-type": "application/json", "idempotency-key": key } });
const job = async (s: Session, key: string) => (await call(`/jobs/${key}`, { token: s.token })).json() as Promise<Job>;
const settle = async (s: Session, key: string) => {
	await vi.waitFor(async () => expect(["pending", "running"]).not.toContain((await job(s, key)).status), { timeout: 5000, interval: 20 });
	return job(s, key);
};
const me = async (s: Session) => (await call("/me", { token: s.token })).json() as Promise<{ consentVersion: number | null; quota: { freeImportUsed: boolean; monthUsed: number } }>;
const code = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;
const ready = async () => {
	const s = await signIn();
	expect((await consent(s)).status).toBe(200);
	return s;
};
const setMeta = (s: Session, key: string, value: string) =>
	inObject(s.userId, (_i, state) => state.storage.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value));
/** Test-only helper: the subscription state that M3 will write from RevenueCat. */
const subscribe = (s: Session, expiresAt: string | null) => setMeta(s, "entitlement", JSON.stringify({ active: true, expiresAt }));

describe("AI consent (AC12)", () => {
	it("import needs consent; PUT gives it, DELETE takes it back", async () => {
		const s = await signIn();
		expect([(await startText(s)).status]).toEqual([403]);
		expect(await code(await startText(s))).toBe("consent_required");
		const put = await consent(s);
		expect(await put.json()).toEqual({ consentVersion: 1 });
		expect((await me(s)).consentVersion).toBe(1);
		expect((await call("/me/consent", { method: "DELETE", token: s.token })).status).toBe(204);
		expect((await me(s)).consentVersion).toBeNull();
		expect((await startText(s)).status).toBe(403);
		expect(calls).toEqual([]);
	});

	it("400 for an unknown notice version or a bad body", async () => {
		const s = await signIn();
		const res = await call("/me/consent", { method: "PUT", token: s.token, body: JSON.stringify({ version: 2 }), headers: { "content-type": "application/json" } });
		expect(await code(res)).toBe("consent_version_unknown");
		expect(await code(await call("/me/consent", { method: "PUT", token: s.token, body: "{", headers: {} }))).toBe("invalid_body");
		expect(await code(await call("/me/consent", { method: "PUT", token: s.token, body: "{}", headers: {} }))).toBe("invalid_body");
	});

	it("the gateway wrapper refuses any model call without the current consent version", async () => {
		for (const version of [null, 0, 2]) {
			await expect(runModel(env as never, version, "structure", STRUCTURE_MODELS.primary, {})).rejects.toBeInstanceOf(ConsentRequired);
		}
		expect(calls).toEqual([]);
	});
});

describe("text import", () => {
	it("202 pending in the contract shape, then done with a draft; nothing is saved", async () => {
		const s = await ready();
		const key = uuid();
		const res = await startText(s, key);
		expect(res.status).toBe(202);
		const started = (await res.json()) as Job;
		expect(Object.keys(started)).toEqual(Object.keys(pendingFixture));
		expect({ ...started, createdAt: "T" }).toEqual({ ...pendingFixture, id: key, createdAt: "T" });
		const done = await settle(s, key);
		expect(Object.keys(done)).toEqual(Object.keys(doneFixture));
		expect(Object.keys(done.draft)).toEqual(Object.keys(doneFixture.draft));
		expect({ ...done.draft, id: "X" }).toEqual({ ...doneFixture.draft, id: "X", title: "Kartoffelsalat lecker", description: "Siehe", sourceUrl: null, sourceType: "photo" });
		expect((await (await call("/recipes", { token: s.token })).json()) as { recipes: unknown[] }).toMatchObject({ recipes: [] });
		expect((await me(s)).quota.freeImportUsed).toBe(true);
	});

	it("every model request goes through the gateway without payload logging; the input is gone afterwards (AC16)", async () => {
		const s = await ready();
		const key = uuid();
		await startText(s, key);
		await settle(s, key);
		expect(calls.length).toBeGreaterThan(0);
		for (const c of calls) {
			expect(c.options).toMatchObject({ gateway: { id: "recipes", skipCache: true }, extraHeaders: GATEWAY_HEADERS });
			expect(c.options.extraHeaders).toEqual({ "cf-aig-collect-log-payload": "false", "cf-aig-skip-cache": "true" });
		}
		const user = calls[0].inputs.messages as { role: string; content: string }[];
		expect(user.map((m) => m.role)).toEqual(["system", "user"]);
		expect(user[1].content).toBe(textFixture.text);
		expect(calls[0].inputs).not.toHaveProperty("tools");
		const row = await inObject(s.userId, (_i, state) => state.storage.sql.exec("SELECT input, input_blob FROM jobs").one());
		expect(row).toEqual({ input: null, input_blob: null });
	});

	it("validation: 400 for a bad key or body, 413 over 20,000 characters, 415 for other types, 411 without length", async () => {
		const s = await ready();
		const bad = await call("/imports", { method: "POST", token: s.token, body: "{}", headers: { "content-type": "application/json", "idempotency-key": "nope" } });
		expect(await code(bad)).toBe("invalid_id");
		expect(await code(await call("/imports", { method: "POST", token: s.token, body: "{}", headers: { "content-type": "application/json", "idempotency-key": uuid() } }))).toBe("invalid_body");
		expect(await code(await startText(s, uuid(), ""))).toBe("invalid_body");
		const long = await startText(s, uuid(), "x".repeat(20_001));
		expect([long.status, await code(long)]).toEqual([413, "text_too_large"]);
		expect(await code(await call("/imports", { method: "POST", token: s.token, body: "x", headers: { "content-type": "text/plain", "idempotency-key": uuid() } }))).toBe("unsupported_media_type");
		const stream = new ReadableStream({ start: (c) => (c.enqueue(new TextEncoder().encode("{}")), c.close()) });
		expect(await code(await call("/imports", { method: "POST", token: s.token, body: stream, headers: { "content-type": "application/json", "idempotency-key": uuid() } }))).toBe("length_required");
	});
});

describe("URL import (AC19 at route level)", () => {
	const page = `<script type="application/ld+json">{"@type":"Recipe","name":"Kartoffelsalat"}</script>`;
	// Factories: a Response made in the test's context cannot be used from the object's request (cross-request I/O).
	const web = (pages: Record<string, () => Response>) => {
		const real = globalThis.fetch;
		vi.restoreAllMocks();
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
			const url = new Request(input, init).url;
			if (url.startsWith("https://security.cloudflare-dns.com/")) return Response.json({ Status: 0, Answer: [{ data: "93.184.215.14" }] });
			if (pages[url]) return pages[url]();
			return real(input, init);
		});
	};
	const startUrl = (s: Session, url: string, key = uuid()) =>
		call("/imports", { method: "POST", token: s.token, body: JSON.stringify({ kind: "url", url }), headers: { "content-type": "application/json", "idempotency-key": key } });

	it("fetches the page, sends the JSON-LD text to the model, and keeps the URL in the draft", async () => {
		const s = await ready();
		web({ "https://example.org/kartoffelsalat": () => new Response(page, { headers: { "content-type": "text/html" } }) });
		const key = uuid();
		expect((await startUrl(s, "https://example.org/kartoffelsalat", key)).status).toBe(202);
		const done = await settle(s, key);
		expect(done.status).toBe("done");
		expect([done.kind, done.draft.sourceType, done.draft.sourceUrl]).toEqual(["url", "url", "https://example.org/kartoffelsalat"]);
		expect((calls[0].inputs.messages as { content: string }[])[1].content).toBe('{"@type":"Recipe","name":"Kartoffelsalat"}');
	});

	it("400 invalid_url before any fetch or charge for hostile URLs", async () => {
		const s = await ready();
		for (const url of ["http://169.254.169.254/", "https://user:pw@example.org/", "https://example.org:8443/", "https://munich-tech.de/", "file:///etc/passwd"]) {
			const res = await startUrl(s, url);
			expect([url, res.status, await code(res)]).toEqual([url, 400, "invalid_url"]);
		}
		expect((await me(s)).quota.freeImportUsed).toBe(false);
	});

	it("a page that fails the fetch rules ends as no_recipe with a refund and one counted attempt", async () => {
		const s = await ready();
		web({ "https://example.org/r": () => new Response(null, { status: 302, headers: { location: "http://10.0.0.1/" } }) });
		const key = uuid();
		await startUrl(s, "https://example.org/r", key);
		expect((await settle(s, key)).status).toBe("no_recipe");
		expect((await me(s)).quota.freeImportUsed).toBe(false);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count FROM attempts").one().count)).toBe(1);
		expect(calls).toEqual([]);
	});
});

describe("photo import", () => {
	const jpeg = (size = 200) => {
		const b = new Uint8Array(size);
		b.set([0xff, 0xd8, 0xff, 0xe0]);
		return b;
	};
	const startPhoto = (s: Session, bytes: Uint8Array, key = uuid(), type = "image/jpeg") =>
		call("/imports", { method: "POST", token: s.token, body: bytes, headers: { "content-type": type, "idempotency-key": key } });

	it("transcribes with the vision model, then structures; the photo is removed afterwards", async () => {
		const s = await ready();
		const key = uuid();
		expect((await startPhoto(s, jpeg(), key)).status).toBe(202);
		const done = await settle(s, key);
		expect([done.status, done.kind, done.draft.sourceType]).toEqual(["done", "photo", "photo"]);
		expect(calls.map((c) => c.model)).toEqual([VISION_MODELS.primary, STRUCTURE_MODELS.primary]);
		const content = (calls[0].inputs.messages as { content: { type: string; image_url?: { url: string } }[] }[])[0].content;
		expect(content[1].image_url?.url.startsWith("data:image/jpeg;base64,/9j/4")).toBe(true);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT input_blob FROM jobs").one().input_blob)).toBeNull();
	});

	it("a photo that is not a recipe ends as no_recipe", async () => {
		const s = await ready();
		fakeAi(answers(recipeAnswer, { is_recipe: false, text: "" }));
		const key = uuid();
		await startPhoto(s, jpeg(), key);
		expect((await settle(s, key)).status).toBe("no_recipe");
	});

	it("413 above 1.5 MB, 415 when the bytes are not a JPEG", async () => {
		const s = await ready();
		const big = await startPhoto(s, jpeg(1_572_865));
		expect([big.status, await code(big)]).toEqual([413, "image_too_large"]);
		const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
		expect(await code(await startPhoto(s, png))).toBe("unsupported_media_type");
	});
});

describe("outcomes and the unit (AC17)", () => {
	const outcome = async (plan: Plan) => {
		const s = await ready();
		fakeAi(plan);
		const key = uuid();
		await startText(s, key);
		const result = await settle(s, key);
		const attempts = await inObject(s.userId, (_i, st) => (st.storage.sql.exec("SELECT count FROM attempts").toArray()[0]?.count as number | undefined) ?? 0);
		return { status: result.status, draft: result.draft, freeUsed: (await me(s)).quota.freeImportUsed, attempts };
	};

	it("guard_block, no_recipe and invalid refund the unit and count one attempt", async () => {
		const blocked = await outcome(() => {
			throw new Error("AiError: Prompt blocked due to security configurations");
		});
		expect(blocked).toEqual({ status: "guard_block", draft: null, freeUsed: false, attempts: 1 });
		expect(await outcome(answers({ ...recipeAnswer, is_recipe: false }))).toMatchObject({ status: "no_recipe", freeUsed: false, attempts: 1 });
		expect(await outcome(answers({ ...recipeAnswer, title: "" }))).toMatchObject({ status: "invalid", freeUsed: false, attempts: 1 });
		expect(await outcome(answers({ nonsense: true }))).toMatchObject({ status: "invalid", freeUsed: false, attempts: 1 });
	});

	it("a model failing 3 times ends as failed with a refund and no attempt; the task is retried alone", async () => {
		const s = await ready();
		fakeAi(() => {
			throw new Error("upstream 500");
		});
		const key = uuid();
		await startText(s, key);
		await vi.waitFor(() => expect(calls).toHaveLength(2), { timeout: 5000, interval: 20 }); // primary and fallback, try 1
		for (let i = 0; i < 2; i++) {
			await inObject(s.userId, async (_i, st) => {
				st.storage.sql.exec("UPDATE tasks SET due_at = ? WHERE kind = 'job'", Date.now() - 1);
				await st.storage.setAlarm(Date.now() - 1);
			});
			await runDurableObjectAlarm(userStub(s.userId));
		}
		expect(calls).toHaveLength(6);
		expect((await job(s, key)).status).toBe("failed");
		expect((await me(s)).quota.freeImportUsed).toBe(false);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM attempts").one().n)).toBe(0);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM tasks WHERE kind LIKE 'job%'").one().n)).toBe(0);
	});

	it("a job still open after 10 minutes ends as failed with a refund and no attempt", async () => {
		const s = await ready();
		fakeAi(() => {
			throw new Error("upstream 500");
		});
		const key = uuid();
		await startText(s, key);
		await vi.waitFor(() => expect(calls).toHaveLength(2), { timeout: 5000, interval: 20 });
		await inObject(s.userId, (instance) => instance.timeoutJob(key, Date.now()));
		expect((await job(s, key)).status).toBe("failed");
		expect((await me(s)).quota.freeImportUsed).toBe(false);
	});

	it("a free user gets exactly one import; a repeat with the same key charges nothing", async () => {
		const s = await ready();
		const key = uuid();
		await startText(s, key);
		await settle(s, key);
		const again = await startText(s, key);
		expect([again.status, ((await again.json()) as Job).status]).toEqual([202, "done"]);
		const second = await startText(s);
		expect([second.status, await code(second)]).toEqual([402, "quota_exhausted"]);
	});

	it("409 while another import is open; two parallel requests cannot both take the last unit", async () => {
		const s = await ready();
		fakeAi(() => new Promise(() => {})); // never answers
		const results = await Promise.all([startText(s), startText(s)]);
		expect(results.map((r) => r.status).sort()).toEqual([202, 409]);
	});

	it("the free unit is used first, then 30 monthly units while subscribed; a lapsed subscription gets 402", async () => {
		const s = await ready();
		await subscribe(s, null);
		const first = uuid();
		await startText(s, first);
		await settle(s, first);
		expect(await me(s)).toMatchObject({ quota: { freeImportUsed: true, monthUsed: 0 } });
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO usage (period, count) VALUES (?, 29)", new Date().toISOString().slice(0, 7)));
		const last = uuid();
		expect((await startText(s, last)).status).toBe(202);
		await settle(s, last);
		expect((await me(s)).quota.monthUsed).toBe(30);
		expect(await code(await startText(s))).toBe("quota_exhausted");
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("UPDATE usage SET count = 0"));
		await subscribe(s, "2020-01-01T00:00:00Z");
		expect(await code(await startText(s))).toBe("quota_exhausted");
	});

	it("a refunded monthly unit is given back", async () => {
		const s = await ready();
		await setMeta(s, "free_import_used_at", "1");
		await subscribe(s, null);
		fakeAi(answers({ ...recipeAnswer, is_recipe: false }));
		const key = uuid();
		await startText(s, key);
		await settle(s, key);
		expect((await me(s)).quota.monthUsed).toBe(0);
	});

	it("the eleventh counted attempt in a day returns 429 attempt_limit", async () => {
		const s = await ready();
		await inObject(s.userId, (_i, st) => st.storage.sql.exec("INSERT INTO attempts (day, count) VALUES (?, 10)", new Date().toISOString().slice(0, 10)));
		const res = await startText(s);
		expect([res.status, await code(res)]).toEqual([429, "attempt_limit"]);
	});

	it("the free unit is refunded at most 5 times; then the next import consumes it whatever the outcome", async () => {
		const s = await ready();
		await setMeta(s, "free_import_refunds", "5");
		fakeAi(answers({ ...recipeAnswer, is_recipe: false }));
		const key = uuid();
		await startText(s, key);
		expect((await settle(s, key)).status).toBe("no_recipe");
		expect((await me(s)).quota.freeImportUsed).toBe(true);
	});
});

describe("jobs and reports", () => {
	it("GET /me openJobId: null without a job, the id while it is open, null after it ends, never another user's job", async () => {
		const openJob = async (s: Session) => ((await (await call("/me", { token: s.token })).json()) as { openJobId: string | null }).openJobId;
		const [s, other] = [await ready(), await ready()];
		expect(await openJob(s)).toBeNull();
		fakeAi(() => new Promise(() => {})); // never answers: the job stays open
		const key = uuid();
		expect((await startText(s, key)).status).toBe(202);
		expect(await openJob(s)).toBe(key);
		expect(await openJob(other)).toBeNull();
		await inObject(s.userId, (instance) => instance.timeoutJob(key, Date.now()));
		expect(await openJob(s)).toBeNull();
	});

	it("404 for another user's job, an unknown job and a job older than 7 days", async () => {
		const a = await ready();
		const b = await ready();
		const key = uuid();
		await startText(a, key);
		await settle(a, key);
		expect((await call(`/jobs/${key}`, { token: b.token })).status).toBe(404);
		expect((await call(`/jobs/${uuid()}`, { token: a.token })).status).toBe(404);
		expect((await call("/jobs/nope", { token: a.token })).status).toBe(400);
		await inObject(a.userId, (_i, st) => st.storage.sql.exec("UPDATE jobs SET created_at = created_at - ?", 8 * 86_400_000));
		expect((await call(`/jobs/${key}`, { token: a.token })).status).toBe(404);
	});

	it("POST /reports records a report for the user's own job (AC33)", async () => {
		const s = await ready();
		const key = uuid();
		await startText(s, key);
		await settle(s, key);
		const body = JSON.stringify({ ...reportFixture, jobId: key, note: "falsche Mengen" });
		const res = await call("/reports", { method: "POST", token: s.token, body, headers: { "content-type": "application/json" } });
		expect(res.status).toBe(204);
		const rows = await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT kind, job_key, reason, note FROM reports").toArray());
		expect(rows).toEqual([{ kind: "import_result", job_key: key, reason: "wrong_content", note: "falsche Mengen" }]);
		const other = JSON.stringify({ ...reportFixture, jobId: uuid() });
		expect((await call("/reports", { method: "POST", token: s.token, body: other, headers: {} })).status).toBe(404);
		expect(await code(await call("/reports", { method: "POST", token: s.token, body: JSON.stringify({ ...reportFixture, reason: "x" }), headers: {} }))).toBe("invalid_body");
	});

	it("metrics carry outcomes and codes only: no text, no URL", async () => {
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		const s = await ready();
		const key = uuid();
		await startText(s, key);
		await settle(s, key);
		const blobs = write.mock.calls.map((c) => c[0]?.blobs ?? []);
		expect(blobs).toContainEqual(["import_outcome", "import_text", "done"]);
		const all = JSON.stringify(write.mock.calls);
		expect(all).not.toContain("Kartoffel");
		expect(all).not.toContain(s.userId);
	});
});

describe("local AI stand-in (local entry only)", () => {
	const local = async () => {
		(env as unknown as { AI?: unknown }).AI = undefined;
		return ready();
	};
	const run = async (s: Session, text: string) => {
		const key = uuid();
		await startText(s, key, text);
		return key;
	};

	it("builds a recipe from the text: first line as title, the rest as steps", async () => {
		const s = await local();
		const done = await settle(s, await run(s, "Pancakes\nMix the batter.\nFry."));
		expect([done.status, done.draft.title, done.draft.steps]).toEqual(["done", "Pancakes", ["Mix the batter.", "Fry."]]);
	});

	it("produces no_recipe and guard_block on purpose", async () => {
		const s = await local();
		expect((await settle(s, await run(s, "Hello __NO_RECIPE__"))).status).toBe("no_recipe");
		expect((await settle(s, await run(s, "Soup __GUARD__"))).status).toBe("guard_block");
	});

	it("__FAIL__ fails every try, so the job ends as failed after the retries", async () => {
		const s = await local();
		const key = await run(s, "Soup __FAIL__");
		await vi.waitFor(async () => expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT attempts FROM tasks WHERE kind = 'job'").toArray()[0]?.attempts)).toBe(1), { timeout: 5000, interval: 20 });
		for (let i = 0; i < 2; i++) {
			await inObject(s.userId, async (_i, st) => {
				st.storage.sql.exec("UPDATE tasks SET due_at = ? WHERE kind = 'job'", Date.now() - 1);
				await st.storage.setAlarm(Date.now() - 1);
			});
			await runDurableObjectAlarm(userStub(s.userId));
		}
		expect((await job(s, key)).status).toBe("failed");
	});

	it("reads a fixed text from any photo", async () => {
		const s = await local();
		const key = uuid();
		const bytes = new Uint8Array(64);
		bytes.set([0xff, 0xd8, 0xff, 0xe0]);
		await call("/imports", { method: "POST", token: s.token, body: bytes, headers: { "content-type": "image/jpeg", "idempotency-key": key } });
		const done = await settle(s, key);
		expect([done.status, done.draft.title]).toEqual(["done", "Local photo recipe"]);
	});
});

describe("security review M2", () => {
	it("a job whose tries were used up by killed runs ends as failed and frees the account (F1)", async () => {
		const s = await ready();
		fakeAi(() => new Promise(() => {})); // never answers, like a run the runtime kills
		const key = uuid();
		await startText(s, key);
		await vi.waitFor(() => expect(calls).toHaveLength(1), { timeout: 5000, interval: 20 });
		await inObject(s.userId, async (_i, st) => {
			st.storage.sql.exec("UPDATE tasks SET attempts = 3, due_at = ? WHERE kind = 'job'", Date.now() - 1);
			await st.storage.setAlarm(Date.now() - 1);
		});
		await runDurableObjectAlarm(userStub(s.userId));
		expect((await job(s, key)).status).toBe("failed");
		expect((await me(s)).quota.freeImportUsed).toBe(false);
		fakeAi(answers(recipeAnswer));
		expect((await startText(s)).status).toBe(202); // no longer job_running
	});

	it("a job older than the timeout ends as failed at its next run (F1)", async () => {
		const s = await ready();
		fakeAi(() => {
			throw new Error("upstream 500");
		});
		const key = uuid();
		await startText(s, key);
		await vi.waitFor(() => expect(calls).toHaveLength(2), { timeout: 5000, interval: 20 });
		await inObject(s.userId, async (_i, st) => {
			st.storage.sql.exec("UPDATE jobs SET created_at = created_at - ?", 11 * 60_000);
			st.storage.sql.exec("UPDATE tasks SET due_at = ? WHERE kind = 'job'", Date.now() - 1);
			await st.storage.setAlarm(Date.now() - 1);
		});
		await runDurableObjectAlarm(userStub(s.userId));
		expect((await job(s, key)).status).toBe("failed");
		expect(calls).toHaveLength(2);
	});

	it("one report per job and kind; a repeat is still 204 (F2)", async () => {
		const s = await ready();
		const key = uuid();
		await startText(s, key);
		await settle(s, key);
		const body = JSON.stringify({ ...reportFixture, jobId: key });
		for (let i = 0; i < 3; i++) expect((await call("/reports", { method: "POST", token: s.token, body, headers: {} })).status).toBe(204);
		expect(await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM reports").one().n)).toBe(1);
	});

	it("consent withdrawn while the page is fetched: no model call, failed, refunded (F3)", async () => {
		const s = await ready();
		const real = globalThis.fetch;
		vi.restoreAllMocks();
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
			const url = new Request(input, init).url;
			if (url.startsWith("https://security.cloudflare-dns.com/")) return Response.json({ Status: 0, Answer: [] });
			if (url === "https://example.org/r") {
				expect((await call("/me/consent", { method: "DELETE", token: s.token })).status).toBe(204);
				return new Response("<h1>Suppe</h1><p>Wasser kochen.</p>", { headers: { "content-type": "text/html" } });
			}
			return real(input, init);
		});
		const key = uuid();
		await call("/imports", { method: "POST", token: s.token, body: JSON.stringify({ kind: "url", url: "https://example.org/r" }), headers: { "content-type": "application/json", "idempotency-key": key } });
		expect((await settle(s, key)).status).toBe("failed");
		expect(calls).toEqual([]);
		expect((await me(s)).quota.freeImportUsed).toBe(false);
	});

	it("drafts lose control and bidirectional characters (F6)", async () => {
		const s = await ready();
		fakeAi(answers({ ...recipeAnswer, title: "\uFEFFKartoffel\u202Esalat\u0007\u200F\u200B\u061C", steps: ["Ko\u2066chen\u0000.\u2028\u2029"] }));
		const key = uuid();
		await startText(s, key);
		const done = await settle(s, key);
		expect([done.draft.title, done.draft.steps]).toEqual(["Kartoffelsalat", ["Kochen."]]);
	});
});

