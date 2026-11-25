import { createExecutionContext, env } from "cloudflare:test";
import { SignJWT, exportJWK, generateKeyPair } from "jose";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import eventCounts from "../fixtures/metrics/event_counts.json";
import { overview } from "../../src/admin/queries";
import worker from "../../src/index";
import { reportReference } from "../../src/share";
import { call, inObject, interceptOutbound, signIn, userStub, uuid } from "../api/helpers";
import inputFixture from "../../../contract/fixtures/recipe_input.json";

const TEAM = "team-x.cloudflareaccess.com";
const AUD = "aud-tag-1";
const ADMIN = "admin.example";
const ACCOUNT = "0123456789abcdef0123456789abcdef";
const adminEnv = {
	...env,
	ADMIN_HOST: ADMIN,
	ACCESS_TEAM_DOMAIN: TEAM,
	ACCESS_AUD: AUD,
	ADMIN_EMAILS: "Operator@Example.org, second@example.org",
	CF_ACCOUNT_ID: ACCOUNT,
	CF_API_TOKEN_READ: "test-read-token-not-a-secret",
	METRICS_DATASET: "recipes_metrics",
	REVENUECAT_V2_API_KEY: "test-v2-key-not-a-secret",
	REVENUECAT_PROJECT_ID: "proj1",
} as unknown as Env;

const keys = await generateKeyPair("RS256", { extractable: true });
const other = await generateKeyPair("RS256", { extractable: true });
const jwk = { ...(await exportJWK(keys.publicKey)), kid: "a1", alg: "RS256" };

let sqlSeen: string[] = [];
let analytics: (sql: string) => unknown[] | null = () => [];
let subscribers: () => Response = () => Response.json({ object: "overview_metrics", metrics: [{ object: "overview_metric", id: "active_subscriptions", value: 42 }] });

beforeEach(() => {
	sqlSeen = [];
	analytics = (sql) =>
		sql.includes("COUNT(DISTINCT index1) AS n FROM")
			? [{ n: 3 }]
			: sql.includes("GROUP BY event, a, b")
				? eventCounts
				: sql.includes("toStartOfDay")
					? [{ day: "2026-11-24 00:00:00", a: "INITIAL_PURCHASE", n: 2, users: 1 }]
					: sql.includes("GROUP BY id, grp")
						? [{ id: "metrics-id-1", grp: "recipes", n: 40 }]
						: [];
	const spy = interceptOutbound();
	const base = spy.getMockImplementation()!;
	spy.mockImplementation(async (input, init) => {
		const req = new Request(input, init);
		if (req.url === `https://${TEAM}/cdn-cgi/access/certs`) return Response.json({ keys: [jwk] });
		if (req.url === `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/analytics_engine/sql`) {
			expect(req.headers.get("authorization")).toBe("Bearer test-read-token-not-a-secret");
			const sql = await req.text();
			sqlSeen.push(sql);
			const data = analytics(sql);
			return data ? Response.json({ meta: [], data, rows: data.length }) : new Response(null, { status: 500 });
		}
		if (req.url === "https://api.revenuecat.com/v2/projects/proj1/metrics/overview") return subscribers();
		return base(input, init);
	});
});
afterEach(() => vi.restoreAllMocks());

async function accessJwt(o: { aud?: string; iss?: string; email?: string; exp?: number; wrongKey?: boolean } = {}) {
	const now = Math.floor(Date.now() / 1000);
	return new SignJWT({ email: o.email ?? "operator@example.org" })
		.setProtectedHeader({ alg: "RS256", kid: "a1" })
		.setIssuer(o.iss ?? `https://${TEAM}`)
		.setAudience(o.aud ?? AUD)
		.setIssuedAt(now - 10)
		.setExpirationTime(o.exp ?? now + 600)
		.sign(o.wrongKey ? other.privateKey : keys.privateKey);
}

