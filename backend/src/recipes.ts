import { z } from "zod";

export const ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const isId = (v: string) => ID_PATTERN.test(v);

export const MAX_RECIPE_BYTES = 64 * 1024;
export const MAX_RECIPES = 2000;
export const MAX_IMAGES_PER_RECIPE = 5;

/**
 * Removes C0 and C1 controls except tab and newline, bidirectional controls (a U+202E can make a title read backwards),
 * zero-width space, line and paragraph separators and the byte-order mark. Used on model output and on shared recipes.
 */
export const stripControls = (s: string) =>
	s.replace(/[\u0000-\u0008\u000B-\u001F\u007F-\u009F\u061C\u200B\u200E\u200F\u2028\u2029\u202A-\u202E\u2066-\u2069\uFEFF]/g, "");

const text = (min: number, max: number) => z.string().min(min).max(max);
const count = (max: number) => z.number().int().min(0).max(max).nullable();

/** Field rules from docs/api.md, "Recipe". Unknown fields are rejected. */
export const RecipeInput = z.strictObject({
	id: z.string().regex(ID_PATTERN),
	title: text(1, 200),
	description: text(0, 2000),
	servings: z.number().int().min(1).max(100).nullable(),
	prepMinutes: count(10000),
	cookMinutes: count(10000),
	ingredients: z
		.array(
			z.strictObject({
				quantity: text(0, 40),
				unit: text(0, 40),
				item: text(1, 200),
				group: text(0, 80).nullable(),
			}),
		)
		.max(100),
	steps: z.array(text(1, 4000)).max(100),
	tags: z.array(text(1, 40)).max(20),
	sourceUrl: text(0, 2048).nullable(),
	sourceType: z.enum(["manual", "photo", "url", "shared"]),
	language: z.string().min(2).max(35).regex(/^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$/),
	imageIds: z
		.array(z.string().regex(ID_PATTERN))
		.max(MAX_IMAGES_PER_RECIPE)
		.refine((ids) => new Set(ids).size === ids.length, "imageIds must not repeat"),
});
export type RecipeInput = z.infer<typeof RecipeInput>;

export type Recipe = RecipeInput & { rev: number; deleted: false; updatedAt: string };
export type DeletionMarker = { id: string; rev: number; deleted: true; updatedAt: string };

/** ISO 8601 UTC without milliseconds, as in the contract ("2026-11-24T18:00:00Z"). */
export const isoTime = (ms: number) => `${new Date(ms).toISOString().slice(0, 19)}Z`;

/** A stored translation as the contract shows it inside a recipe (docs/api.md, Milestone M4). */
export type TranslationView = { language: string; stale: boolean } & Record<string, unknown>;

/** Server view in the field order of the contract; `translations` only when the recipe has at least one. */
export function toRecipe(
	row: { id: string; rev: number; deleted: number; updated_at: number; json: string | null },
	translations: TranslationView[] = [],
): Recipe | DeletionMarker {
	const updatedAt = isoTime(row.updated_at);
	if (row.deleted || row.json === null) return { id: row.id, rev: row.rev, deleted: true, updatedAt };
	const { id, ...rest } = JSON.parse(row.json) as RecipeInput;
	return { id, rev: row.rev, deleted: false, updatedAt, ...rest, ...(translations.length ? { translations } : {}) };
}
