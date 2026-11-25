// Idempotent provisioning of what the Worker relies on, per environment (plan 4.8, 4.12, v12; OWNER-TODO A2).
// Run from the repo root with bun:
//   bun run infra/provision.ts --env staging --dry-run        prints the planned calls, needs nothing
//   CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=... CLOUDFLARE_ZONE_ID=... ADMIN_EMAILS=a@b.c \
//     bun run infra/provision.ts --env production             reads the current state, then creates or updates
//
// Every call below follows Cloudflare's API reference as read on 2026-11-25 (docs/versions/backend.md,
// "Provisioning"). None has run against a real account yet: ALL ARE UNVERIFIED until the owner runs this once.
// The script never prints secret values: responses are not logged, and a Turnstile secret returned on creation is
// replaced by a notice to copy it from the dashboard.
import { AI_GATEWAY_LIMIT } from "../backend/src/limits";

export type Env = "staging" | "production";
export type Call = { method: "GET" | "POST" | "PUT" | "PATCH"; path: string; body?: unknown; why: string };
export type Resource = {
  name: string;
  /** GET that returns the current state; null when the resource has no lookup (an upsert). */
  lookup: Call | null;
  /** The calls needed given the lookup's `result` (null = absent or not looked up). Empty when it already matches. */
  decide: (current: unknown) => Call[];
};

export const ZONE = "munich-tech.de";
export const HOSTS: Record<Env, { app: string; admin: string | null; worker: string }> = {
  staging: { app: "recipe-staging.munich-tech.de", admin: null, worker: "recipes-staging" },
  production: { app: "recipe.munich-tech.de", admin: "recipe-admin.munich-tech.de", worker: "recipes-production" },
};
export const GATEWAY_ID = "recipes";
const HAZARDS = Array.from({ length: 14 }, (_, i) => `S${i + 1}`);
const block = (keys: string[]) => Object.fromEntries(keys.map((k) => [k, "BLOCK"]));

type Inputs = { account: string; zone: string; adminEmails: string[] };

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
const pick = (o: unknown, keys: string[]) => Object.fromEntries(keys.map((k) => [k, (o as Record<string, unknown> | null)?.[k]]));

