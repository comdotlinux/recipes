// Local dev and test entry only (wrangler env "local"). The production entry never imports src/testing/.
import type { ModelRunner } from "./ai/gateway";
import { createApi } from "./api";
import type { EntitlementSource } from "./billing/revenuecat";
import type { Result } from "./http";
import { localAi } from "./testing/local-ai";
import { LOCAL_ENTITLEMENT_KEY, LOCAL_ENTITLEMENT_PATH, localEntitlement, localEntitlementRoute } from "./testing/local-billing";
import { verifyTestIdToken } from "./testing/test-idp";
import { UserDO as ProductionUserDO, entitled } from "./user-do";

/**
 * Same object, with the deterministic AI stand-in when the environment has no AI binding (env.local has none),
 * and the local subscription switch when it has no RevenueCat key (env.local has none).
 */
export class UserDO extends ProductionUserDO {
	protected override models(): { AI?: ModelRunner } {
		return { AI: this.env.AI ?? localAi };
	}

	protected override entitlementSource(): EntitlementSource | null {
		return super.entitlementSource() ?? (async (_userId, now) => localEntitlement(this.meta(LOCAL_ENTITLEMENT_KEY) === "true", now));
	}

	setLocalEntitlement(secretHash: string, active: boolean): Promise<Result> {
		return this.run(
			"billing",
			secretHash,
			async (now) => {
				this.setMeta(LOCAL_ENTITLEMENT_KEY, String(active));
				const fresh = await this.refreshEntitlement(now, "local");
				return { status: 200, body: this.billingView(fresh !== null && entitled(fresh, now, true), now) };
			},
			true,
		);
	}
}

const api = createApi({ testProvider: verifyTestIdToken });

// Should this entry ever be deployed by mistake, it answers nothing outside the machine or the Android emulator's host alias.
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "10.0.2.2"]);

export default {
	fetch(request, env, ctx) {
		const url = new URL(request.url);
		if (!LOCAL_HOSTS.has(url.hostname)) {
			return Response.json({ error: { code: "not_found", message: "local entry serves local hosts only" } }, { status: 404 });
		}
		if (url.pathname === LOCAL_ENTITLEMENT_PATH && request.method === "POST") return localEntitlementRoute(request, env);
		return api.fetch(request, env, ctx);
	},
} satisfies ExportedHandler<Env>;
