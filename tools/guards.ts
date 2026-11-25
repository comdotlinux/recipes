// Repository guards (plan M0 step 7; AC3, AC23, AC24, AC37, AC39, AC40, AC44).
// Run from the repo root: `bun run tools/guards.ts`. Bun built-ins only.
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

export type Status = "pass" | "fail" | "not-enforced";
export type Result = { name: string; status: Status; lines: string[] };

const SWIFT_BUDGET = 60;
const SWIFT_BUDGET_SHIM = 150;
const KOTLIN_SHARE_MIN = 0.95;
// The Kotlin share is only meaningful once the client has real size: the iOS entry point is a
// fixed Swift cost. Below this many Kotlin + Swift main source lines the share is not enforced.
export const SHARE_ENFORCED_FROM = 1200;
const HUE_MIN = 250;
const HUE_MAX = 320;

function files(root: string, dir: string, exts: string[]): string[] {
  const base = join(root, dir);
  if (!existsSync(base)) return [];
  return (readdirSync(base, { recursive: true }) as string[])
    .filter((p) => exts.some((e) => p.endsWith(e)))
    .map((p) => join(dir, p));
}

const read = (root: string, rel: string) => readFileSync(join(root, rel), "utf8");

// Main source sets only (`shared/src/*Main/`, `androidApp/src/main/`); test source sets are never scanned.
function mainFiles(root: string, exts: string[]): string[] {
  const shared = join(root, "shared/src");
  const sets = existsSync(shared) ? readdirSync(shared).filter((d) => d.endsWith("Main")).map((d) => `shared/src/${d}`) : [];
  return [...sets, "androidApp/src/main"].flatMap((dir) => files(root, dir, exts));
}

