/**
 * The web deletion page `GET /account/delete` (plan 4.7, AC29) for people without the app. It loads third-party script
 * only from Turnstile and from the configured providers' documented origins (docs/versions/backend.md, "Web sign-in"),
 * and its CSP lists exactly those. Without a Turnstile site key and at least one configured provider it is a 404.
 */
import { audiences, escapeHtml } from "./web";

export const DELETE_PAGE = "/account/delete";

const TURNSTILE = "https://challenges.cloudflare.com";
// Google Identity Services: https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid (CSP section).
const GSI_SCRIPT = "https://accounts.google.com/gsi/client";
const GSI = "https://accounts.google.com/gsi/";
const GSI_STYLE = "https://accounts.google.com/gsi/style";
// Sign in with Apple JS: https://developer.apple.com/documentation/signinwithapple/configuring-your-webpage-for-sign-in-with-apple
const APPLE_SCRIPT = "https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js";
const APPLE_ORIGIN = "https://appleid.cdn-apple.com";

const GOOGLE_CLIENT = /^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$/;
const APPLE_SERVICES_ID = /^[A-Za-z0-9][A-Za-z0-9.-]{0,154}$/;

export type DeletePageConfig = { siteKey: string; google: string | null; apple: string | null };

/**
 * Null while the page cannot work: no valid Turnstile site key, or no provider whose web client id is set, well-formed
 * and also accepted as an audience (otherwise the server would refuse the provider's token).
 */
export function deletePageConfig(env: Pick<Env, "TURNSTILE_SITE_KEY" | "GOOGLE_AUDIENCES" | "APPLE_AUDIENCES"> & { GOOGLE_WEB_CLIENT_ID?: string; APPLE_WEB_SERVICES_ID?: string }): DeletePageConfig | null {
	if (!/^[0-9A-Za-z_-]+$/.test(env.TURNSTILE_SITE_KEY)) return null;
	const google = env.GOOGLE_WEB_CLIENT_ID && GOOGLE_CLIENT.test(env.GOOGLE_WEB_CLIENT_ID) && audiences(env.GOOGLE_AUDIENCES).includes(env.GOOGLE_WEB_CLIENT_ID) ? env.GOOGLE_WEB_CLIENT_ID : null;
	const apple = env.APPLE_WEB_SERVICES_ID && APPLE_SERVICES_ID.test(env.APPLE_WEB_SERVICES_ID) && audiences(env.APPLE_AUDIENCES).includes(env.APPLE_WEB_SERVICES_ID) ? env.APPLE_WEB_SERVICES_ID : null;
	return google || apple ? { siteKey: env.TURNSTILE_SITE_KEY, google, apple } : null;
}

export function deletePageCsp(config: DeletePageConfig): string {
	const script = ["'self'", TURNSTILE, ...(config.google ? [GSI_SCRIPT] : []), ...(config.apple ? [APPLE_ORIGIN] : [])];
	const frame = [TURNSTILE, ...(config.google ? [GSI] : [])];
	const connect = ["'self'", ...(config.google ? [GSI] : [])];
	const style = config.google ? [`style-src ${GSI_STYLE}`] : [];
	return [
		"default-src 'none'",
		`script-src ${script.join(" ")}`,
		`frame-src ${frame.join(" ")}`,
		`connect-src ${connect.join(" ")}`,
		...style,
		"base-uri 'none'",
		"form-action 'none'",
		"frame-ancestors 'none'",
	].join("; ");
}

export function deletePageHtml(config: DeletePageConfig, origin: string): string {
	const attr = (name: string, value: string | null) => (value ? ` data-${name}="${escapeHtml(value)}"` : "");
	return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Recipes: delete your account</title>
<script src="/account-delete.js" defer></script>
<script src="${TURNSTILE}/turnstile/v0/api.js" async defer></script>${config.google ? `\n<script src="${GSI_SCRIPT}" async defer></script>` : ""}${config.apple ? `\n<script src="${APPLE_SCRIPT}" defer></script>` : ""}
</head>
<body${attr("site-key", config.siteKey)}${attr("google-client", config.google)}${attr("apple-client", config.apple)}${attr("redirect", `${origin}${DELETE_PAGE}`)}>
<main>
<h1>Delete your Recipes account</h1>
<p lang="de"><strong>Recipes-Konto löschen.</strong> Melde dich mit demselben Apple- oder Google-Konto an wie in der App. Danach werden deine Rezepte, Bilder und Einstellungen endgültig gelöscht. Ein Abo im App Store oder bei Google Play läuft weiter, bis du es dort kündigst.</p>
<p>Sign in with the same Apple or Google account you use in the app. Your recipes, pictures and settings are then deleted for good. A subscription in the App Store or on Google Play keeps billing until you cancel it there.</p>
<p><label><input type="checkbox" id="confirm"> I understand / Ich habe verstanden</label></p>
<div class="cf-turnstile" data-sitekey="${escapeHtml(config.siteKey)}" data-callback="onDeleteTurnstile"></div>
<p>${config.apple ? '<button type="button" id="apple" disabled>Sign in with Apple and delete</button> ' : ""}</p>
${config.google ? '<div id="google"></div>' : ""}
<p id="status" role="status"></p>
</main>
</body>
</html>
`;
}
