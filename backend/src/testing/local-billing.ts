import { z } from "zod";
import { parseSessionToken } from "../auth/index";
import { type Entitlement, NONE } from "../billing/revenuecat";
import { type Result, error, toResponse } from "../http";
import { isoTime } from "../recipes";

// Marker that scripts/check-bundle.ts rejects: this stand-in must never reach the production bundle.
export const LOCAL_BILLING_MARKER = "__LOCAL_BILLING_STUB__";

/** Meta key in the user's object that holds the switch; read only by the local entry's UserDO. */
export const LOCAL_ENTITLEMENT_KEY = "local_entitlement";
export const LOCAL_ENTITLEMENT_PATH = "/v1/testing/entitlement";

/** What the stand-in "RevenueCat" answers: a monthly subscription running 30 days from the switch, or nothing. */
export const localEntitlement = (active: boolean, now: number): Entitlement =>
	active ? { active: true, expiresAt: isoTime(now + 30 * 86_400_000), gracePeriodExpiresAt: null, productId: `monthly ${LOCAL_BILLING_MARKER}` } : NONE;

type LocalObject = { setLocalEntitlement(secretHash: string, active: boolean): Promise<Result> };

/**
 * `POST /v1/testing/entitlement` with the session token and `{ "active": true | false }`: switches the signed-in
 * user's subscription without RevenueCat. Wired only in src/index.local.ts.
 */
export async function localEntitlementRoute(request: Request, env: Env): Promise<Response> {
	const header = request.headers.get("authorization");
	const auth = header?.startsWith("Bearer ") ? await parseSessionToken(header.slice(7), env) : null;
	if (!auth) return toResponse(error(401, "unauthenticated", "missing, malformed or forged token"));
	if (!/^\d{1,3}$/.test(request.headers.get("content-length") ?? "")) return toResponse(error(411, "length_required", "send Content-Length, at most 999 bytes"));
	const body = z.strictObject({ active: z.boolean() }).safeParse(await request.json().catch(() => null));
	if (!body.success) return toResponse(error(400, "invalid_body", "send { active: boolean }"));
	const stub = env.USER_DO.get(env.USER_DO.idFromName(auth.userId)) as unknown as LocalObject;
	return toResponse(await stub.setLocalEntitlement(auth.secretHash, body.data.active));
}
