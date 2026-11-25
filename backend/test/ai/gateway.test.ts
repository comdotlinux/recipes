import { describe, expect, it } from "vitest";
import { answerObject, runModel } from "../../src/ai/gateway";
import * as models from "../../src/ai/models";
import { STRUCTURE_MODELS, VISION_MODELS } from "../../src/ai/models";
import { structureRecipe } from "../../src/ai/structure";
import { transcribePhoto } from "../../src/ai/transcribe";

const ai = (plan: (model: string) => unknown) => {
	const models: string[] = [];
	return {
		models,
		env: {
			AI: {
				run: async (model: string) => {
					models.push(model);
					return plan(model);
				},
			} as never,
		},
	};
};
const fail = (message: string) => () => {
	throw new Error(message);
};

describe("gateway", () => {
	it("refuses when the AI binding is missing (local environment)", async () => {
		await expect(runModel({}, 1, "structure", "m", {})).rejects.toThrow("AI binding is not configured");
	});

	it("reads the answer from an object, a JSON string, a fenced string, or nothing", () => {
		expect(answerObject({ response: { a: 1 } })).toEqual({ a: 1 });
		expect(answerObject({ response: 'Here:\n```json\n{"a":2}\n```' })).toEqual({ a: 2 });
		expect(answerObject({ response: "no json" })).toBeNull();
		expect(answerObject({ response: "{broken}" })).toBeNull();
		expect(answerObject(null)).toBeNull();
	});
});

describe("model fallback", () => {
	it("structure falls back to the second model on an error, and propagates when both fail", async () => {
		const both = ai((m) => (m === STRUCTURE_MODELS.primary ? fail("500")() : { response: { is_recipe: false } }));
		expect(await structureRecipe(both.env, 1, "x", { sourceType: "photo", sourceUrl: null })).toEqual({ status: "invalid" });
		expect(both.models).toEqual([STRUCTURE_MODELS.primary, STRUCTURE_MODELS.fallback]);
		await expect(structureRecipe(ai(fail("500")).env, 1, "x", { sourceType: "photo", sourceUrl: null })).rejects.toThrow("500");
	});

	it("transcribe: fallback, guardrail block, unusable answer, and both models failing", async () => {
		const fallback = ai((m) => (m === VISION_MODELS.primary ? fail("timeout")() : { response: '{"is_recipe":true,"text":"Mehl"}' }));
		expect(await transcribePhoto(fallback.env, 1, new ArrayBuffer(4))).toEqual({ status: "text", text: "Mehl" });
		expect(fallback.models).toEqual([VISION_MODELS.primary, VISION_MODELS.fallback]);
		expect(await transcribePhoto(ai(fail("Response blocked by guardrail")).env, 1, new ArrayBuffer(4))).toEqual({ status: "guard_block" });
		expect(await transcribePhoto(ai(() => ({ response: "?" })).env, 1, new ArrayBuffer(4))).toEqual({ status: "invalid" });
		await expect(transcribePhoto(ai(fail("down")).env, 1, new ArrayBuffer(4))).rejects.toThrow("down");
	});
});

describe("model choice and gateway settings behind the AI notice", () => {
	// The AI notice promises what https://developers.cloudflare.com/workers-ai/platform/data-usage/ states for Workers AI.
	// That page does not cover other providers reached through AI Gateway: change the notice text before relaxing this test.
	it("every model id, primary and fallback, is a Workers AI model (@cf/)", () => {
		const ids = Object.values(models).flatMap((group) => Object.values(group as Record<string, string>));
		expect(ids.length).toBeGreaterThanOrEqual(4);
		for (const id of ids) expect(id).toMatch(/^@cf\//);
	});

	it("every call through the wrapper skips the cache and the payload log", async () => {
		const seen: unknown[] = [];
		const env = { AI: { run: async (_m: string, _i: unknown, options: unknown) => (seen.push(options), { response: {} }) } as never };
		await runModel(env, 1, "structure", STRUCTURE_MODELS.primary, {});
		await runModel(env, 1, "transcribe", VISION_MODELS.primary, {});
		expect(seen).toEqual([
			{ gateway: { id: "recipes", skipCache: true, metadata: { feature: "structure" } }, extraHeaders: { "cf-aig-collect-log-payload": "false", "cf-aig-skip-cache": "true" } },
			{ gateway: { id: "recipes", skipCache: true, metadata: { feature: "transcribe" } }, extraHeaders: { "cf-aig-collect-log-payload": "false", "cf-aig-skip-cache": "true" } },
		]);
	});
});
