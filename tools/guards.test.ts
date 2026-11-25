import { describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import {
  checkClientLines,
  checkDataInventory,
  checkRestoreRunbook,
  metaKeys,
  metricEvents,
  schemaTables,
  checkGradients,
  checkI18n,
  checkPurple,
  checkSecurityReview,
  checkStoreReadiness,
  hue,
  runAll,
  sourceLines,
} from "./guards";

// Each fixture gets its own fresh, uniquely named directory; nothing is ever deleted.
function fixture(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), "guards-fixture-"));
  for (const [p, c] of Object.entries(files)) {
    mkdirSync(dirname(join(root, p)), { recursive: true });
    writeFileSync(join(root, p), c);
  }
  return root;
}

const THEME = "shared/src/commonMain/kotlin/com/linux/recipes/ui/theme/Theme.kt";
const RES = "shared/src/commonMain/composeResources";

describe("gradients", () => {
  test("passes without gradient API", () => {
    const r = checkGradients(fixture({ "shared/src/commonMain/kotlin/A.kt": "val b = SolidColor(Color.Red)\n" }));
    expect(r.status).toBe("pass");
  });
  test.each([
    ["shared/src/commonMain/kotlin/A.kt", "val b = Brush.verticalGradient(listOf(a, b))"],
    ["shared/src/commonMain/kotlin/A.kt", "val b = linearGradient(colors)"],
    ["androidApp/src/main/kotlin/A.kt", "val d = GradientDrawable()"],
    ["androidApp/src/main/res/drawable/bg.xml", "<shape><gradient android:startColor=\"#fff\"/></shape>"],
  ])("fails on %s: %s", (path, src) => {
    const r = checkGradients(fixture({ [path]: src }));
    expect(r.status).toBe("fail");
    expect(r.lines[0]).toContain(path);
  });
  test("ignores test source sets", () => {
    const r = checkGradients(fixture({ "shared/src/commonTest/kotlin/A.kt": "val b = Brush.verticalGradient(c)" }));
    expect(r.status).toBe("pass");
  });
});

describe("purple hue", () => {
  test("hue maths", () => {
    expect(hue(255, 0, 0)).toBe(0);
    expect(hue(128, 0, 255)).toBeCloseTo(270, 0);
    expect(hue(128, 128, 128)).toBeNull(); // exact grey: no hue
    expect(hue(0xfe, 0xf7, 0xff)).toBeCloseTo(292.5, 1); // Material lavender neutral #FEF7FF has a hue
  });
  test("passes on green, orange and greys", () => {
    const src = "val a = Color(0xFF2E7D32)\nval b = Color(0xFFFF9800)\nval c = Color(0xFF121212)\nval d = Color(0xFF808080)\n";
    expect(checkPurple(fixture({ [THEME]: src })).status).toBe("pass");
  });
  test("passes when no Kotlin main sources exist", () => {
    expect(checkPurple(fixture({})).status).toBe("pass");
  });
  test("ignores test source sets", () => {
    const r = checkPurple(fixture({ "shared/src/commonTest/kotlin/com/linux/recipes/ui/theme/T.kt": "val p = Color(0xFF8000FF)" }));
    expect(r.status).toBe("pass");
  });
  test("catches a literal in any main screen file", () => {
    const r = checkPurple(fixture({ "androidApp/src/main/kotlin/com/linux/recipes/ui/list/List.kt": "val p = Color(0xFFFEF7FF)" }));
    expect(r.status).toBe("fail");
  });
  test.each([
    "val p = Color(0xFF6750A4)",
    "val p = Color(red = 0.5f, green = 0.2f, blue = 0.8f)",
    "val p = Color(128, 0, 255)",
    "val p = Color.hsv(280f, 0.6f, 0.5f)",
    "val p = Color.Magenta",
  ])("fails on %s", (src) => {
    expect(checkPurple(fixture({ [THEME]: src })).status).toBe("fail");
  });
});

describe("i18n parity", () => {
  const def = `<resources><string name="hello">Hello %1$s</string><string name="bye">Bye</string></resources>`;
  test("passes with no resources", () => {
    const r = checkI18n(fixture({}));
    expect(r.status).toBe("pass");
    expect(r.lines[0]).toContain("no ");
  });
  test("passes and says so with only the default locale", () => {
    const r = checkI18n(fixture({ [`${RES}/values/strings.xml`]: def }));
    expect(r.status).toBe("pass");
    expect(r.lines[0]).toContain("only the default locale");
  });
  test("passes with a matching locale", () => {
    const de = `<resources><string name="hello">Hallo %1$s</string><string name="bye">Tschüss</string></resources>`;
    expect(checkI18n(fixture({ [`${RES}/values/strings.xml`]: def, [`${RES}/values-de/strings.xml`]: de })).status).toBe("pass");
  });
  test.each([
    ["missing key", `<resources><string name="hello">Hallo %1$s</string></resources>`, "missing key"],
    ["extra key", `<resources><string name="hello">Hallo %1$s</string><string name="bye">B</string><string name="x">X</string></resources>`, "extra key"],
    ["placeholder", `<resources><string name="hello">Hallo %1$d</string><string name="bye">B</string></resources>`, "placeholders"],
    ["empty", `<resources><string name="hello">Hallo %1$s</string><string name="bye"></string></resources>`, "empty value"],
  ])("fails on %s", (_n, de, msg) => {
    const r = checkI18n(fixture({ [`${RES}/values/strings.xml`]: def, [`${RES}/values-de/strings.xml`]: de }));
    expect(r.status).toBe("fail");
    expect(r.lines.join("\n")).toContain(msg);
  });
  test("fails when a locale exists without the default", () => {
    expect(checkI18n(fixture({ [`${RES}/values-de/strings.xml`]: def })).status).toBe("fail");
  });
});