// ---------- gradients (AC24) ----------
// Compose Brush factories (also when imported bare), Compose/Android gradient shaders,
// android.graphics.drawable.GradientDrawable and <gradient> in drawable XML.
const GRADIENT =
  /\b(?:linear|horizontal|vertical|radial|sweep)Gradient\s*\(|\b(?:Linear|Radial|Sweep)Gradient(?:Shader)?\b|\bGradientDrawable\b|<gradient\b/;

export function checkGradients(root: string): Result {
  const hits: string[] = [];
  for (const f of mainFiles(root, [".kt", ".kts", ".xml"])) {
    read(root, f)
      .split("\n")
      .forEach((line, i) => {
        if (GRADIENT.test(line)) hits.push(`${f}:${i + 1}: gradient API: ${line.trim()}`);
      });
  }
  return hits.length
    ? { name: "no-gradients", status: "fail", lines: hits }
    : { name: "no-gradients", status: "pass", lines: ["no gradient API in the main source sets of shared/ and androidApp/"] };
}

// ---------- purple hue (AC24) ----------
/** HSL hue in degrees for 0..255 channels, or null for an exact grey (r = g = b), which has no hue.
 *  Same rule as ThemeRulesTest.kt: near-greys such as Material's lavender neutrals do have a hue. */
export function hue(r: number, g: number, b: number): number | null {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const c = max - min;
  if (c < 1e-9) return null;
  let h: number;
  if (max === r) h = ((g - b) / c) % 6;
  else if (max === g) h = (b - r) / c + 2;
  else h = (r - g) / c + 4;
  return (h * 60 + 360) % 360;
}

const HEX_INT = /\b0x([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\b/g; // 0xAARRGGBB or 0xRRGGBB
const HEX_STR = /#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\b/g; // "#AARRGGBB" or "#RRGGBB"
const NUM = String.raw`(0x[0-9A-Fa-f]+|\d+(?:\.\d+)?f?)`;
const RGB_CALL = new RegExp(
  String.raw`\bColor\(\s*(?:red\s*=\s*)?${NUM}\s*,\s*(?:green\s*=\s*)?${NUM}\s*,\s*(?:blue\s*=\s*)?${NUM}`,
  "g",
);
const HSX_CALL = /\bColor\.hs[vl]\(\s*(?:hue\s*=\s*)?(\d+(?:\.\d+)?)f?\s*,\s*(?:saturation\s*=\s*)?(\d+(?:\.\d+)?)f?/g;
const NAMED_PURPLE = /\bColor\.Magenta\b/g; // hue 300

function channel(tok: string): number {
  if (tok.startsWith("0x")) return parseInt(tok, 16);
  const v = parseFloat(tok);
  return tok.includes(".") || tok.endsWith("f") ? v * 255 : v;
}

export function purpleLiterals(text: string): string[] {
  const out: string[] = [];
  const add = (lit: string, h: number | null) => {
    if (h !== null && h >= HUE_MIN && h <= HUE_MAX) out.push(`${lit} (hue ${Math.round(h)})`);
  };
  for (const m of text.matchAll(HEX_INT)) {
    const v = parseInt(m[1].slice(-6), 16);
    add(m[0], hue((v >> 16) & 255, (v >> 8) & 255, v & 255));
  }
  for (const m of text.matchAll(HEX_STR)) {
    const v = parseInt(m[1].slice(-6), 16);
    add(m[0], hue((v >> 16) & 255, (v >> 8) & 255, v & 255));
  }
  for (const m of text.matchAll(RGB_CALL)) add(m[0], hue(channel(m[1]), channel(m[2]), channel(m[3])));
  for (const m of text.matchAll(HSX_CALL)) {
    if (parseFloat(m[2]) > 0) add(m[0], parseFloat(m[1]));
  }
  for (const m of text.matchAll(NAMED_PURPLE)) add(m[0], 300);
  return out;
}

// Every Kotlin file of the main source sets, so a colour literal in any screen is caught.
export function checkPurple(root: string): Result {
  const name = "no-purple-colours";
  const kt = mainFiles(root, [".kt"]);
  if (!kt.length) return { name, status: "pass", lines: ["no Kotlin main sources yet"] };
  const hits = kt.flatMap((f) => purpleLiterals(read(root, f)).map((h) => `${f}: ${h}`));
  return hits.length
    ? { name, status: "fail", lines: hits }
    : {
        name,
        status: "pass",
        lines: [`${kt.length} Kotlin main file(s), no colour literal with hue ${HUE_MIN}-${HUE_MAX} (only exact greys are hueless)`],
      };
}

// ---------- localisation parity (AC23) ----------
const RES_DIR = "shared/src/commonMain/composeResources";
const PLACEHOLDER = /%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]/g;

export function parseStrings(xml: string): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const m of xml.matchAll(/<string\s+name="([^"]+)"[^>]*?(?:\/>|>([\s\S]*?)<\/string>)/g)) out.set(m[1], [m[2] ?? ""]);
  for (const m of xml.matchAll(/<plurals\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/plurals>/g)) {
    out.set(m[1], [...m[2].matchAll(/<item\b[^>]*?(?:\/>|>([\s\S]*?)<\/item>)/g)].map((i) => i[1] ?? ""));
  }
  return out;
}

const placeholders = (values: string[]) =>
  [...new Set(values.flatMap((v) => v.replaceAll("%%", "").match(PLACEHOLDER) ?? []))].sort().join(" ");

export function checkI18n(root: string): Result {
  const name = "i18n-parity";
  const base = join(root, RES_DIR);
  const dirs = existsSync(base) ? readdirSync(base).filter((d) => /^values(-|$)/.test(d)) : [];
  const locales = dirs.filter((d) => d !== "values" && existsSync(join(base, d, "strings.xml")));
  const hasDefault = existsSync(join(base, "values", "strings.xml"));
  if (!hasDefault && !locales.length) return { name, status: "pass", lines: [`no ${RES_DIR}/values*/strings.xml yet`] };
  if (!hasDefault) return { name, status: "fail", lines: [`locale files exist but ${RES_DIR}/values/strings.xml is missing`] };
  const def = parseStrings(read(root, `${RES_DIR}/values/strings.xml`));
  const errors: string[] = [];
  const checkEmpty = (file: string, map: Map<string, string[]>) => {
    for (const [k, vs] of map) if (!vs.length || vs.some((v) => !v.trim())) errors.push(`${file}: "${k}" has an empty value`);
  };
  checkEmpty("values/strings.xml", def);
  for (const loc of locales) {
    const file = `${loc}/strings.xml`;
    const map = parseStrings(read(root, `${RES_DIR}/${file}`));
    for (const k of def.keys()) if (!map.has(k)) errors.push(`${file}: missing key "${k}"`);
    for (const k of map.keys()) if (!def.has(k)) errors.push(`${file}: extra key "${k}" not in values/strings.xml`);
    for (const [k, vs] of map) {
      const d = def.get(k);
      if (d && placeholders(d) !== placeholders(vs))
        errors.push(`${file}: "${k}" placeholders [${placeholders(vs)}] differ from default [${placeholders(d)}]`);
    }
    checkEmpty(file, map);
  }
  if (errors.length) return { name, status: "fail", lines: errors };
  return {
    name,
    status: "pass",
    lines: [
      locales.length
        ? `${def.size} keys; locales ${locales.join(", ")} match the default`
        : `only the default locale exists (${def.size} keys); nothing to compare yet`,
    ],
  };
}

// ---------- Swift budget and Kotlin share (AC3) ----------
// A "source line" is a physical line that still contains a non-whitespace character after
// removing `//` line comments and `/* */` block comments (nested, as in Kotlin and Swift).
// Blank and comment-only lines do not count. The same function counts Kotlin and Swift, and only
// main sources are counted: `shared/src/*Main/`, `androidApp/src/main/` and Swift under `iosApp/`.
// The Swift budget is always enforced; the Kotlin share only from SHARE_ENFORCED_FROM lines on.
// shortcut: string literals are tracked per line only, so a multi-line raw string containing
// comment markers can be miscounted by a line; upgrade to a real lexer if that ever matters.
export function sourceLines(text: string): number {
  let depth = 0;
  let n = 0;
  for (const line of text.split("\n")) {
    let code = false;
    let inStr = false;
    for (let i = 0; i < line.length; i++) {
      const c = line[i];
      const d = line[i + 1];
      if (depth > 0) {
        if (c === "/" && d === "*") (depth++, i++);
        else if (c === "*" && d === "/") (depth--, i++);
        continue;
      }
      if (inStr) {
        if (c === "\\") i++;
        else if (c === '"') inStr = false;
        continue;
      }
      if (c === "/" && d === "/") break;
      if (c === "/" && d === "*") {
        depth++;
        i++;
        continue;
      }
      if (c === '"') inStr = true;
      if (c.trim()) code = true;
    }
    if (code) n++;
  }
  return n;
}

// docs/spikes/S4.md records the Swift-shim alternative with a line `Decision: swift-shim`.
export const S4_SHIM = /^\s*Decision:\s*swift-shim\s*$/im;

export function checkClientLines(root: string): Result[] {
  const count = (fs: string[]) => fs.reduce((s, f) => s + sourceLines(read(root, f)), 0);
  const swiftFiles = files(root, "iosApp", [".swift"]);
  const swift = count(swiftFiles);
  const kotlin = count(mainFiles(root, [".kt"]));
  const s4 = join(root, "docs/spikes/S4.md");
  const shim = existsSync(s4) && S4_SHIM.test(readFileSync(s4, "utf8"));
  const budget = shim ? SWIFT_BUDGET_SHIM : SWIFT_BUDGET;
  const swiftRes: Result = {
    name: "swift-budget",
    status: swift <= budget ? "pass" : "fail",
    lines: [
      `${swift} Swift source lines in ${swiftFiles.length} file(s) under iosApp/, budget ${budget}` +
        (shim ? " (S4 Swift-shim alternative recorded)" : ""),
    ],
  };
  const total = kotlin + swift;
  const share = total ? kotlin / total : 0;
  const measured = `Kotlin ${kotlin} / ${total} client main source lines = ${(share * 100).toFixed(1)}% (minimum ${KOTLIN_SHARE_MIN * 100}%)`;
  const shareRes: Result =
    total < SHARE_ENFORCED_FROM
      ? {
          name: "kotlin-share",
          status: "not-enforced",
          lines: [`${measured}; NOT YET ENFORCED: ${total} lines is below the ${SHARE_ENFORCED_FROM}-line threshold`],
        }
      : { name: "kotlin-share", status: share >= KOTLIN_SHARE_MIN ? "pass" : "fail", lines: [measured] };
  return [swiftRes, shareRes];
}

// ---------- later-milestone documents (AC37, AC39, AC40, AC44) ----------
function pending(name: string, file: string, milestone: string): Result {
  return { name, status: "not-enforced", lines: [`${file} does not exist yet; it is created in ${milestone}. NOT YET ENFORCED.`] };
}

// AC37: findings live in a Markdown table with columns that include Severity, Status and Reason.
export function checkSecurityReview(root: string): Result {
  const name = "security-review";
  const file = "docs/security-review.md";
  if (!existsSync(join(root, file))) return pending(name, file, "M6");
  const rows = read(root, file)
    .split("\n")
    .filter((l) => l.trim().startsWith("|"))
    .map((l) => l.trim().replace(/^\||\|$/g, "").split("|").map((c) => c.trim()));
  const hi = rows.findIndex((r) => r.some((c) => /^severity$/i.test(c)) && r.some((c) => /^status$/i.test(c)));
  if (hi < 0) return { name, status: "fail", lines: [`${file}: no findings table with Severity and Status columns`] };
  const col = (re: RegExp) => rows[hi].findIndex((c) => re.test(c));
  const [sev, st, why] = [col(/^severity$/i), col(/^status$/i), col(/^reason$/i)];
  const errors: string[] = [];
  const findings = rows.slice(hi + 1).filter((r) => !r.every((c) => /^:?-+:?$/.test(c) || c === ""));
  for (const r of findings) {
    const status = (r[st] ?? "").toLowerCase();
    const severity = (r[sev] ?? "").toLowerCase();
    const label = r.join(" | ");
    if (status !== "fixed" && status !== "accepted") errors.push(`${file}: finding not fixed or accepted: ${label}`);
    if (status === "accepted" && (why < 0 || !(r[why] ?? "").trim())) errors.push(`${file}: accepted without a reason: ${label}`);
    if (status === "accepted" && (severity === "high" || severity === "medium" || severity === "critical"))
      errors.push(`${file}: ${severity} finding accepted instead of fixed: ${label}`);
  }
  return errors.length
    ? { name, status: "fail", lines: errors }
    : { name, status: "pass", lines: [`${findings.length} finding(s), all fixed or accepted with a reason`] };
}

// AC39: every checklist item is ticked and links to its evidence.
export function checkStoreReadiness(root: string): Result {
  const name = "store-readiness";
  const file = "docs/store-readiness.md";
  if (!existsSync(join(root, file))) return pending(name, file, "M3");
  const items = read(root, file)
    .split("\n")
    .filter((l) => /^\s*[-*]\s+\[[ xX]\]/.test(l));
  const errors: string[] = [];
  if (!items.length) errors.push(`${file}: no checklist items`);
  for (const l of items) {
    if (/\[ \]/.test(l)) errors.push(`${file}: not ticked: ${l.trim()}`);
    else if (!/\]\([^)]+\)/.test(l)) errors.push(`${file}: no evidence link: ${l.trim()}`);
  }
  return errors.length
    ? { name, status: "fail", lines: errors }
    : { name, status: "pass", lines: [`${items.length} item(s) ticked with evidence links`] };
}

