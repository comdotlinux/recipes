import { afterEach, beforeEach, describe, expect, it } from "vitest";
import meFixture from "../../../contract/fixtures/me.json";
import requestFixture from "../../../contract/fixtures/session_request.json";
import sessionFixture from "../../../contract/fixtures/session_response.json";
import { testIdToken } from "../../src/testing/test-idp";
import { DAY_MS, SESSION_TTL_MS } from "../../src/user-do";
import { vi } from "vitest";
import { TURNSTILE_OK, call, freshIp, inObject, interceptOutbound, json, nonce, providerToken, signIn } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => vi.restoreAllMocks());

const session = (body: Record<string, unknown>, ip?: string) => call("/session", { method: "POST", ip, ...json(body) });
const errorCode = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code;

async function attempt(provider: "apple" | "google", o: Parameters<typeof providerToken>[2] = {}, extra: Record<string, unknown> = {}) {
	const n = await nonce();
	return session({ provider, idToken: await providerToken(provider, n, o), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true, ...extra });
}

describe("POST /session/nonce", () => {
	it("returns a nonce", async () => {
		const res = await call("/session/nonce", { method: "POST" });
		expect(res.status).toBe(200);
		expect(typeof ((await res.json()) as { nonce: string }).nonce).toBe("string");
	});
});

