/** The printable recipe page for GET /recipes/:id/pdf (src/pdf.ts renders it). Plain text only: every field is escaped. */
import { base64 } from "./ai/transcribe";
import type { RecipeInput } from "./recipes";

/** Plan 4.8: the template's own CSP. */
export const PDF_CSP = "default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'";

const ENTITIES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
export const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ENTITIES[c]);

type Labels = { ingredients: string; steps: string; servings: string; prep: string; cook: string; min: string };
/** The page's few fixed texts in the app's ten languages, chosen by the recipe's language; English otherwise. */
export const LABELS: Record<string, Labels> = {
	en: { ingredients: "Ingredients", steps: "Steps", servings: "servings", prep: "prep", cook: "cook", min: "min" },
	de: { ingredients: "Zutaten", steps: "Zubereitung", servings: "Portionen", prep: "Vorbereitung", cook: "Kochen", min: "Min." },
	fr: { ingredients: "Ingrédients", steps: "Étapes", servings: "portions", prep: "préparation", cook: "cuisson", min: "min" },
	es: { ingredients: "Ingredientes", steps: "Pasos", servings: "raciones", prep: "preparación", cook: "cocción", min: "min" },
	it: { ingredients: "Ingredienti", steps: "Procedimento", servings: "porzioni", prep: "preparazione", cook: "cottura", min: "min" },
	pt: { ingredients: "Ingredientes", steps: "Modo de preparo", servings: "porções", prep: "preparação", cook: "cozimento", min: "min" },
	nl: { ingredients: "Ingrediënten", steps: "Bereiding", servings: "porties", prep: "voorbereiding", cook: "kooktijd", min: "min" },
	pl: { ingredients: "Składniki", steps: "Przygotowanie", servings: "porcje", prep: "przygotowanie", cook: "gotowanie", min: "min" },
	tr: { ingredients: "Malzemeler", steps: "Hazırlanışı", servings: "porsiyon", prep: "hazırlık", cook: "pişirme", min: "dk" },
	sv: { ingredients: "Ingredienser", steps: "Gör så här", servings: "portioner", prep: "förberedelse", cook: "tillagning", min: "min" },
};

const STYLE = `body{font-family:sans-serif;margin:0;color:#222}h1{font-size:22pt;margin:0 0 6pt}h2{font-size:13pt;margin:14pt 0 4pt}
.meta{color:#555}img{max-width:100%;max-height:9cm;margin:4pt 0}li{margin:2pt 0}p{white-space:pre-wrap}`;

/** The printable page. Plain text only: nothing from the recipe can become markup. */
export function pdfHtml(recipe: RecipeInput, pictures: { mime: string; bytes: ArrayBuffer }[]): string {
	const e = escapeHtml;
	const t = LABELS[recipe.language.split("-")[0].toLowerCase()] ?? LABELS.en;
	const meta = [
		recipe.servings !== null ? `${recipe.servings} ${t.servings}` : "",
		recipe.prepMinutes !== null ? `${t.prep} ${recipe.prepMinutes} ${t.min}` : "",
		recipe.cookMinutes !== null ? `${t.cook} ${recipe.cookMinutes} ${t.min}` : "",
	].filter((m) => m);
	let group: string | null = null;
	const ingredients = recipe.ingredients
		.map((i) => {
			const heading = i.group !== null && i.group !== group ? `</ul><h3>${e(i.group)}</h3><ul>` : "";
			group = i.group;
			return `${heading}<li>${e([i.quantity, i.unit, i.item].filter((p) => p).join(" "))}</li>`;
		})
		.join("");
	return `<!doctype html>
<html lang="${e(recipe.language)}">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${PDF_CSP}">
<title>${e(recipe.title)}</title>
<style>${STYLE}</style>
</head>
<body>
<h1>${e(recipe.title)}</h1>
${meta.length ? `<p class="meta">${e(meta.join(" · "))}</p>` : ""}
${pictures.map((p) => `<img alt="" src="data:${p.mime};base64,${base64(p.bytes)}">`).join("\n")}
${recipe.description ? `<p>${e(recipe.description)}</p>` : ""}
${recipe.ingredients.length ? `<h2>${e(t.ingredients)}</h2><ul>${ingredients}</ul>` : ""}
${recipe.steps.length ? `<h2>${e(t.steps)}</h2><ol>${recipe.steps.map((s) => `<li><p>${e(s)}</p></li>`).join("")}</ol>` : ""}
${recipe.tags.length ? `<p class="meta">${e(recipe.tags.join(", "))}</p>` : ""}
</body>
</html>
`;
}