// ---------- data inventory (AC44) ----------
const INVENTORY = "docs/data-inventory.md";
export const PROCESSORS = ["Cloudflare", "RevenueCat", "Apple", "Google"];
const LEGAL_PLACEHOLDER = "OWNER-LEGAL-PLACEHOLDER";
// Retention numbers the privacy policy must name, from the inventory's retention summary (minutes, days, months).
const POLICY_PERIODS = ["5", "7", "30", "90", "3", "12"];

const uniq = (xs: string[]) => [...new Set(xs)].sort();

/** Tables created by the migrations. */
export function schemaTables(migrations: string): string[] {
  return uniq([...migrations.matchAll(/CREATE TABLE (?:IF NOT EXISTS )?(\w+)/g)].map((m) => m[1]));
}

/** Meta keys the user object reads or writes, with `deletion_done_<step>` expanded from DELETION_STEPS. */
export function metaKeys(userDo: string, extra = ""): string[] {
  const text = userDo + extra;
  const keys = [
    ...[...text.matchAll(/(?:setMeta|meta|appleMap|setAppleMap)(?:<[^>]*>)?\("(\w+)"/g)].map((m) => m[1]),
    ...[...text.matchAll(/key = '(\w+)'/g)].map((m) => m[1]),
    ...[...text.matchAll(/LOCAL_ENTITLEMENT_KEY = "(\w+)"/g)].map((m) => m[1]),
  ];
  const steps = /DELETION_STEPS = \[([^\]]*)\]/.exec(userDo)?.[1] ?? "";
  for (const m of steps.matchAll(/"(\w+)"/g)) keys.push(`deletion_done_${m[1]}`);
  return uniq(keys);
}

