/**
 * Analytics Engine events (plan 4.10). Events about a user are written by that user's object with
 * index = its random metrics_id, never the user id. Events without a user are written by the Worker without an index.
 */
export type UserEvent = { event: "account_created" } | { event: "session_created" } | { event: "request"; group: string; status: number };

export function userEvent(env: Env, metricsId: string, e: UserEvent): void {
	const blobs = e.event === "request" ? [e.event, e.group, String(e.status)] : [e.event];
	env.METRICS.writeDataPoint({ indexes: [metricsId], blobs, doubles: [1] });
}

export type SystemEvent =
	| { event: "auth_failure"; reason: string }
	| { event: "rate_limited"; route: string }
	| { event: "provider_unavailable"; provider: string };

export function systemEvent(env: Env, e: SystemEvent): void {
	const detail = e.event === "auth_failure" ? e.reason : e.event === "rate_limited" ? e.route : e.provider;
	env.METRICS.writeDataPoint({ blobs: [e.event, detail], doubles: [1] });
}