const admin = async (path: string, init: RequestInit & { jwt?: string | null; host?: string } = {}) => {
	const headers = new Headers(init.headers);
	const jwt = init.jwt === undefined ? await accessJwt() : init.jwt;
	if (jwt) headers.set("cf-access-jwt-assertion", jwt);
	const request = new Request(`https://${init.host ?? ADMIN}${path}`, { ...init, headers }) as unknown as Parameters<typeof worker.fetch>[0];
	return worker.fetch(request, adminEnv, createExecutionContext());
};

describe("admin host access (AC41)", () => {
	it("403 for no JWT, bad signature, wrong aud, wrong iss, expired token and an email outside the allow-list", async () => {
		const now = Math.floor(Date.now() / 1000);
		const cases: [string, string | null][] = [
			["no JWT", null],
			["bad signature", await accessJwt({ wrongKey: true })],
			["wrong aud", await accessJwt({ aud: "other" })],
			["wrong iss", await accessJwt({ iss: "https://evil.cloudflareaccess.com" })],
			["expired", await accessJwt({ exp: now - 60 })],
			["foreign email", await accessJwt({ email: "intruder@example.org" })],
		];
		for (const [label, jwt] of cases) expect([label, (await admin("/admin/", { jwt })).status]).toEqual([label, 403]);
		expect((await admin("/admin/")).status).toBe(200);
	});

	it("admin paths on the public host answer 404", async () => {
		for (const path of ["/admin/", "/admin/users", "/admin"]) {
			const res = await admin(path, { host: "recipe.example" });
			expect([path, res.status]).toEqual([path, 404]);
		}
	});

	it("a non-GET request with a foreign or missing Origin answers 403", async () => {
		const form = new URLSearchParams({ id: "A".repeat(43), reason: "none", confirm: "UNSUSPEND" });
		for (const origin of ["https://evil.example", null]) {
			const res = await admin("/admin/user/suspend", { method: "POST", body: form, headers: origin ? { origin } : {} });
			expect([origin, res.status]).toEqual([origin, 403]);
		}
	});

	it("pages carry no script, a strict CSP, HSTS, and no purple or gradient", async () => {
		for (const path of ["/admin/", "/admin/users", "/admin/subscriptions", "/admin/reports"]) {
			const res = await admin(path);
			expect(res.headers.get("content-security-policy")).toBe("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
			expect(res.headers.get("strict-transport-security")).toContain("max-age=");
			const html = await res.text();
			expect(html).not.toMatch(/<script|gradient|purple|violet/i);
		}
	});
});

describe("admin pages (AC42)", () => {
	it("the overview renders the plan 4.12 counters from fixture metrics", async () => {
		const o = overview(eventCounts, 3);
		expect(o).toMatchObject({
			activeUsers: 3,
			newAccounts: 4,
			signInFailures: { invalid_token: 3 },
			importsByOutcome: { done: 9, guard_block: 2 },
			guardBlocks: 2,
			translations: 5,
			pdfs: 7,
			shares: 8,
			reports: 2,
			quotaDenials: 11,
			aiCalls: 22,
			webhookFailures: 7,
			entitlementMismatches: 1,
			deletionsErased: 2,
		});
		const html = await (await admin("/admin/")).text();
		for (const label of ["Active users", "New accounts", "Sign-in failures: invalid_token", "Imports: done", "Guard blocks", "Translations", "PDFs", "Shares", "Reports", "Quota denials", "Webhook failures", "Entitlement mismatches"]) {
			expect(html).toContain(label);
		}
		expect(sqlSeen.every((s) => s.includes("FROM recipes_metrics "))).toBe(true);
		// Active users and request counts come from request events, which never include job polling (metrics.ts).
		expect(sqlSeen.filter((s) => s.includes("COUNT(DISTINCT index1)")).every((s) => s.includes("blob1 = 'request'"))).toBe(true);
	});

	it("shows the RevenueCat active-subscriber figure, and only the link when it is unavailable", async () => {
		expect(await (await admin("/admin/subscriptions")).text()).toContain("<strong>42</strong>");
		subscribers = () => new Response(null, { status: 503 });
		const html = await (await admin("/admin/subscriptions")).text();
		expect(html).toContain("not available from RevenueCat");
		expect(html).toContain('href="https://app.revenuecat.com/"');
		subscribers = () => Response.json({ metrics: [{ id: "active_subscriptions", value: 42 }] });
	});

	it("shows a notice instead of numbers when Analytics Engine cannot be read", async () => {
		analytics = () => null;
		expect(await (await admin("/admin/")).text()).toContain("Analytics Engine could not be read");
	});

	it("users and reports pages render from fixture rows", async () => {
		expect(await (await admin("/admin/users")).text()).toContain("metrics-id-1");
		const ref = await reportReference("B".repeat(43), Date.now(), adminEnv);
		analytics = (sql) => (sql.includes("share_report") ? [{ timestamp: "2026-11-24 10:00:00", event: "share_report", a: "offensive", b: ref }] : []);
		const html = await (await admin("/admin/reports")).text();
		expect(html).toContain(`/admin/user?ref=${encodeURIComponent(ref)}`);
	});

	it("a marker in a recipe title, text and picture never appears in adminSummary or the user page; a report reference opens the account", async () => {
		const s = await signIn();
		const id = uuid();
		const recipe = { ...inputFixture, id, title: "MARKER-TITLE", description: "MARKER-TEXT", steps: ["MARKER-STEP"], imageIds: [] };
		await call(`/recipes/${id}`, { method: "PUT", token: s.token, body: JSON.stringify(recipe), headers: { "content-type": "application/json", "if-none-match": "*" } });
		await call(`/recipes/${id}/images/${uuid()}`, { method: "PUT", token: s.token, body: new Uint8Array([0xff, 0xd8, 0xff, 0xe0, ...new TextEncoder().encode("MARKER-PIC")]), headers: { "content-type": "image/jpeg" } });
		const summary = (await userStub(s.userId).adminSummary()) as unknown as { status: number; body: unknown };
		expect(summary.status).toBe(200);
		expect(summary.body).toMatchObject({ state: "active", recipes: 1, pictures: 1, sessions: 1 });
		const page = await (await admin(`/admin/user?id=${s.userId}`)).text();
		const ref = await reportReference(s.userId, Date.now(), adminEnv);
		const byRef = await (await admin(`/admin/user?ref=${encodeURIComponent(ref)}`)).text();
		for (const text of [JSON.stringify(summary), page, byRef]) expect(text).not.toMatch(/MARKER/);
		expect(byRef).toContain(s.userId);
		const old = await reportReference(s.userId, Date.now() - 31 * 86_400_000, adminEnv);
		expect((await admin(`/admin/user?ref=${encodeURIComponent(old)}`)).status).toBe(404);
	});

	it("suspend needs the typed confirmation, writes the audit row with the operator, and redirects", async () => {
		const s = await signIn();
		const post = (confirm: string, reason = "abusive_content") =>
			admin("/admin/user/suspend", { method: "POST", body: new URLSearchParams({ id: s.userId, reason, confirm }), headers: { origin: `https://${ADMIN}` } });
		expect((await post("yes")).status).toBe(400);
		expect((await post("SUSPEND", "made_up")).status).toBe(400);
		const res = await post("SUSPEND");
		expect(res.status).toBe(303);
		expect([res.headers.get("strict-transport-security"), res.headers.get("x-content-type-options")]).toEqual(["max-age=63072000; includeSubDomains", "nosniff"]);
		expect(res.headers.get("location")).toBe(`/admin/user?id=${encodeURIComponent(s.userId)}`);
		expect((await call("/me/consent", { method: "DELETE", token: s.token })).status).toBe(403);
		expect((await post("UNSUSPEND", "none")).status).toBe(303);
		const audit = await inObject(s.userId, (_i, st) => st.storage.sql.exec("SELECT actor, action FROM audit ORDER BY id").toArray());
		expect(audit).toEqual([
			{ actor: "operator@example.org", action: "suspend" },
			{ actor: "operator@example.org", action: "unsuspend" },
		]);
		expect((await admin(`/admin/user?id=${"Q".repeat(43)}`)).status).toBe(404);
	});
});
