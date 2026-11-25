/**
 * The admin host (plan 4.12, AC41, AC42), served only when the request's host is ADMIN_HOST. Every request needs a
 * valid Cloudflare Access JWT from an allow-listed email; a non-GET request also needs the admin host's own Origin.
 * The admin reaches a user's object by the user id from a Support ID or from an opened share-report reference, and may
 * call exactly adminSummary and setSuspended there (plan 4.4, third entry point).
 */
import { Hono } from "hono";
import { AI_GATEWAY_LIMIT } from "../limits";
import { systemEvent } from "../metrics";
import { openReportReference } from "../share";
import { SUSPENSION_REASONS, type SuspensionReason, type UserDO, userNamespace } from "../user-do";
import { pageHeaders } from "../web";
import { verifyAccess } from "./access";
import { ADMIN_CSP, layout, overviewPage, reportsPage, subscriptionsPage, userPage, usersPage } from "./pages";
import { WINDOWS, activeSubscribers, activeUsersSql, dailySql, eventCountsSql, overview, reportsSql, runQuery, topAccountsSql } from "./queries";

type AdminCalls = Pick<UserDO, "adminSummary" | "setSuspended">;
const USER_ID = /^[A-Za-z0-9_-]{43}$/;
const CONFIRM = { suspend: "SUSPEND", unsuspend: "UNSUSPEND" } as const;

const adminObject = (env: Env, userId: string) => {
	const ns = userNamespace(env);
	return ns.get(ns.idFromName(userId)) as unknown as AdminCalls;
};

export function createAdmin() {
	const app = new Hono<{ Bindings: Env; Variables: { operator: string } }>();
	const html = (body: string, status = 200) => new Response(body, { status, headers: pageHeaders(ADMIN_CSP) });
	const denied = () => html(layout("Forbidden", "<p>Access denied.</p>"), 403);

	app.use("*", async (c, next) => {
		const operator = await verifyAccess(c.req.header("cf-access-jwt-assertion"), c.env, Date.now());
		if (!operator) {
			systemEvent(c.env, { event: "auth_failure", reason: "admin_access" });
			return denied();
		}
		if (c.req.method !== "GET" && c.req.method !== "HEAD" && c.req.header("origin") !== `https://${c.env.ADMIN_HOST}`) return denied();
		c.set("operator", operator);
		await next();
	});

	app.get("/admin/", async (c) => {
		const windows = await Promise.all(
			WINDOWS.map(async (days) => {
				const [rows, active] = await Promise.all([runQuery(c.env, eventCountsSql(c.env, days)), runQuery(c.env, activeUsersSql(c.env, days))]);
				return rows && active ? { days, o: overview(rows, Number(active[0]?.n ?? 0)) } : null;
			}),
		);
		const ok = windows.every((w) => w !== null) ? (windows as { days: (typeof WINDOWS)[number]; o: ReturnType<typeof overview> }[]) : null;
		return html(overviewPage(ok, AI_GATEWAY_LIMIT.requests));
	});

	app.get("/admin/subscriptions", async (c) => html(subscriptionsPage(await activeSubscribers(c.env), await runQuery(c.env, dailySql(c.env, "billing_webhook")))));

	app.get("/admin/users", async (c) => {
		const [created, requests, top] = await Promise.all([
			runQuery(c.env, dailySql(c.env, "account_created")),
			runQuery(c.env, dailySql(c.env, "request")),
			runQuery(c.env, topAccountsSql(c.env)),
		]);
		return html(usersPage(created, requests, top));
	});

	app.get("/admin/reports", async (c) => html(reportsPage(await runQuery(c.env, reportsSql(c.env)))));

	app.get("/admin/user", async (c) => {
		const ref = c.req.query("ref");
		const userId = ref ? await openReportReference(ref, c.env, Date.now()) : (c.req.query("id") ?? "").trim();
		if (!userId || !USER_ID.test(userId)) return html(layout("Account", "<p>Not a Support ID, or the report reference is older than 30 days.</p>"), 404);
		const summary = await adminObject(c.env, userId).adminSummary();
		if (summary.status !== 200) return html(layout("Account", "<p>No account with this Support ID.</p>"), 404);
		return html(userPage(userId, summary.body as Record<string, unknown>, SUSPENSION_REASONS));
	});

	app.post("/admin/user/suspend", async (c) => {
		const form = await c.req.parseBody();
		const userId = String(form.id ?? "");
		const reason = String(form.reason ?? "");
		const lift = reason === "none";
		if (!USER_ID.test(userId) || (!lift && !(SUSPENSION_REASONS as readonly string[]).includes(reason))) return html(layout("Account", "<p>Invalid request.</p>"), 400);
		if (form.confirm !== (lift ? CONFIRM.unsuspend : CONFIRM.suspend)) return html(layout("Account", `<p>Type ${lift ? CONFIRM.unsuspend : CONFIRM.suspend} to confirm.</p>`), 400);
		const result = await adminObject(c.env, userId).setSuspended(lift ? null : (reason as SuspensionReason), c.get("operator"));
		if (result.status !== 200) return html(layout("Account", "<p>No account with this Support ID.</p>"), 404);
		return c.redirect(`/admin/user?id=${encodeURIComponent(userId)}`, 303);
	});

	app.notFound(() => html(layout("Not found", "<p>No such page.</p>"), 404));
	return app;
}
