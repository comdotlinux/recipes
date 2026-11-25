import { expect, it } from "vitest";
import source from "../src/user-do.ts?raw";

// Security review M5: the object's user id (ctx.id.name) feeds the own-share check, recipient hashes and share tokens.
// It must always go through the guarded selfId(), never a non-null assertion that would turn undefined into a value.
it("no method in user-do.ts uses a non-null assertion on ctx.id.name", () => {
	expect(source).not.toMatch(/ctx\.id\.name\s*!/);
	expect(source.match(/ctx\.id\.name/g)!.length).toBeGreaterThan(0);
});
