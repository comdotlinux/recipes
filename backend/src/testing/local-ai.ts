import type { ModelRunner } from "../ai/gateway";
import { VISION_MODELS } from "../ai/models";

// Marker that scripts/check-bundle.ts rejects: this stand-in must never reach the production bundle.
export const LOCAL_AI_MARKER = "__LOCAL_AI_STUB__";

/** Text the stand-in "reads" from any photo. */
export const LOCAL_PHOTO_TEXT = "Local photo recipe\n1 cup of flour\nMix and bake.";

/**
 * Deterministic stand-in for the Workers AI binding in env.local, so every import outcome can be produced on purpose:
 * `__NO_RECIPE__` -> no_recipe, `__GUARD__` -> guard_block, `__FAIL__` -> failed (after the retries), otherwise done
 * with the first line as title and the other lines as steps. A translation request gets the source back with
 * "[<language name>] " in front of the title, the ingredient items and the steps.
 */
export const localAi: ModelRunner & { marker: string } = {
	marker: LOCAL_AI_MARKER,
	run: (async (model: string, inputs: { messages?: { role: string; content: unknown }[] }) => {
		if (model === VISION_MODELS.primary || model === VISION_MODELS.fallback) {
			return { response: JSON.stringify({ is_recipe: true, text: LOCAL_PHOTO_TEXT }) };
		}
		const text = String(inputs.messages?.find((m) => m.role === "user")?.content ?? "");
		// Translation: the source fields back with "[<language name>] " in front of each text, so the copy is recognisable.
		const target = /^Translate the recipe JSON in the user message into (\w+)\./.exec(String(inputs.messages?.[0]?.content ?? ""));
		if (target) {
			const tag = (s: string) => (s ? `[${target[1]}] ${s}`.slice(0, 200) : s);
			const r = JSON.parse(text) as { title: string; description: string; ingredients: { item: string }[]; steps: string[]; tags: string[] };
			return { response: { ...r, title: tag(r.title), ingredients: r.ingredients.map((i) => ({ ...i, item: tag(i.item) })), steps: r.steps.map((x) => tag(x).slice(0, 4000)) } };
		}
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
