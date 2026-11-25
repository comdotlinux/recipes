import { describe, expect, test } from "bun:test";
import { AI_GATEWAY_LIMIT } from "../backend/src/limits";
import { dryRun, mergeRules, redact, resources } from "./provision";

const inputs = { account: "acc", zone: "zon", adminEmails: ["op@example.org"] };
const byName = (env: "staging" | "production", name: string) => resources(env, inputs).find((r) => r.name === name)!;

describe("provisioning plan", () => {
  test("creates what is absent", () => {
    const gw = byName("staging", "AI Gateway").decide(null);
    expect(gw).toHaveLength(1);
    expect(gw[0]).toMatchObject({ method: "POST", path: "/accounts/acc/ai-gateway/gateways" });
    expect(gw[0].body).toMatchObject({ id: "recipes", cache_ttl: 0, collect_logs: false, rate_limiting_limit: AI_GATEWAY_LIMIT.requests });
    const guard = (gw[0].body as { guardrails: { prompt: Record<string, string>; response: Record<string, string> } }).guardrails;
    expect(guard.prompt.P1).toBe("BLOCK");
    expect(Object.values(guard.response).every((a) => a === "BLOCK")).toBe(true);
  });

  test("is idempotent: a matching current state plans nothing", () => {
    const gateway = byName("production", "AI Gateway");
    const body = gateway.decide(null)[0].body as Record<string, unknown>;
    expect(gateway.decide(body)).toEqual([]);
    expect(gateway.decide({ ...body, cache_ttl: 60 })[0]).toMatchObject({ method: "PUT" });
    const widget = byName("staging", "turnstile widget");
    expect(widget.decide([{ name: "recipes-staging", sitekey: "0x1", mode: "managed", domains: ["recipe-staging.munich-tech.de"] }])).toEqual([]);
    expect(widget.decide([{ name: "recipes-staging", sitekey: "0x1", mode: "managed", domains: ["evil.example"] }])[0]).toMatchObject({ method: "PUT", path: "/accounts/acc/challenges/widgets/0x1" });
    const sub = byName("production", "workers.dev and preview URLs");
    expect(sub.decide({ enabled: false, previews_enabled: false })).toEqual([]);
    expect(sub.decide({ enabled: true, previews_enabled: true })[0].body).toEqual({ enabled: false, previews_enabled: false });
    expect(byName("staging", "workers.dev and preview URLs").decide(null)[0].body).toEqual({ enabled: false, previews_enabled: true });
  });

  test("stays within the Free plan: one rate rule, IP and colo, 10 s; at most 5 custom rules", () => {
    const rate = byName("production", "rate limiting rule").decide(null)[0].body as { rules: { ratelimit: { characteristics: string[]; period: number; mitigation_timeout: number } }[] };
    expect(rate.rules).toHaveLength(1);
    expect(rate.rules[0].ratelimit).toMatchObject({ characteristics: ["cf.colo.id", "ip.src"], period: 10, mitigation_timeout: 10 });
    const custom = byName("production", "custom WAF rules").decide(null)[0].body as { rules: { expression: string }[] };
    expect(custom.rules.length).toBeLessThanOrEqual(5);
    // Legal pages and the deletion page are never challenged.
    expect(JSON.stringify(custom.rules)).not.toMatch(/privacy|imprint|terms|challenge/);
  });

  test("the Access application exists only for production, with the allow-list and an 8-hour session", () => {
    expect(resources("staging", inputs).some((r) => r.name === "Access application")).toBe(false);
    const app = byName("production", "Access application").decide(null)[0];
    expect(app.body).toMatchObject({ type: "self_hosted", domain: "recipe-admin.munich-tech.de", session_duration: "8h" });
    expect(JSON.stringify(app.body)).toContain("op@example.org");
    expect(byName("production", "Access application").decide([{ id: "x1", domain: "recipe-admin.munich-tech.de" }])[0]).toMatchObject({ method: "PUT", path: "/accounts/acc/access/apps/x1" });
  });

  test("zone-wide rules are the same for both environments, so one run never removes the other's", () => {
    for (const name of ["rate limiting rule", "custom WAF rules"]) {
      expect(byName("staging", name).decide(null)).toEqual(byName("production", name).decide(null));
    }
  });

  test("merges into the zone's rulesets: other sites' rules are kept in place, only ours are replaced", () => {
    const other = { id: "o1", description: "blog: block bots", expression: "true", action: "block" };
    const oldOurs = { id: "m1", description: "recipes: old", expression: "false", action: "block" };
    const ours = [{ description: "recipes: new", expression: "true", action: "block" }];
    const merged = mergeRules({ rules: [other, oldOurs] }, ours);
    expect(merged).toEqual({ rules: [other, ...ours], kept: ["blog: block bots"], changed: true });
    expect(mergeRules({ rules: [other, { ...ours[0], id: "m2" }] }, ours).changed).toBe(false);
    const call = byName("production", "custom WAF rules").decide({ rules: [other] })[0];
    expect((call.body as { rules: unknown[] }).rules[0]).toEqual(other);
    expect(call.why).toContain("blog: block bots");
    expect(byName("production", "custom WAF rules").decide({ rules: [other, ...(byName("production", "custom WAF rules").decide(null)[0].body as { rules: unknown[] }).rules] })).toEqual([]);
  });

  test("the dry run lists every resource as unverified and prints no secret", () => {
    const lines = dryRun("production", inputs);
    expect(lines.filter((l) => l.startsWith("# ")).every((l) => l.endsWith("(UNVERIFIED)"))).toBe(true);
    expect(lines.join("\n")).toContain("custom domain recipe-admin.munich-tech.de");
    expect(redact({ result: { sitekey: "0x1", secret: "s3cr3t", nested: [{ api_token: "t" }] } })).toEqual({ result: { sitekey: "0x1", secret: "[redacted]", nested: [{ api_token: "[redacted]" }] } });
  });
});
