/**
 * Host routing shared by the production and the local entry: the admin host goes to the admin app (plan 4.12), and
 * admin paths on any other host answer 404, so the public host never serves an admin page (AC41).
 * Every answer passes through `secure()`: the one place that sets the security headers (plan 4.8 "Edge", AC29).
 */
import { HSTS } from "./web";

type App = { fetch: (request: Request, env: Env, ctx: ExecutionContext) => Response | Promise<Response> };

/** For HTML without its own policy (static files from public/): scripts and styles from this origin only. */
export const DEFAULT_HTML_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

/** HSTS and nosniff on everything; a CSP, a referrer policy and frame denial on every HTML answer that has none. */
export function secure(response: Response): Response {
	const out = new Response(response.body, response);
	out.headers.set("strict-transport-security", HSTS);
	out.headers.set("x-content-type-options", "nosniff");
	if (!out.headers.has("referrer-policy")) out.headers.set("referrer-policy", "no-referrer");
	if ((out.headers.get("content-type") ?? "").startsWith("text/html")) {
		if (!out.headers.has("content-security-policy")) out.headers.set("content-security-policy", DEFAULT_HTML_CSP);
		if (!out.headers.has("x-frame-options")) out.headers.set("x-frame-options", "DENY");
	}
	return out;
}

function route(api: App, admin: App, request: Request, env: Env, ctx: ExecutionContext): Response | Promise<Response> {
	const url = new URL(request.url);
	if (env.ADMIN_HOST && url.hostname === env.ADMIN_HOST) return admin.fetch(request, env, ctx);
	if (url.pathname === "/admin" || url.pathname.startsWith("/admin/")) {
		return Response.json({ error: { code: "not_found", message: "no such route" } }, { status: 404 });
	}
	return api.fetch(request, env, ctx);
}

export async function dispatch(api: App, admin: App, request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
	return secure(await route(api, admin, request, env, ctx));
}
