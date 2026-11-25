/**
 * The only place that calls a model (plan 4.5). It refuses without the current AI consent version, routes through
 * AI Gateway and turns off payload logging for every request.
 */

/** Version of the AI notice the app shows; PUT /me/consent must send exactly this. */
export const AI_NOTICE_VERSION = 1;

/** UNVERIFIED: the gateway is created by infra/provision.ts with Guardrails set to Block (spike S2). */
export const GATEWAY_ID = "recipes";

/**
 * Per-request privacy settings; the AI notice relies on both (Workers AI data-usage page excepts gateway logs and cache).
 * - no payload log: https://developers.cloudflare.com/ai-gateway/observability/logging/ (checked 2026-11-24)
 * - no cache: header `cf-aig-skip-cache` (https://developers.cloudflare.com/ai-gateway/features/caching/, page dated
 *   2026-09-30) and the binding's `gateway.skipCache` (https://developers.cloudflare.com/ai-gateway/integrations/worker-binding-methods/).
 *   The docs say skipping bypasses a cached copy; they do not say it prevents storing one, so the gateway's own cache
 *   setting must stay off too (it is off by default; infra/provision.ts keeps it off).
 */
export const GATEWAY_HEADERS = { "cf-aig-collect-log-payload": "false", "cf-aig-skip-cache": "true" } as const;

/**
 * UNVERIFIED (spike S2, needs an account): how a Guardrails block surfaces through the binding. Until S2 records the
 * exact error, any error naming a guardrail or a blocked prompt or response counts as a block.
 */
export const GUARDRAIL_BLOCK = /guardrail|prompt blocked|response blocked|blocked due to security/i;

export class ConsentRequired extends Error {
	constructor() {
		super("AI consent is not recorded");
	}
}
export class GuardrailBlock extends Error {}

export type ModelRunner = Pick<Ai, "run">;

export async function runModel(
	env: { AI?: ModelRunner },
	consent: number | null | (() => number | null),
	feature: "structure" | "transcribe",
	model: string,
	inputs: Record<string, unknown>,
): Promise<unknown> {
	// A reader is evaluated right before the call, so consent withdrawn during the job is honoured.
	if ((typeof consent === "function" ? consent() : consent) !== AI_NOTICE_VERSION) throw new ConsentRequired();
	if (!env.AI) throw new Error("AI binding is not configured in this environment");
	try {
		return await (env.AI.run as (m: string, i: unknown, o: unknown) => Promise<unknown>)(model, inputs, {
			gateway: { id: GATEWAY_ID, skipCache: true, metadata: { feature } },
			extraHeaders: GATEWAY_HEADERS,
		});
	} catch (e) {
		if (GUARDRAIL_BLOCK.test(String(e))) throw new GuardrailBlock(String(e));
		throw e;
	}
}

/** The model's answer as an object: JSON Mode returns an object, other models a JSON string (maybe fenced). */
export function answerObject(result: unknown): unknown {
	const response = (result as { response?: unknown } | null)?.response;
	if (response && typeof response === "object") return response;
	if (typeof response !== "string") return null;
	const start = response.indexOf("{");
	const end = response.lastIndexOf("}");
	if (start < 0 || end < start) return null;
	try {
		return JSON.parse(response.slice(start, end + 1));
	} catch {
		return null;
	}
}