/** Every event name of the metric unions in metrics.ts. */
export function metricEvents(metrics: string): string[] {
  return uniq([...metrics.matchAll(/event: ((?:"\w+"(?: \| )?)+)/g)].flatMap((m) => [...m[1].matchAll(/"(\w+)"/g)].map((x) => x[1])));
}

/** Retention constants (`*_TTL_MS`, `*_DAYS`) defined in backend/src. */
export function retentionConstants(sources: string[]): string[] {
  return uniq(sources.flatMap((s) => [...s.matchAll(/const (\w+_(?:TTL_MS|DAYS)) =/g)].map((m) => m[1])));
}

export function checkDataInventory(root: string): Result[] {
  const name = "data-inventory";
  // A tree without the backend (test fixtures) has nothing to compare.
  if (!existsSync(join(root, "backend/src/storage/migrations.ts"))) return [{ name, status: "pass", lines: ["no backend schema in this tree"] }];
  if (!existsSync(join(root, INVENTORY))) return [{ name, status: "fail", lines: [`${INVENTORY} is missing`] }];
  const inventory = read(root, INVENTORY);
  const named = (x: string) => inventory.includes(`\`${x}\``);
  const testing = files(root, "backend/src/testing", [".ts"]).map((f) => read(root, f)).join("\n");
  const sources = files(root, "backend/src", [".ts"]).map((f) => read(root, f));
  const wanted: [string, string[]][] = [
    ["table", schemaTables(read(root, "backend/src/storage/migrations.ts"))],
    ["meta key", metaKeys(read(root, "backend/src/user-do.ts"), testing)],
    ["metric event", metricEvents(read(root, "backend/src/metrics.ts"))],
    ["retention constant", retentionConstants(sources)],
  ];
  const missing = wanted.flatMap(([kind, xs]) => xs.filter((x) => !named(x)).map((x) => `${INVENTORY}: ${kind} \`${x}\` is not listed`));
  missing.push(...PROCESSORS.filter((p) => !inventory.includes(p)).map((p) => `${INVENTORY}: processor ${p} is not named`));
  const counts = wanted.map(([kind, xs]) => `${xs.length} ${kind}s`).join(", ");
  const inventoryResult: Result = missing.length
    ? { name, status: "fail", lines: missing }
    : { name, status: "pass", lines: [`${counts} and ${PROCESSORS.length} processors are all listed`] };

  const policyName = "privacy-policy";
  const policies = ["backend/legal/privacy.en.html", "backend/legal/privacy.de.html"].filter((f) => existsSync(join(root, f)));
  if (policies.length < 2) return [inventoryResult, { name: policyName, status: "fail", lines: ["backend/legal/privacy.en.html and privacy.de.html must exist"] }];
  if (policies.some((f) => read(root, f).includes(LEGAL_PLACEHOLDER))) {
    return [
      inventoryResult,
      {
        name: policyName,
        status: "not-enforced",
        lines: ["the privacy policy is still the owner placeholder; NOT ENFORCED until the owner pastes the generator text (production deploy is blocked by backend/scripts/check-legal.ts)"],
      },
    ];
  }
  const policyErrors = policies.flatMap((f) => {
    const text = read(root, f);
    return [
      ...PROCESSORS.filter((p) => !text.includes(p)).map((p) => `${f}: processor ${p} is not named`),
      ...POLICY_PERIODS.filter((n) => !new RegExp(`\\b${n}\\b`).test(text)).map((n) => `${f}: retention period ${n} (see ${INVENTORY}) is not named`),
    ];
  });
  return [
    inventoryResult,
    policyErrors.length ? { name: policyName, status: "fail", lines: policyErrors } : { name: policyName, status: "pass", lines: ["both privacy policies name every processor and retention period"] },
  ];
}

