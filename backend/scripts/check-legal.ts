// Production deploy gate: fails while any owner-supplied legal text under backend/legal/ still has the placeholder
// marker (backend/legal/README.md). Staging may show the placeholder; production may not.
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const MARKER = "OWNER-LEGAL-PLACEHOLDER";
const dir = join(import.meta.dir, "..", "legal");
const left = readdirSync(dir)
	.filter((f) => f.endsWith(".html"))
	.filter((f) => readFileSync(join(dir, f), "utf8").includes(MARKER));
if (left.length > 0) {
	console.error(`Legal texts still contain ${MARKER}; production cannot be deployed:\n${left.map((f) => `backend/legal/${f}`).join("\n")}`);
	process.exit(1);
}
console.log("legal check: no placeholder left in backend/legal/");
