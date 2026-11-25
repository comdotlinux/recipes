// Production deploy gate: account deletion must be able to reach RevenueCat and Apple, so the variables it needs may
// not be empty in env.production of wrangler.jsonc (security review M6, finding 2).
import { readFileSync } from "node:fs";
import { join } from "node:path";

export const REQUIRED_PRODUCTION_VARS = ["REVENUECAT_PROJECT_ID", "APPLE_TEAM_ID", "APPLE_KEY_ID"];

export function missingProductionVars(wranglerText: string): string[] {
	// Comment lines are dropped before parsing; no value in the config contains a line-leading "//".
	const config = JSON.parse(
		wranglerText
			.split("\n")
			.filter((l) => !l.trim().startsWith("//"))
			.join("\n"),
	) as { env: { production: { vars: Record<string, string> } } };
	return REQUIRED_PRODUCTION_VARS.filter((name) => !config.env.production.vars[name]?.trim());
}

if (import.meta.main) {
	const missing = missingProductionVars(readFileSync(join(import.meta.dir, "..", "wrangler.jsonc"), "utf8"));
	if (missing.length > 0) {
		console.error(`env.production in backend/wrangler.jsonc has empty variables needed by account deletion: ${missing.join(", ")}`);
		process.exit(1);
	}
	console.log("production config check: deletion variables are set");
}