export function resources(env: Env, i: Inputs): Resource[] {
  const h = HOSTS[env];
  const acct = `/accounts/${i.account}`;
  const zone = `/zones/${i.zone}`;
  const out: Resource[] = [];

  // Turnstile widget: the domain list is the control for the unchecked siteverify hostname (provisioning requirements).
  const widget = { name: `recipes-${env}`, mode: "managed", domains: [h.app] };
  out.push({
    name: "turnstile widget",
    lookup: { method: "GET", path: `${acct}/challenges/widgets?filter=name:${widget.name}`, why: "find the widget by name" },
    decide: (current) => {
      const found = (current as { name: string; sitekey: string; mode: string; domains: string[] }[] | null)?.find((w) => w.name === widget.name);
      if (!found) return [{ method: "POST", path: `${acct}/challenges/widgets`, body: widget, why: "create; then set TURNSTILE_SITE_KEY and `wrangler secret put TURNSTILE_SECRET` from the dashboard" }];
      return same(pick(found, ["mode", "domains"]), pick(widget, ["mode", "domains"])) ? [] : [{ method: "PUT", path: `${acct}/challenges/widgets/${found.sitekey}`, body: widget, why: "domains or mode differ" }];
    },
  });

  // AI Gateway: no cache, no logs (so no payloads), Guardrails blocking on prompt and response, the cost ceiling.
  const gateway = {
    id: GATEWAY_ID,
    cache_ttl: 0,
    cache_invalidate_on_update: true,
    collect_logs: false,
    rate_limiting_limit: AI_GATEWAY_LIMIT.requests,
    rate_limiting_interval: AI_GATEWAY_LIMIT.periodSeconds,
    rate_limiting_technique: AI_GATEWAY_LIMIT.technique,
    guardrails: { prompt: block(["P1", ...HAZARDS]), response: block(HAZARDS) },
  };
  out.push({
    name: "AI Gateway",
    lookup: { method: "GET", path: `${acct}/ai-gateway/gateways/${GATEWAY_ID}`, why: "read the gateway (404 = absent)" },
    decide: (current) => {
      if (!current) return [{ method: "POST", path: `${acct}/ai-gateway/gateways`, body: gateway, why: "create" }];
      const keys = Object.keys(gateway).filter((k) => k !== "id");
      const { id: _id, ...update } = gateway;
      return same(pick(current, keys), pick(gateway, keys)) ? [] : [{ method: "PUT", path: `${acct}/ai-gateway/gateways/${GATEWAY_ID}`, body: update, why: "settings differ (full replace)" }];
    },
  });

  // Free plan: one rate-limiting rule, IP + colo only, 10 s period and timeout, path expressions only.
  const rateRule = {
    description: "recipes: sign-in and web deletion per IP",
    expression: '(http.request.uri.path wildcard "/v1/session*") or (http.request.uri.path eq "/v1/account/delete-web")',
    action: "block",
    ratelimit: { characteristics: ["cf.colo.id", "ip.src"], period: 10, requests_per_period: 20, mitigation_timeout: 10 },
  };
  out.push(zoneRules("rate limiting rule", `${zone}/rulesets/phases/http_ratelimit/entrypoint`, [rateRule]));

  // Free plan: up to 5 custom rules. The legal pages, /account/delete and /s are never challenged (AC29).
  const customRules = [
    {
      description: "recipes: only the API's methods under /v1",
      expression: '(starts_with(http.request.uri.path, "/v1/") and not http.request.method in {"GET" "HEAD" "POST" "PUT" "DELETE" "OPTIONS"})',
      action: "block",
    },
    {
      // Zone-wide, the same for both environments: running the script for one must not drop the other's rule.
      description: "recipes: no admin paths on the app hosts",
      expression: `(http.host in {"${HOSTS.production.app}" "${HOSTS.staging.app}"} and starts_with(http.request.uri.path, "/admin"))`,
      action: "block",
    },
  ];
  out.push(zoneRules("custom WAF rules", `${zone}/rulesets/phases/http_request_firewall_custom/entrypoint`, customRules));

  // Free Managed Ruleset: deployed by default on Free; checked, and added only if its execute rule is missing.
  out.push({
    name: "Free Managed Ruleset",
    lookup: { method: "GET", path: `${zone}/rulesets`, why: "find the managed ruleset id and the managed entrypoint" },
    decide: (current) => {
      const managed = (current as { id: string; kind: string; name: string }[] | null)?.find((r) => r.kind === "managed" && r.name === "Cloudflare Free Managed Ruleset");
      if (!managed) return [{ method: "GET", path: `${zone}/rulesets/phases/http_request_firewall_managed/entrypoint`, why: "REPORT: the Free Managed Ruleset is not listed; check it in the dashboard" }];
      return [{ method: "GET", path: `${zone}/rulesets/phases/http_request_firewall_managed/entrypoint`, why: `check that an execute rule for ${managed.id} exists; add {action:"execute", action_parameters:{id}, expression:"true"} only if missing` }];
    },
  });

  // Zone settings available on Free.
  const settings: [string, unknown][] = [
    ["always_use_https", "on"],
    ["min_tls_version", "1.2"],
    ["security_header", { strict_transport_security: { enabled: true, max_age: 63072000, include_subdomains: true, preload: false, nosniff: true } }],
  ];
  for (const [id, value] of settings) {
    out.push({
      name: `zone setting ${id}`,
      lookup: { method: "GET", path: `${zone}/settings/${id}`, why: `read ${id}` },
      decide: (current) => (same((current as { value?: unknown } | null)?.value, value) ? [] : [{ method: "PATCH", path: `${zone}/settings/${id}`, body: { value }, why: `set ${id}` }]),
    });
  }

  // Custom domains (DNS record and certificate included) and no workers.dev; preview URLs only on staging (plan v12).
  for (const hostname of [h.app, ...(h.admin ? [h.admin] : [])]) {
    out.push({
      name: `custom domain ${hostname}`,
      lookup: { method: "GET", path: `${acct}/workers/domains?hostname=${hostname}`, why: "find the domain" },
      decide: (current) =>
        (current as { hostname: string; service: string }[] | null)?.some((d) => d.hostname === hostname && d.service === h.worker)
          ? []
          : [{ method: "PUT", path: `${acct}/workers/domains`, body: { hostname, service: h.worker, zone_id: i.zone }, why: "attach (upsert)" }],
    });
  }
  const subdomain = { enabled: false, previews_enabled: env === "staging" };
  out.push({
    name: "workers.dev and preview URLs",
    lookup: { method: "GET", path: `${acct}/workers/scripts/${h.worker}/subdomain`, why: "read workers.dev state" },
    decide: (current) => (same(pick(current, ["enabled", "previews_enabled"]), subdomain) ? [] : [{ method: "POST", path: `${acct}/workers/scripts/${h.worker}/subdomain`, body: subdomain, why: "workers.dev off" }]),
  });

  // Access application for the admin host (production only): allow-list of operator emails, 8-hour session.
  if (h.admin) {
    const app = {
      type: "self_hosted",
      name: "Recipes admin",
      domain: h.admin,
      session_duration: "8h",
      policies: [{ name: "operators", decision: "allow", include: i.adminEmails.map((email) => ({ email: { email } })), precedence: 1 }],
    };
    out.push({
      name: "Access application",
      lookup: { method: "GET", path: `${acct}/access/apps?domain=${h.admin}&exact=true`, why: "find the application" },
      decide: (current) => {
        const found = (current as { id: string; domain: string }[] | null)?.find((a) => a.domain === h.admin);
        return [
          found
            ? { method: "PUT", path: `${acct}/access/apps/${found.id}`, body: app, why: "keep domain, session and allow-list current; then set ACCESS_AUD from result.aud" }
            : { method: "POST", path: `${acct}/access/apps`, body: app, why: "create; then set ACCESS_AUD from result.aud and ACCESS_TEAM_DOMAIN from /access/organizations" },
        ];
      },
    });
    out.push({ name: "Access team domain", lookup: { method: "GET", path: `${acct}/access/organizations`, why: "auth_domain is ACCESS_TEAM_DOMAIN" }, decide: () => [] });
  }
  return out;
}