// ---------- restore runbook (AC40) ----------
export const RESTORE_RULE = /never restore an object to a point (?:in time )?before its erasure/i;

export function checkRestoreRunbook(root: string): Result {
  const name = "restore-runbook";
  const file = "docs/runbooks/restore.md";
  if (!existsSync(join(root, file))) return pending(name, file, "M6");
  const text = read(root, file);
  if (!RESTORE_RULE.test(text)) return { name, status: "fail", lines: [`${file}: the rule "never restore an object to a point before its erasure" is missing`] };
  const record = /^## Drill record\s*$([\s\S]*?)(?=^## |(?![\s\S]))/m.exec(text)?.[1] ?? "";
  const filled = record.split("\n").some((l) => /^\s*\|.*\d{4}-\d{2}-\d{2}/.test(l));
  return filled
    ? { name, status: "pass", lines: [`${file}: rule present and a drill is recorded`] }
    : { name, status: "not-enforced", lines: [`${file}: rule present; drill record empty, NOT ENFORCED until the owner runs the staging drill (needs the Cloudflare account)`] };
}

export function runAll(root: string): Result[] {
  return [
    checkGradients(root),
    checkPurple(root),
    checkI18n(root),
    ...checkClientLines(root),
    checkSecurityReview(root),
    checkStoreReadiness(root),
    ...checkDataInventory(root),
    checkRestoreRunbook(root),
  ];
}

if (import.meta.main) {
  const results = runAll(process.cwd());
  const tag = { pass: "PASS", fail: "FAIL", "not-enforced": "NOT ENFORCED" } as const;
  for (const r of results) for (const l of r.lines) console.log(`${tag[r.status]} ${r.name}: ${l}`);
  const failed = results.filter((r) => r.status === "fail");
  if (failed.length) {
    console.error(`\n${failed.length} guard(s) failed: ${failed.map((r) => r.name).join(", ")}`);
    process.exit(1);
  }
}
