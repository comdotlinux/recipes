// Own file: the provider key cache is per isolate, and this file needs it empty.
import { env } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TURNSTILE_OK, call, json, nonce, providerToken } from "./helpers";

afterEach(() => vi.restoreAllMocks());

describe("provider keys unavailable (security review S2)", () => {
	it("503 provider_unavailable, not counted as an auth failure", async () => {
		const real = globalThis.fetch;
		vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
			const url = new Request(input, init).url;
			if (url.includes("siteverify")) return Response.json({ success: true });
			if (url === "https://www.googleapis.com/oauth2/v3/certs") return new Response("down", { status: 502 });
			return real(input, init);
		});
		const write = vi.spyOn(env.METRICS, "writeDataPoint");
		const n = await nonce();
		const res = await call("/session", {
			method: "POST",
			...json({ provider: "google", idToken: await providerToken("google", n), nonce: n, turnstileToken: TURNSTILE_OK, ageConfirmed: true }),
		});
		expect(res.status).toBe(503);
		expect(((await res.json()) as { error: { code: string } }).error.code).toBe("provider_unavailable");
		const events = write.mock.calls.map((c) => c[0]?.blobs);
		expect(events.map((b) => b?.[0])).not.toContain("auth_failure");
		expect(events).toContainEqual(["provider_unavailable", "google"]);
	});
});
