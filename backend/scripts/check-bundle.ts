// Fails when test-only or development values reach the production bundle in dist/ (AC30 and the local-only keys).
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const FORBIDDEN: [string, RegExp][] = [
	["test identity provider marker", /__TEST_IDP__/],
	["local AI stand-in marker", /__LOCAL_AI_STUB__/],
	["local subscription switch marker", /__LOCAL_BILLING_STUB__/],
	["development secret value", /dev-only-/],
	["Cloudflare Turnstile test key", /1x0{20,}[A-Z]{2}/],
];

const dir = process.argv[2] ?? "dist";
const hits = readdirSync(dir).flatMap((file) => {
	const text = readFileSync(join(dir, file), "utf8");
	return FORBIDDEN.filter(([, pattern]) => pattern.test(text)).map(([what]) => `${join(dir, file)}: ${what}`);
});
if (hits.length > 0) {
	console.error(`Forbidden values in the production bundle:\n${hits.join("\n")}`);
	process.exit(1);
}
console.log(`bundle check: ${dir} has no test-only or development values`);
