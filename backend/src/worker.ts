/**
 * Host routing shared by the production and the local entry: the admin host goes to the admin app (plan 4.12), and
 * admin paths on any other host answer 404, so the public host never serves an admin page (AC41).
 */
type App = { fetch: (request: Request, env: Env, ctx: ExecutionContext) => Response | Promise<Response> };

export function dispatch(api: App, admin: App, request: Request, env: Env, ctx: ExecutionContext): Response | Promise<Response> {
	const url = new URL(request.url);
	if (env.ADMIN_HOST && url.hostname === env.ADMIN_HOST) return admin.fetch(request, env, ctx);
	if (url.pathname === "/admin" || url.pathname.startsWith("/admin/")) {
		return Response.json({ error: { code: "not_found", message: "no such route" } }, { status: 404 });
	}
	return api.fetch(request, env, ctx);
}
