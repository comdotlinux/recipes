/**
 * Analytics Engine events (plan 4.10). Events about a user are written by that user's object with
 * index = its random metrics_id, never the user id. Events without a user are written by the Worker without an index.
 */
export type UserEvent =
	| { event: "account_created" }
	| { event: "session_created" }
	| { event: "request"; group: string; status: number }
	| { event: "import_outcome"; kind: string; outcome: string; durationMs: number }
	| { event: "quota_denied"; reason: string }
	| { event: "report"; kind: string; reason: string }
	// trigger: webhook | client | stale | daily | local; outcome: active | inactive | unavailable | gave_up
	| { event: "entitlement_refresh"; trigger: string; outcome: string };

/** No content, no URLs, no user text: only the event, fixed codes and a duration. */
export function userEvent(env: Env, metricsId: string, e: UserEvent): void {
	const blobs =
		e.event === "request"
			? [e.event, e.group, String(e.status)]
			: e.event === "import_outcome"
				? [e.event, e.kind, e.outcome]
				: e.event === "quota_denied"
					? [e.event, e.reason]
					: e.event === "report"
						? [e.event, e.kind, e.reason]
						: e.event === "entitlement_refresh"
							? [e.event, e.trigger, e.outcome]
							: [e.event];
	env.METRICS.writeDataPoint({ indexes: [metricsId], blobs, doubles: [e.event === "import_outcome" ? e.durationMs : 1] });
}

export type SystemEvent =
	| { event: "auth_failure"; reason: string }
	| { event: "rate_limited"; route: string }
	| { event: "provider_unavailable"; provider: string }
	// type: one of the known RevenueCat event types or "OTHER"; never the user id or anything else from the payload.
	| { event: "billing_webhook"; type: string };

export function systemEvent(env: Env, e: SystemEvent): void {
	const detail = e.event === "auth_failure" ? e.reason : e.event === "rate_limited" ? e.route : e.event === "billing_webhook" ? e.type : e.provider;
	env.METRICS.writeDataPoint({ blobs: [e.event, detail], doubles: [1] });
}