describe("POST /session success", () => {
	for (const provider of ["apple", "google"] as const) {
		it(`${provider}: valid id token gives a session in the contract shape`, async () => {
			const res = await attempt(provider, { sub: `ok-${provider}` });
			expect(res.status).toBe(200);
			const body = (await res.json()) as typeof sessionFixture;
			expect(Object.keys(body)).toEqual(Object.keys(sessionFixture));
			expect(body.token.split(".")).toEqual(["v1", body.userId, expect.stringMatching(/^[A-Za-z0-9_-]{43}$/), expect.stringMatching(/^[A-Za-z0-9_-]{43}$/)]);
			expect(body.userId).toMatch(/^[A-Za-z0-9_-]{43}$/);
			expect(body.expiresAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
		});
	}

	it("the same provider account reaches the same user id", async () => {
		const a = (await (await attempt("google", { sub: "same" })).json()) as { userId: string };
		const b = (await (await attempt("google", { sub: "same" })).json()) as { userId: string };
		expect(a.userId).toBe(b.userId);
	});
});

describe("POST /session refusals (AC4)", () => {
	const cases: [string, () => Promise<Response>, number, string][] = [
		["body that is not JSON", () => call("/session", { method: "POST", body: "{" }), 400, "invalid_body"],
		["missing age confirmation", () => attempt("google", {}, { ageConfirmed: false }), 400, "age_confirmation_required"],
		["missing Turnstile token", () => attempt("google", {}, { turnstileToken: "" }), 400, "invalid_body"],
		["absent Turnstile token field", async () => {
			const n = await nonce();
			return session({ provider: "google", idToken: await providerToken("google", n), nonce: n, ageConfirmed: true });
		}, 400, "invalid_body"],
		["invalid Turnstile token", () => attempt("google", {}, { turnstileToken: "bad" }), 401, "invalid_turnstile"],
		["expired id token", () => attempt("google", { expSeconds: -120 }), 401, "invalid_token"],
		["wrong aud", () => attempt("google", { aud: "someone.else" }), 401, "invalid_token"],
		["wrong iss", () => attempt("apple", { iss: "https://evil.example" }), 401, "invalid_token"],
		["bad signature", () => attempt("apple", { wrongKey: true }), 401, "invalid_token"],
		["alg none", () => attempt("google", { algNone: true }), 401, "invalid_token"],
		["nonce not issued by the server", async () => {
			const fake = "n1.9999999999999.AAAA";
			return session({ provider: "google", idToken: await providerToken("google", fake), nonce: fake, turnstileToken: TURNSTILE_OK, ageConfirmed: true });
		}, 401, "invalid_nonce"],
		["nonce claim mismatch", () => attempt("google", { nonceClaim: "other" }), 401, "invalid_token"],
		["Google with the hashed (Apple) nonce form", async () => {
			const n = await nonce();
			const hashed = await providerToken("apple", n);
			const claim = JSON.parse(atob(hashed.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).nonce as string;
			return session({ provider: "google", idToken: await providerToken("google", n, { nonceClaim: claim }), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true });
		}, 401, "invalid_token"],
		["Apple with the raw (Google) nonce form", async () => {
			const n = await nonce();
			return session({ provider: "apple", idToken: await providerToken("apple", n, { nonceClaim: n }), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true });
		}, 401, "invalid_token"],
	];
	for (const [name, run, status, code] of cases) {
		it(name, async () => {
			const res = await run();
			expect(res.status).toBe(status);
			expect(await errorCode(res)).toBe(code);
		});
	}

	it("expired nonce", async () => {
		const n = await nonce();
		vi.useFakeTimers({ now: Date.now() + 6 * 60_000, toFake: ["Date"] });
		try {
			const res = await session({ provider: "google", idToken: await providerToken("google", n), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true });
			expect(res.status).toBe(401);
			expect(await errorCode(res)).toBe("invalid_nonce");
		} finally {
			vi.useRealTimers();
		}
	});

	it("second use of the same nonce", async () => {
		const n = await nonce();
		const body = { provider: "google", idToken: await providerToken("google", n, { sub: "twice" }), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true };
		expect((await session(body)).status).toBe(200);
		const again = await session(body);
		expect(again.status).toBe(401);
		expect(await errorCode(again)).toBe("invalid_nonce");
	});

	it("production providers only: the local entry also accepts provider test, with its nonce check", async () => {
		const n = await nonce();
		const wrong = await session({ provider: "test", idToken: testIdToken("t", "other"), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true });
		expect(wrong.status).toBe(401);
		expect(await errorCode(wrong)).toBe("invalid_token");
	});

	it("409 while the account is being deleted", async () => {
		const s = await signIn("deleting-user");
		await inObject(s.userId, (_i, state) => state.storage.sql.exec("UPDATE meta SET value = 'deleting' WHERE key = 'state'"));
		const n = await nonce();
		const res = await session({ provider: "test", idToken: testIdToken("deleting-user", n), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true });
		expect(res.status).toBe(409);
		expect(await errorCode(res)).toBe("account_deleting");
		expect((await call("/me", { token: s.token })).status).toBe(401);
	});
});

describe("rate limits (AC11)", () => {
	for (const route of ["/session/nonce", "/session"]) {
		it(`${route} returns 429 once the per-IP binding reports the caller as limited`, async () => {
			const ip = freshIp();
			const statuses: number[] = [];
			for (let i = 0; i < 25; i++) statuses.push((await call(route, { method: "POST", ip, ...json({}) })).status);
			expect(statuses).toContain(429);
			const limited = await call(route, { method: "POST", ip, ...json({}) });
			expect(limited.status).toBe(429);
			expect(await errorCode(limited)).toBe("rate_limited");
			expect((await call("/session/nonce", { method: "POST" })).status).toBe(200); // other IPs are not affected
		});
	}
});

describe("GET /me", () => {
	it("equals the contract fixture for a new account", async () => {
		const s = await signIn();
		const res = await call("/me", { token: s.token });
		expect(res.status).toBe(200);
		expect(await res.json()).toEqual({ ...meFixture, userId: s.userId });
	});

	it("401 without, with a malformed or with an unknown token", async () => {
		const s = await signIn();
		const unknown = `v1.${s.userId}.${"A".repeat(43)}`;
		for (const token of [undefined, "garbage", unknown]) {
			const res = await call("/me", { token });
			expect(res.status).toBe(401);
			expect(await errorCode(res)).toBe("unauthenticated");
		}
	});
});

describe("session lifetime (AC10)", () => {
	it("DELETE /session revokes the token at once; other sessions stay valid", async () => {
		const a = await signIn("two-devices");
		const b = await signIn("two-devices");
		expect((await call("/session", { method: "DELETE", token: a.token })).status).toBe(204);
		expect((await call("/me", { token: a.token })).status).toBe(401);
		expect((await call("/session", { method: "DELETE", token: a.token })).status).toBe(401);
		expect((await call("/me", { token: b.token })).status).toBe(200);
	});

	it("a session unused for 30 days returns 401", async () => {
		const s = await signIn();
		vi.useFakeTimers({ now: Date.now() + SESSION_TTL_MS + 1000, toFake: ["Date"] });
		try {
			expect((await call("/me", { token: s.token })).status).toBe(401);
		} finally {
			vi.useRealTimers();
		}
	});

	it("use extends the expiry at most once per day", async () => {
		const s = await signIn();
		const expiry = () => inObject(s.userId, (_i, state) => state.storage.sql.exec<{ e: number }>("SELECT expires_at AS e FROM sessions").one().e);
		const first = await expiry();
		const start = Date.now();
		vi.useFakeTimers({ now: start + DAY_MS / 2, toFake: ["Date"] });
		try {
			await call("/me", { token: s.token });
			expect(await expiry()).toBe(first); // same day: unchanged
			vi.setSystemTime(start + DAY_MS + 60_000);
			await call("/me", { token: s.token });
			expect(await expiry()).toBeGreaterThanOrEqual(start + DAY_MS + SESSION_TTL_MS);
			vi.setSystemTime(start + SESSION_TTL_MS + DAY_MS / 2);
			expect((await call("/me", { token: s.token })).status).toBe(200); // past the first expiry, inside the extended one
		} finally {
			vi.useRealTimers();
		}
	});
});

describe("write limits only after authentication", () => {
	it("a wrong secret with user B's id does not consume B's write limit and counts against the caller's IP", async () => {
		const b = await signIn("limit-victim");
		const forged = `v1.${b.userId}.${"A".repeat(43)}`;
		const write = (token: string, ip?: string) => call(`/recipes/${crypto.randomUUID()}`, { method: "DELETE", token, ip });
		// More failed writes than B's per-user limit (120 per minute), spread over IPs of 20 each.
		for (let n = 0; n < 7; n++) {
			const ip = freshIp();
			const statuses: number[] = [];
			for (let i = 0; i < 22; i++) statuses.push((await write(forged, ip)).status);
			expect(statuses.slice(0, 20).every((s) => s === 401)).toBe(true);
			expect(statuses.at(-1)).toBe(429); // the caller's IP is limited
		}
		expect((await write(b.token)).status).toBe(404); // B's own write still passes the limit (404: id never existed)
	});

	it("the per-user write limit still applies to an authenticated user", async () => {
		const s = await signIn();
		const statuses: number[] = [];
		for (let i = 0; i < 125; i++) statuses.push((await call(`/recipes/${crypto.randomUUID()}`, { method: "DELETE", token: s.token })).status);
		expect(statuses).toContain(429);
		expect((await call("/me", { token: s.token })).status).toBe(200); // reads are not limited
	});
});

describe("POST /session without Content-Length", () => {
	it("411 length_required before the body is read", async () => {
		const body = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(new TextEncoder().encode("{}"));
				controller.close();
			},
		});
		const res = await call("/session", { method: "POST", body });
		expect(res.status).toBe(411);
		expect(await errorCode(res)).toBe("length_required");
	});
});

