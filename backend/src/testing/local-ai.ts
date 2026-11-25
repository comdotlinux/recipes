import type { ModelRunner } from "../ai/gateway";
import { VISION_MODELS } from "../ai/models";

// Marker that scripts/check-bundle.ts rejects: this stand-in must never reach the production bundle.
export const LOCAL_AI_MARKER = "__LOCAL_AI_STUB__";

/** Text the stand-in "reads" from any photo. */
export const LOCAL_PHOTO_TEXT = "Local photo recipe\n1 cup of flour\nMix and bake.";

/**
 * Deterministic stand-in for the Workers AI binding in env.local, so every import outcome can be produced on purpose:
 * `__NO_RECIPE__` -> no_recipe, `__GUARD__` -> guard_block, `__FAIL__` -> failed (after the retries), otherwise done
 * with the first line as title and the other lines as steps.
 */
export const localAi: ModelRunner & { marker: string } = {
	marker: LOCAL_AI_MARKER,
	run: (async (model: string, inputs: { messages?: { role: string; content: unknown }[] }) => {
		if (model === VISION_MODELS.primary || model === VISION_MODELS.fallback) {
			return { response: JSON.stringify({ is_recipe: true, text: LOCAL_PHOTO_TEXT }) };
		}
		const text = String(inputs.messages?.find((m) => m.role === "user")?.content ?? "");
		if (text.includes("__GUARD__")) throw new Error(`Prompt blocked due to security configurations (${LOCAL_AI_MARKER})`);
		if (text.includes("__FAIL__")) throw new Error(`${LOCAL_AI_MARKER}: simulated model failure`);
		const lines = text
			.split("\n")
			.map((l) => l.trim())
			.filter((l) => l.length > 0);
		return {
			response: {
				is_recipe: !text.includes("__NO_RECIPE__") && lines.length > 0,
				title: (lines[0] ?? "").slice(0, 200),
				description: "",
				servings: null,
				prepMinutes: null,
				cookMinutes: null,
				ingredients: [],
				steps: lines.slice(1, 101).map((l) => l.slice(0, 4000)),
				tags: [],
				language: "en",
			},
		};
	}) as ModelRunner["run"],
};
