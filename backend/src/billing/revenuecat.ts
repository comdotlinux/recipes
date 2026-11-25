import { z } from "zod";
import { isoTime } from "../recipes";

/**
 * RevenueCat REST API v1, `GET /subscribers/{app_user_id}` with a secret API key (docs/versions/backend.md, "RevenueCat").
 * The subscriber record is the source of truth (plan 4.6); webhooks only trigger a call to this function.
 */
export type Entitlement = { active: boolean; expiresAt: string | null; gracePeriodExpiresAt?: string | null; productId: string | null };

/** Fetches one customer's entitlement; null when RevenueCat cannot be reached or answers something unusable. */
export type EntitlementSource = (userId: string, now: number) => Promise<Entitlement | null>;

export const TIMEOUT_MS = 5_000;
export const NONE: Entitlement = { active: false, expiresAt: null, gracePeriodExpiresAt: null, productId: null };

const date = z.string().datetime({ offset: true }).nullish();
const Subscriber = z.object({
	subscriber: z.object({
		entitlements: z.record(z.string(), z.object({ expires_date: date, grace_period_expires_date: date, product_identifier: z.string().max(200) })),
		subscriptions: z.record(z.string(), z.object({ refunded_at: date })).optional(),
	}),
});

/** The later of the expiry and the grace-period end, in ms; null for an entitlement that never expires. */
export const endOf = (e: Pick<Entitlement, "expiresAt" | "gracePeriodExpiresAt">): number | null =>
	e.expiresAt === null ? null : Math.max(Date.parse(e.expiresAt), e.gracePeriodExpiresAt ? Date.parse(e.gracePeriodExpiresAt) : 0);

const iso = (s: string | null | undefined) => (s ? isoTime(Date.parse(s)) : null);

/** Maps the subscriber record to our entitlement. A refunded subscription is inactive at once, whatever its dates say. */
export function mapSubscriber(body: unknown, entitlementId: string, now: number): Entitlement | null {
	const parsed = Subscriber.safeParse(body);
	if (!parsed.success) return null;
	const e = parsed.data.subscriber.entitlements[entitlementId];
	if (!e) return NONE;
	const mapped: Entitlement = {
		active: false,
		expiresAt: iso(e.expires_date),
		gracePeriodExpiresAt: iso(e.grace_period_expires_date),
		productId: e.product_identifier,
	};
	const refunded = parsed.data.subscriber.subscriptions?.[e.product_identifier]?.refunded_at;
	const end = endOf(mapped);
	mapped.active = !refunded && (end === null || now < end);
	return mapped;
}

/** One try plus one retry on network errors, timeouts, 429 and 5xx. Never logs the key or the response body. */
export const revenueCat =
	(env: Env): EntitlementSource =>
	async (userId, now) => {
		const url = `${env.REVENUECAT_API_BASE}/subscribers/${encodeURIComponent(userId)}`;
		for (let tryNo = 1; tryNo <= 2; tryNo++) {
			let res: Response;
			try {
				res = await fetch(url, { headers: { authorization: `Bearer ${env.REVENUECAT_API_KEY}` }, signal: AbortSignal.timeout(TIMEOUT_MS) });
				if (res.ok) return mapSubscriber(await res.json(), env.REVENUECAT_ENTITLEMENT, now);
				await res.body?.cancel();
			} catch (e) {
				console.error(`revenuecat: try ${tryNo} failed: ${e instanceof Error ? e.name : "non-error value"}`);
				continue;
			}
			console.error(`revenuecat: try ${tryNo} answered ${res.status}`);
			if (res.status !== 429 && res.status < 500) break;
		}
		return null;
	};

/** REST API v2 base; the customer delete needs a v2 secret key and the project id (docs/versions/backend.md, "RevenueCat"). */
export const REVENUECAT_V2_BASE = "https://api.revenuecat.com/v2";

/**
 * Account deletion (plan 4.7): `DELETE /v2/projects/{project_id}/customers/{customer_id}`. True when RevenueCat accepted
 * the deletion or does not know the customer (404 counts as done); false means try again later. Without a v2 key
 * (env.local) there is nothing at RevenueCat to delete.
 */
export async function deleteCustomer(env: { REVENUECAT_V2_API_KEY?: string; REVENUECAT_PROJECT_ID: string }, userId: string): Promise<boolean> {
	if (!env.REVENUECAT_V2_API_KEY) return true;
	if (!env.REVENUECAT_PROJECT_ID) return false;
	const url = `${REVENUECAT_V2_BASE}/projects/${encodeURIComponent(env.REVENUECAT_PROJECT_ID)}/customers/${encodeURIComponent(userId)}`;
	try {
		const res = await fetch(url, { method: "DELETE", headers: { authorization: `Bearer ${env.REVENUECAT_V2_API_KEY}` }, signal: AbortSignal.timeout(TIMEOUT_MS) });
		await res.body?.cancel();
		if (res.ok || res.status === 404) return true;
		console.error(`revenuecat: customer delete answered ${res.status}`);
	} catch (e) {
		console.error(`revenuecat: customer delete failed: ${e instanceof Error ? e.name : "non-error value"}`);
	}
	return false;
}