describe("sessions per user (security review S8)", () => {
	it("an eleventh sign-in revokes the oldest token and keeps the ten newest", async () => {
		const sessions = [];
		for (let i = 0; i < 11; i++) sessions.push(await signIn("many-devices"));
		expect((await call("/me", { token: sessions[0].token })).status).toBe(401);
		for (const s of sessions.slice(1)) expect((await call("/me", { token: s.token })).status).toBe(200);
		const stored = await inObject(sessions[0].userId, (_i, state) => state.storage.sql.exec("SELECT count(*) AS n FROM sessions").one().n);
		expect(stored).toBe(10);
	});
});

describe("refusals create no session", () => {
	const rows = async (sub: string) => {
		const res = await attempt("google", { sub });
		expect(res.status).toBe(200);
		const { userId } = (await res.json()) as { userId: string };
		return inObject(userId, (_i, st) => st.storage.sql.exec("SELECT count(*) AS n FROM sessions").one().n);
	};
	const refusals: [string, (sub: string) => Promise<Response>][] = [
		["absent Turnstile field", async (sub) => {
			const n = await nonce();
			return session({ provider: "google", idToken: await providerToken("google", n, { sub }), nonce: n, ageConfirmed: true });
		}],
		["invalid Turnstile token", (sub) => attempt("google", { sub }, { turnstileToken: "bad" })],
		["invalid nonce", async (sub) => {
			const fake = "n1.9999999999999.AAAA";
			return session({ provider: "google", idToken: await providerToken("google", fake, { sub }), nonce: fake, turnstileToken: TURNSTILE_OK, ageConfirmed: true });
		}],
		["invalid id token", (sub) => attempt("google", { sub, wrongKey: true })],
	];
	for (const [name, refuse] of refusals) {
		it(`${name}: a later valid sign-in for the same subject finds exactly one session`, async () => {
			const sub = `refused-${name.replace(/ /g, "-")}`;
			expect([400, 401]).toContain((await refuse(sub)).status);
			expect(await rows(sub)).toBe(1);
		});
	}
});

describe("contract fixture session_request.json", () => {
	it("passes the request schema: the route goes on to the Turnstile check", async () => {
		const res = await session(requestFixture as Record<string, unknown>);
		expect([res.status, await errorCode(res)]).toEqual([401, "invalid_turnstile"]);
	});
});

