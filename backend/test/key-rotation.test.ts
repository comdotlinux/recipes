import { describe, expect, it } from "vitest";
import { issueNonce, newSessionToken, parseSessionToken, verifyNonce } from "../src/auth/index";
import { REPORT_REFERENCE_TTL_MS, SHARE_TTL_MS, newShareId, newShareToken, openReportReference, openShareToken, reportReference } from "../src/share";
import { SESSION_TTL_MS } from "../src/user-do";

// AC34 and docs/runbooks/key-rotation.md: after a rotation SERVER_KEY is new and SERVER_KEY_PREVIOUS holds the old key.
const before = { SERVER_KEY: "old-key-not-a-secret" };
const after = { SERVER_KEY: "new-key-not-a-secret", SERVER_KEY_PREVIOUS: "old-key-not-a-secret" };
const dropped = { SERVER_KEY: "new-key-not-a-secret" };
const USER = "A".repeat(43);
const now = Date.now();

describe("SERVER_KEY rotation (AC34)", () => {
	it("nonces issued under the old key stay valid for their 5 minutes", async () => {
		const nonce = await issueNonce(before, now);
		expect(await verifyNonce(nonce, after, now + 4 * 60_000)).not.toBeNull();
		expect(await verifyNonce(nonce, after, now + 5 * 60_000 + 1)).toBeNull();
		expect(await verifyNonce(nonce, dropped, now)).toBeNull();
		expect(await verifyNonce(await issueNonce(after, now), after, now)).not.toBeNull();
	});

	it("session tokens issued under the old key stay valid while SERVER_KEY_PREVIOUS is set", async () => {
		const { token, secretHash } = await newSessionToken(USER, before);
		expect(await parseSessionToken(token, after)).toEqual({ userId: USER, secretHash });
		expect(await parseSessionToken(token, dropped)).toBeNull();
		// The object enforces the 30-day lifetime; the runbook keeps the previous key at least that long (60 days).
		expect(SESSION_TTL_MS).toBeLessThanOrEqual(60 * 86_400_000);
	});

	it("share tokens sealed with the old key open for their 30 days", async () => {
		const shareId = newShareId();
		const token = await newShareToken(USER, shareId, before);
		expect(await openShareToken(token, after)).toEqual({ ownerId: USER, shareId });
		expect(await openShareToken(token, dropped)).toBeNull();
		expect(SHARE_TTL_MS).toBeLessThanOrEqual(60 * 86_400_000);
	});

	it("report references made under the old key open for 30 days, and never after", async () => {
		const ref = await reportReference(USER, now, before);
		expect(await openReportReference(ref, after, now + REPORT_REFERENCE_TTL_MS - 1)).toBe(USER);
		expect(await openReportReference(ref, after, now + REPORT_REFERENCE_TTL_MS + 1)).toBeNull();
		expect(await openReportReference(ref, dropped, now)).toBeNull();
	});
});