/** Rules of this script carry this prefix in their description; every other rule of the zone belongs to other sites. */
export const RULE_PREFIX = "recipes:";

/**
 * A zone phase entrypoint shared with the zone's other sites: only rules whose description starts with RULE_PREFIX are
 * replaced; every other rule is kept in its place and order, and ours follow them (rate-limiting rules must come last
 * anyway). The planned call names the kept rules, so a run shows what it leaves alone.
 */
export function mergeRules(current: unknown, ours: { description: string }[]): { rules: unknown[]; kept: string[]; changed: boolean } {
  const existing = ((current as { rules?: { description?: string }[] } | null)?.rules ?? []) as { description?: string }[];
  const others = existing.filter((r) => !(r.description ?? "").startsWith(RULE_PREFIX));
  const fields = ["description", "expression", "action", "ratelimit"];
  const mine = existing.filter((r) => (r.description ?? "").startsWith(RULE_PREFIX)).map((r) => pick(r, fields));
  const changed = !same(mine, ours.map((r) => pick(r, fields)));
  // Other sites' rule objects are sent back exactly as read, so the PUT does not change them.
  return { rules: [...others, ...ours], kept: others.map((r) => r.description ?? "(no description)"), changed };
}

function zoneRules(name: string, path: string, rules: { description: string; expression: string; action: string }[]): Resource {
  return {
    name,
    lookup: { method: "GET", path, why: `read ${name}` },
    decide: (current) => {
      const merged = mergeRules(current, rules);
      if (!merged.changed) return [];
      const kept = merged.kept.length ? `keeps ${merged.kept.length} rule(s) of other sites: ${merged.kept.join("; ")}` : "keeps every rule of other sites (none seen yet)";
      return [{ method: "PUT", path, body: { rules: merged.rules }, why: `replace only the "${RULE_PREFIX}" rules; ${kept}` }];
    },
  };
}

/** What a dry run prints: every lookup, then the calls planned as if nothing existed yet. */
export function dryRun(env: Env, i: Inputs): string[] {
  return resources(env, i).flatMap((r) => [
    `# ${r.name} (UNVERIFIED)`,
    ...(r.lookup ? [`  ${r.lookup.method} ${r.lookup.path}    (${r.lookup.why})`] : []),
    ...r.decide(null).map((c) => `  ${c.method} ${c.path} ${c.body ? JSON.stringify(c.body) : ""}    (${c.why}, if absent)`),
  ]);
}

/** Strips anything secret from an API result before it is used or shown. */
export function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, /secret|token/i.test(k) ? "[redacted]" : redact(v)]));
  return value;
}

async function api(token: string, c: Call): Promise<{ ok: boolean; status: number; result: unknown }> {
  const res = await fetch(`https://api.cloudflare.com/client/v4${c.path}`, {
    method: c.method,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: c.body === undefined ? undefined : JSON.stringify(c.body),
  });
  const body = (await res.json().catch(() => ({}))) as { result?: unknown };
  return { ok: res.ok, status: res.status, result: redact(body.result ?? null) };
}

async function main(argv: string[]) {
  const env = argv[argv.indexOf("--env") + 1] as Env;
  if (env !== "staging" && env !== "production") throw new Error("--env staging|production");
  const inputs = {
    account: process.env.CLOUDFLARE_ACCOUNT_ID ?? "<account_id>",
    zone: process.env.CLOUDFLARE_ZONE_ID ?? "<zone_id>",
    adminEmails: (process.env.ADMIN_EMAILS ?? "<operator email>").split(",").map((e) => e.trim()).filter(Boolean),
  };
  if (argv.includes("--dry-run")) {
    console.log(dryRun(env, inputs).join("\n"));
    return;
  }
  const token = process.env.CLOUDFLARE_API_TOKEN;
  if (!token || !process.env.CLOUDFLARE_ACCOUNT_ID || !process.env.CLOUDFLARE_ZONE_ID) throw new Error("set CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_ZONE_ID, or use --dry-run");
  for (const r of resources(env, inputs)) {
    const current = r.lookup ? await api(token, r.lookup) : null;
    const calls = r.decide(current?.ok ? current.result : null);
    if (calls.length === 0) console.log(`ok       ${r.name}`);
    for (const c of calls) {
      if (c.method === "GET") {
        console.log(`CHECK    ${r.name}: ${c.why}`);
        continue;
      }
      const done = await api(token, c);
      // Only the status: a created Turnstile widget's response holds its secret.
      console.log(`${done.ok ? "applied" : `FAILED ${done.status}`}  ${r.name}: ${c.method} ${c.path} (${c.why})`);
    }
  }
}

if (import.meta.main) await main(process.argv.slice(2));
