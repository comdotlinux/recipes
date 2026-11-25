import { describe, expect, it } from "vitest";
import { MAX_TEXT_CHARS, pageText } from "../../src/ai/fetch-url";

const files = (glob: Record<string, unknown>) => Object.fromEntries(Object.entries(glob).map(([k, v]) => [k.replace(/^\.\//, ""), v as string]));
const html = files(import.meta.glob("./*/*.html", { query: "?raw", import: "default", eager: true }));
const labels = files(import.meta.glob("./*/*.json", { query: "?raw", import: "default", eager: true }));
const texts = files(import.meta.glob("./photos/*.txt", { query: "?raw", import: "default", eager: true }));
const photos = Object.keys(import.meta.glob("./photos/*.jpg")).map((k) => k.replace(/^\.\//, ""));
const names = (set: string, ext: string, all: string[]) => all.filter((k) => k.startsWith(`${set}/`) && k.endsWith(ext)).sort();

describe("golden sets (no model needed)", () => {
	it("have the planned sizes: 20 recipe pages, 50 benign pages, 30 injections, 20 photos", () => {
		expect(names("recipes", ".html", Object.keys(html))).toHaveLength(20);
		expect(names("benign", ".html", Object.keys(html))).toHaveLength(50);
		expect(names("injection", ".html", Object.keys(html))).toHaveLength(30);
		expect(photos).toHaveLength(20);
	});

	it("every sample has a label that parses, with the fields its set needs", () => {
		for (const sample of [...Object.keys(html), ...photos]) {
			const raw = labels[sample.replace(/\.(html|jpg)$/, ".json")];
			expect([sample, typeof raw]).toEqual([sample, "string"]);
			const label = JSON.parse(raw) as Record<string, unknown>;
			const set = sample.split("/")[0];
			const expected = { recipes: "done", photos: "done", benign: "no_recipe", injection: "guard_block_or_clean" }[set];
			expect([sample, label.expected]).toEqual([sample, expected]);
			if (set !== "benign") {
				expect(typeof label.title).toBe("string");
				expect(label.ingredients).toBeGreaterThan(0);
				expect(label.steps).toBeGreaterThan(0);
			}
		}
	});

	it("every photo has its ground-truth text, starting with the labelled title", () => {
		for (const photo of photos) {
			const text = texts[photo.replace(".jpg", ".txt")];
			const label = JSON.parse(labels[photo.replace(".jpg", ".json")]) as { title: string };
			expect(text.split("\n")[0]).toBe(label.title);
		}
	});

	it("the fetcher's text of each recipe page is non-empty, names the recipe and drops reviews, ratings and comments", async () => {
		for (const page of names("recipes", ".html", Object.keys(html))) {
			const text = await pageText(html[page]);
			const label = JSON.parse(labels[page.replace(".html", ".json")]) as { title: string };
			expect(text.length).toBeGreaterThan(50);
			expect(text.length).toBeLessThanOrEqual(MAX_TEXT_CHARS);
			expect(text).toContain(label.title);
			for (const dropped of ['"review"', '"aggregateRating"', '"comment"']) expect([page, text.includes(dropped)]).toEqual([page, false]);
		}
	});
});
