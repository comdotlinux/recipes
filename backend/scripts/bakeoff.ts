/**
 * Model bake-off (plan M2): runs every model in src/ai/models.ts over test/golden/ through the real AI Gateway and
 * writes docs/model-bakeoff.md. Needs a Cloudflare account:
 *
 *   CLOUDFLARE_ACCOUNT_ID=... CLOUDFLARE_API_TOKEN=... [GATEWAY_ID=recipes] bun run bakeoff
 *
 * The requests are the ones the Worker sends (structureRequest, transcribeRequest), with payload logging and the
 * cache off. Workers AI REST route through a gateway, per
 * https://developers.cloudflare.com/ai-gateway/usage/providers/workersai/ (checked 2026-11-24).
 */
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pageText } from "../src/ai/fetch-url";
import { GATEWAY_HEADERS, GUARDRAIL_BLOCK, answerObject } from "../src/ai/gateway";
import { STRUCTURE_MODELS, VISION_MODELS } from "../src/ai/models";
import { structureRequest, toDraft } from "../src/ai/structure";
import { readTranscript, transcribeRequest } from "../src/ai/transcribe";
import { type Label, type ModelRun, type Outcome, characterErrorRate, costUsd, report, summarise } from "./bakeoff-score";

const account = process.env.CLOUDFLARE_ACCOUNT_ID;
const token = process.env.CLOUDFLARE_API_TOKEN;
const gateway = process.env.GATEWAY_ID ?? "recipes";
if (!account || !token) {
	console.error("Set CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN (an API token with Workers AI and AI Gateway access).");
	process.exit(2);
}

const GOLDEN = join(import.meta.dir, "..", "test", "golden");
const samples = (set: string, ext: string) =>
	readdirSync(join(GOLDEN, set))
		.filter((f) => f.endsWith(ext))
		.sort()
		.map((f) => ({ name: `${set}/${f}`, path: join(GOLDEN, set, f), label: JSON.parse(readFileSync(join(GOLDEN, set, f.replace(ext, ".json")), "utf8")) as Label }));

type Call = { answer: unknown; blocked: boolean; failed: boolean; ms: number; cost: number };

async function chat(model: string, request: Record<string, unknown>): Promise<Call> {
	const started = performance.now();
	const res = await fetch(`https://api.cloudflare.com/client/v4/accounts/${account}/ai/v1/chat/completions`, {
		method: "POST",
		headers: { authorization: `Bearer ${token}`, "content-type": "application/json", "cf-aig-gateway-id": gateway, ...GATEWAY_HEADERS },
		body: JSON.stringify({ model, ...request }),
	});
	const ms = performance.now() - started;
	const text = await res.text();
	if (!res.ok) return { answer: null, blocked: GUARDRAIL_BLOCK.test(text), failed: true, ms, cost: 0 };
	const body = JSON.parse(text) as { choices?: { message?: { content?: unknown } }[]; usage?: { prompt_tokens: number; completion_tokens: number } };
	const content = body.choices?.[0]?.message?.content;
	return { answer: { response: content }, blocked: false, failed: false, ms, cost: body.usage ? costUsd(model, body.usage) : 0 };
}

const outcomeOf = (call: Call): Outcome => {
	if (call.blocked) return { status: "guard_block" };
	if (call.failed) return { status: "error" };
	return toDraft(answerObject(call.answer), { sourceType: "url", sourceUrl: null }) as Outcome;
};

async function structureRuns(model: string): Promise<ModelRun[]> {
	const runs: ModelRun[] = [];
	for (const set of ["recipes", "benign", "injection"]) {
		for (const s of samples(set, ".html")) {
			const call = await chat(model, structureRequest(await pageText(readFileSync(s.path, "utf8"))));
			const outcome = outcomeOf(call);
			runs.push({ model, set, sample: s.name, outcome, latencyMs: call.ms, costUsd: call.cost, validJson: outcome.status !== "invalid" && outcome.status !== "error", label: s.label });
		}
	}
	return runs;
}

async function visionRuns(model: string): Promise<ModelRun[]> {
	const runs: ModelRun[] = [];
	for (const s of samples("photos", ".jpg")) {
		const bytes = readFileSync(s.path);
		const seen = await chat(model, transcribeRequest(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer));
		const transcript = seen.blocked ? ({ status: "guard_block" } as const) : seen.failed ? ({ status: "error" } as const) : readTranscript(seen.answer);
		const truth = readFileSync(s.path.replace(".jpg", ".txt"), "utf8");
		let outcome: Outcome = transcript.status === "text" ? { status: "error" } : (transcript as Outcome);
		let ms = seen.ms;
		let cost = seen.cost;
		if (transcript.status === "text") {
			const structured = await chat(STRUCTURE_MODELS.primary, structureRequest(transcript.text));
			outcome = outcomeOf(structured);
			ms += structured.ms;
			cost += structured.cost;
		}
		runs.push({
			model,
			set: "photos",
			sample: s.name,
			outcome,
			latencyMs: ms,
			costUsd: cost,
			validJson: transcript.status !== "invalid" && transcript.status !== "error",
			cer: transcript.status === "text" ? characterErrorRate(transcript.text, truth) : 1,
			label: s.label,
		});
	}
	return runs;
}

const summaries = [];
for (const model of [STRUCTURE_MODELS.primary, STRUCTURE_MODELS.fallback]) summaries.push(summarise(model, await structureRuns(model)));
for (const model of [VISION_MODELS.primary, VISION_MODELS.fallback]) summaries.push(summarise(model, await visionRuns(model)));
const out = join(import.meta.dir, "..", "..", "docs", "model-bakeoff.md");
writeFileSync(out, report(summaries, { date: new Date().toISOString().slice(0, 10), gateway }));
console.log(`wrote ${out}`);
