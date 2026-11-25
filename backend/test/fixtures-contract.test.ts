import { env } from "cloudflare:test";
import { expect, it } from "vitest";
import gradleProperties from "../../gradle.properties?raw";
import { openShareToken } from "../src/share";

const fixtures = import.meta.glob<string>("../../contract/fixtures/*.json", { query: "?raw", import: "default", eager: true });

/** The app's API host as configured for the client build (gradle.properties API_BASE_URL). */
const appHost = new URL(/^API_BASE_URL=(.+)$/m.exec(gradleProperties)![1].trim()).hostname;
/** Our host, plus the reserved example domains (RFC 2606) that stand for third-party recipe pages. */
const ALLOWED_HOSTS = [appHost, "example.org", "example.com", "example.net"];

it("every share token in the fixtures has the real token shape and opens under the fixture key", async () => {
	// Share fixtures only: session_response.json also has a "token", which is a session token.
	const shareFixtures = Object.entries(fixtures).filter(([file]) => /share/.test(file)).map(([, text]) => text);
	const tokens = shareFixtures.flatMap((text) => [...text.matchAll(/"(?:token|shareToken)": "([^"]*)"|\/s#([^"]*)"/g)].map((m) => m[1] ?? m[2]));
	expect(tokens.length).toBeGreaterThanOrEqual(4);
	for (const token of tokens) {
		expect(token).toMatch(/^[A-Za-z0-9_-]{107}$/);
		// Generated with src/share.ts newShareToken under this fixed, public key.
		expect(await openShareToken(token, { SERVER_KEY: "fixture-key-not-a-secret" })).not.toBeNull();
		expect(await openShareToken(token, env)).toBeNull();
	}
});

it("every URL in the fixtures uses the app host or a reserved example host", () => {
	expect(appHost).toBe("recipe.munich-tech.de");
	const urls = Object.values(fixtures).flatMap((text) => [...text.matchAll(/https?:\/\/[^"\s]+/g)].map((m) => m[0]));
	expect(urls.length).toBeGreaterThan(0);
	for (const url of urls) expect([url, ALLOWED_HOSTS.includes(new URL(url).hostname)]).toEqual([url, true]);
});