describe("source lines, Swift budget, Kotlin share", () => {
  test("counts code lines only", () => {
    const src = `// comment\n\nval a = 1 // trailing\n/* block\n still */\nval s = "/* not a comment */"\n/* a /* nested */ b */ val c = 2\n`;
    expect(sourceLines(src)).toBe(3);
  });
  const kotlin = (n: number) => Array.from({ length: n }, (_, i) => `val v${i} = ${i}`).join("\n");
  const swift = (n: number) => Array.from({ length: n }, (_, i) => `let v${i} = ${i}`).join("\n");
  test("passes within budget and share at the threshold", () => {
    const [s, k] = checkClientLines(fixture({ "shared/src/commonMain/kotlin/A.kt": kotlin(1150), "iosApp/iosApp/App.swift": swift(60) }));
    expect(s.status).toBe("pass");
    expect(k.status).toBe("pass");
  });
  test("share is not enforced below 1200 lines and never passes there", () => {
    const [s, k] = checkClientLines(fixture({ "shared/src/commonMain/kotlin/A.kt": kotlin(153), "iosApp/iosApp/App.swift": swift(16) }));
    expect(s.status).toBe("pass");
    expect(k.status).toBe("not-enforced");
    expect(k.lines[0]).toContain("90.5%");
    expect(k.lines[0]).toContain("169");
    expect(k.lines[0]).toContain("1200");
  });
  test("counts main source sets only", () => {
    const [, k] = checkClientLines(fixture({ "shared/src/commonMain/kotlin/A.kt": kotlin(10), "shared/src/commonTest/kotlin/T.kt": kotlin(5000) }));
    expect(k.lines[0]).toContain("Kotlin 10 / 10");
  });
  test("fails above 60 Swift lines", () => {
    const [s] = checkClientLines(fixture({ "shared/src/commonMain/kotlin/A.kt": kotlin(5000), "iosApp/iosApp/App.swift": swift(61) }));
    expect(s.status).toBe("fail");
  });
  test("allows 150 when S4 records the Swift shim", () => {
    const root = fixture({
      "shared/src/commonMain/kotlin/A.kt": kotlin(5000),
      "iosApp/iosApp/App.swift": swift(150),
      "docs/spikes/S4.md": "# S4\nDecision: swift-shim\n",
    });
    expect(checkClientLines(root)[0].status).toBe("pass");
  });
  test("fails when Kotlin share is below 95 percent above the threshold", () => {
    const [s, k] = checkClientLines(
      fixture({ "shared/src/commonMain/kotlin/A.kt": kotlin(1100), "iosApp/iosApp/App.swift": swift(120), "docs/spikes/S4.md": "Decision: swift-shim\n" }),
    );
    expect(s.status).toBe("pass");
    expect(k.status).toBe("fail");
  });
});

describe("later-milestone documents", () => {
  test("absent files are not enforced and never pass", () => {
    const root = fixture({});
    for (const r of [checkSecurityReview(root), checkStoreReadiness(root)]) {
      expect(r.status).toBe("not-enforced");
      expect(r.lines[0]).toMatch(/NOT YET ENFORCED/);
      expect(r.lines[0]).toMatch(/M\d/);
    }
  });

  const table = (rows: string) => `| ID | Severity | Status | Reason |\n|---|---|---|---|\n${rows}`;
  test("security review passes", () => {
    const md = table("| 1 | high | fixed | |\n| 2 | low | accepted | needs no fix because X |\n");
    expect(checkSecurityReview(fixture({ "docs/security-review.md": md })).status).toBe("pass");
  });
  test.each([
    ["| 1 | medium | accepted | later |\n", "accepted instead of fixed"],
    ["| 1 | low | accepted | |\n", "without a reason"],
    ["| 1 | low | open | |\n", "not fixed or accepted"],
  ])("security review fails: %s", (rows, msg) => {
    const r = checkSecurityReview(fixture({ "docs/security-review.md": table(rows) }));
    expect(r.status).toBe("fail");
    expect(r.lines.join("\n")).toContain(msg);
  });

  test("store readiness passes", () => {
    const md = "- [x] Privacy labels ([evidence](https://example.com/a))\n";
    expect(checkStoreReadiness(fixture({ "docs/store-readiness.md": md })).status).toBe("pass");
  });
  test.each(["- [ ] Privacy labels\n", "- [x] Privacy labels, no link\n", "nothing here\n"])("store readiness fails: %s", (md) => {
    expect(checkStoreReadiness(fixture({ "docs/store-readiness.md": md })).status).toBe("fail");
  });
});

