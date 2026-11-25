import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { systemEvent, userEvent } from "../../src/metrics";
import { call, inObject, interceptOutbound, signIn } from "./helpers";

beforeEach(() => {
	interceptOutbound();
});
afterEach(() => vi.restoreAllMocks());

describe("metrics (plan 4.10)", () => {
	it("user events carry the random metrics_id as index, never the user id", async () => {
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		const s = await signIn();
		await call("/me", { token: s.token });
		const metricsId = await inObject(s.userId, (_i, state) => state.storage.sql.exec<{ v: string }>("SELECT value AS v FROM meta WHERE key = 'metrics_id'").one().v);
		expect(metricsId).toMatch(/^[0-9a-f-]{36}$/);
		const points = write.mock.calls.map((c) => c[0]!);
		const mine = points.filter((p) => p.indexes?.[0] === metricsId).map((p) => p.blobs);
		expect(mine).toEqual([["account_created"], ["session_created"], ["request", "me", "200"]]);
		expect(JSON.stringify(points)).not.toContain(s.userId);
	});

	it("counts requests per route group per day in the object", async () => {
		const s = await signIn();
		await call("/me", { token: s.token });
		await call("/recipes", { token: s.token });
		await call("/recipes", { token: s.token });
		const rows = await inObject(s.userId, (_i, state) => state.storage.sql.exec("SELECT key, count FROM daily_counters ORDER BY key").toArray());
		expect(rows).toEqual([
			{ key: "me", count: 1 },
			{ key: "recipes", count: 2 },
		]);
	});

	it("system events have no index", () => {
		const fake = { METRICS: { writeDataPoint: vi.fn() } } as unknown as Env;
		systemEvent(fake, { event: "auth_failure", reason: "invalid_nonce" });
		systemEvent(fake, { event: "rate_limited", route: "nonce" });
		userEvent(fake, "m", { event: "session_created" });
		expect((fake.METRICS.writeDataPoint as ReturnType<typeof vi.fn>).mock.calls).toEqual([
			[{ blobs: ["auth_failure", "invalid_nonce"], doubles: [1] }],
			[{ blobs: ["rate_limited", "nonce"], doubles: [1] }],
			[{ indexes: ["m"], blobs: ["session_created"], doubles: [1] }],
		]);
	});
});
