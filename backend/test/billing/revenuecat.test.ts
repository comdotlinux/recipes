import { env } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import subscriber from "../fixtures/revenuecat/subscriber.json";
import { NONE, endOf, mapSubscriber, revenueCat } from "../../src/billing/revenuecat";

const NOW = Date.parse("2026-11-25T12:00:00Z");
type Overrides = { expires?: string | null; grace?: string | null; refunded?: string | null; entitlements?: Record<string, unknown> };
/** The documented subscriber record (fixture) with the dates a test needs. */
const record = (o: Overrides = {}) => {
	const r = structuredClone(subscriber) as unknown as {
		subscriber: { entitlements: Record<string, Record<string, unknown>>; subscriptions: Record<string, Record<string, unknown>> };
	};
	const e = r.subscriber.entitlements.recipes_pro;
	if (o.expires !== undefined) e.expires_date = o.expires;
	if (o.grace !== undefined) e.grace_period_expires_date = o.grace;
	if (o.refunded !== undefined) r.subscriber.subscriptions.monthly.refunded_at = o.refunded;
	if (o.entitlements) r.subscriber.entitlements = o.entitlements as never;
	return r;
};

describe("mapping the subscriber record (plan 4.6)", () => {
	it("an entitlement whose expiry is ahead is active, with product and dates", () => {
		expect(mapSubscriber(record(), "recipes_pro", NOW)).toEqual({
			active: true,
			expiresAt: "2026-12-25T10:00:00Z",
			gracePeriodExpiresAt: null,
			productId: "monthly",
		});
	});

	it("an expired entitlement is inactive; a grace period ahead keeps it active and extends the end", () => {
		expect(mapSubscriber(record({ expires: "2026-11-25T11:00:00Z" }), "recipes_pro", NOW)?.active).toBe(false);
		const grace = mapSubscriber(record({ expires: "2026-11-25T11:00:00Z", grace: "2026-12-01T11:00:00Z" }), "recipes_pro", NOW)!;
		expect(grace.active).toBe(true);
		expect(endOf(grace)).toBe(Date.parse("2026-12-01T11:00:00Z"));
	});

	it("a refunded subscription is inactive at once, even before its expiry", () => {
		expect(mapSubscriber(record({ refunded: "2026-11-25T11:30:00Z" }), "recipes_pro", NOW)?.active).toBe(false);
	});

	it("a sandbox purchase counts as a subscription in every environment (on purpose: docs/versions/backend.md, RevenueCat)", () => {
		const sandbox = record();
		(sandbox.subscriber.subscriptions.monthly as Record<string, unknown>).is_sandbox = true;
		expect(mapSubscriber(sandbox, "recipes_pro", NOW)?.active).toBe(true);
	});

	it("no recipes_pro entitlement means none; a non-expiring one is active", () => {
		expect(mapSubscriber(record({ entitlements: {} }), "recipes_pro", NOW)).toEqual(NONE);
		expect(mapSubscriber(record({ expires: null }), "recipes_pro", NOW)?.active).toBe(true);
	});

	it("an unusable answer is null, not an inactive entitlement", () => {
		expect(mapSubscriber({ subscriber: { entitlements: { recipes_pro: { expires_date: "soon" } } } }, "recipes_pro", NOW)).toBeNull();
		expect(mapSubscriber("nonsense", "recipes_pro", NOW)).toBeNull();
	});
});

describe("REST client", () => {
	afterEach(() => vi.restoreAllMocks());
	const KEY = "test-revenuecat-key-not-a-secret";
	const USER = "u".repeat(43);

	it("calls GET /v1/subscribers/{id} with the secret key as bearer token", async () => {
		const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => Response.json(record()));
		expect((await revenueCat(env)(USER, NOW))?.active).toBe(true);
		const [input, init] = fetch.mock.calls[0];
		expect(String(input)).toBe(`https://api.revenuecat.com/v1/subscribers/${USER}`);
		expect(new Headers(init?.headers).get("authorization")).toBe(`Bearer ${KEY}`);
		expect(init?.signal).toBeInstanceOf(AbortSignal);
	});

	it("retries once after a 5xx, a 429 or a network error, then gives up with null", async () => {
		const answers = [() => new Response("down", { status: 503 }), () => Response.json(record())];
		const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => answers.shift()!());
		expect((await revenueCat(env)(USER, NOW))?.active).toBe(true);
		expect(fetch).toHaveBeenCalledTimes(2);

		fetch.mockReset().mockImplementation(async () => new Response(null, { status: 429 }));
		expect(await revenueCat(env)(USER, NOW)).toBeNull();
		expect(fetch).toHaveBeenCalledTimes(2);

		fetch.mockReset().mockImplementation(async () => {
			throw new DOMException("timed out", "TimeoutError");
		});
		expect(await revenueCat(env)(USER, NOW)).toBeNull();
		expect(fetch).toHaveBeenCalledTimes(2);
	});

	it("does not retry a 4xx; never logs the key or the response body", async () => {
		const log = vi.spyOn(console, "error").mockImplementation(() => {});
		const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(`secret body ${KEY}`, { status: 401 }));
		expect(await revenueCat(env)(USER, NOW)).toBeNull();
		expect(fetch).toHaveBeenCalledTimes(1);
		fetch.mockReset().mockImplementation(async () => new Response("{ not json", { status: 200 }));
		expect(await revenueCat(env)(USER, NOW)).toBeNull();
		const logged = JSON.stringify(log.mock.calls);
		expect(logged).toContain("401");
		expect(logged).not.toContain(KEY);
		expect(logged).not.toContain("secret body");
		expect(logged).not.toContain("not json");
	});
});