describe("CLI", () => {
  test("exits 0 on a clean tree and 1 on a failing one", () => {
    const script = join(import.meta.dir, "guards.ts");
    const ok = Bun.spawnSync(["bun", "run", script], { cwd: fixture({}) });
    expect(ok.exitCode).toBe(0);
    const bad = Bun.spawnSync(["bun", "run", script], { cwd: fixture({ [THEME]: "val p = Color(0xFF6750A4)" }) });
    expect(bad.exitCode).toBe(1);
    expect(bad.stdout.toString()).toContain("FAIL no-purple-colours");
    expect(runAll(fixture({})).some((r) => r.status === "fail")).toBe(false);
  });
});

describe("data inventory (AC44)", () => {
  const base = {
    "backend/src/storage/migrations.ts": 'sql.exec("CREATE TABLE IF NOT EXISTS meta (k)"); sql.exec("CREATE TABLE recipes (id)");',
    "backend/src/user-do.ts": 'export const DELETION_STEPS = ["apple"] as const; this.setMeta("state", s); this.meta("rev"); const KEEP_DAYS = 3;',
    "backend/src/metrics.ts": '| { event: "request"; group: string } | { event: "share" | "pdf"; outcome: string }',
    "backend/legal/privacy.en.html": "OWNER-LEGAL-PLACEHOLDER",
    "backend/legal/privacy.de.html": "OWNER-LEGAL-PLACEHOLDER",
  };
  const all = "`meta` `recipes` `state` `rev` `deletion_done_apple` `request` `share` `pdf` `KEEP_DAYS` Cloudflare RevenueCat Apple Google";
  test("parsers", () => {
    expect(schemaTables(base["backend/src/storage/migrations.ts"])).toEqual(["meta", "recipes"]);
    expect(metaKeys(base["backend/src/user-do.ts"])).toEqual(["deletion_done_apple", "rev", "state"]);
    expect(metricEvents(base["backend/src/metrics.ts"])).toEqual(["pdf", "request", "share"]);
  });
  test("passes when everything is listed; the placeholder policy is not enforced", () => {
    const [inv, policy] = checkDataInventory(fixture({ ...base, "docs/data-inventory.md": all }));
    expect(inv.status).toBe("pass");
    expect(policy.status).toBe("not-enforced");
  });
  test("fails on a table, key, event or processor missing from the inventory", () => {
    const [inv] = checkDataInventory(fixture({ ...base, "docs/data-inventory.md": all.replace("`recipes`", "").replace("`pdf`", "").replace("Google", "") }));
    expect(inv.status).toBe("fail");
    expect(inv.lines.join("\n")).toMatch(/table `recipes`[\s\S]*metric event `pdf`[\s\S]*processor Google/);
  });
  test("a filled privacy policy must name every processor and retention period", () => {
    const policy = "Cloudflare RevenueCat Apple Google 5 7 30 90 3 12";
    const ok = checkDataInventory(fixture({ ...base, "docs/data-inventory.md": all, "backend/legal/privacy.en.html": policy, "backend/legal/privacy.de.html": policy }));
    expect(ok[1].status).toBe("pass");
    const bad = checkDataInventory(fixture({ ...base, "docs/data-inventory.md": all, "backend/legal/privacy.en.html": "Cloudflare", "backend/legal/privacy.de.html": policy }));
    expect(bad[1].status).toBe("fail");
  });
  test("the real repository passes", () => {
    expect(checkDataInventory(process.cwd())[0].status).toBe("pass");
  });
});

describe("restore runbook (AC40)", () => {
  test("missing file is pending, a missing rule fails, an empty record is not enforced, a dated record passes", () => {
    expect(checkRestoreRunbook(fixture({})).status).toBe("not-enforced");
    expect(checkRestoreRunbook(fixture({ "docs/runbooks/restore.md": "# Restore" })).status).toBe("fail");
    const rule = "Never restore an object to a point before its erasure.\n\n## Drill record\n\nNot run yet.\n";
    expect(checkRestoreRunbook(fixture({ "docs/runbooks/restore.md": rule })).status).toBe("not-enforced");
    const done = rule.replace("Not run yet.", "| Date | Result |\n|---|---|\n| 2026-12-01 | restored |");
    expect(checkRestoreRunbook(fixture({ "docs/runbooks/restore.md": done })).status).toBe("pass");
  });
});
