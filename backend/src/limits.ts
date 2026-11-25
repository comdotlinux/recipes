/**
 * The AI Gateway rate limit, the global cost ceiling (plan 4.5). infra/provision.ts sets it on the gateway; the cron
 * alert (cron.ts) and the admin overview compare usage with it. One place, so the three cannot drift apart.
 */
export const AI_GATEWAY_LIMIT = { requests: 600, periodSeconds: 3600, technique: "sliding" } as const;
