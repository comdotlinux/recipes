import { type Context, Hono } from "hono";
import { z } from "zod";
import {
	type Provider,
	deriveUserId,
	issueNonce,
	newSessionToken,
	parseSessionToken,
	verifyIdToken,
	verifyNonce,
	verifyTurnstile,
} from "./auth/index";
import { type Result, error, toResponse } from "./http";
import { ALLOWED_MIME, MAX_IMAGE_BYTES, sniffImage } from "./images";
import { systemEvent } from "./metrics";
import { MAX_RECIPE_BYTES, RecipeInput, isId } from "./recipes";
import type { UserDO } from "./user-do";

export type TestProvider = (idToken: string, nonce: string) => Promise<{ sub: string } | null>;

type C = Context<{ Bindings: Env }>;

const MAX_SESSION_BODY = 16 * 1024;

const TURNSTILE_CSP =
	"default-src 'none'; script-src 'self' https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const turnstilePage = (siteKey: string) => `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Recipes</title>
<script src="/turnstile.js"></script>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
</head>
<body>
<div class="cf-turnstile" data-sitekey="${siteKey}" data-callback="onTurnstileToken"></div>
</body>
</html>
`;

const reply = (c: C, r: Result) => {
	c.res = toResponse(r);
	return c.res;
};
const fail = (c: C, status: number, code: string, message: string) => reply(c, error(status, code, message));

const byteLength = (s: string) => new TextEncoder().encode(s).byteLength;
/** Checked before any byte is read: a body without Content-Length (chunked) is refused instead of buffered. */
const bodyCheck = (c: C, max: number): "length_required" | "too_long" | null => {
	const length = c.req.header("content-length");
	if (length === undefined || !/^\d{1,15}$/.test(length)) return "length_required";
	return Number(length) > max ? "too_long" : null;
};
const LENGTH_REQUIRED = error(411, "length_required", "send Content-Length");

// The RPC stub types map `unknown` bodies to never; every method returns a structured-clonable Result, so use the class types.
type UserObject = Pick<UserDO, "createSession" | "deleteSession" | "me" | "listRecipes" | "putRecipe" | "deleteRecipe" | "putImage" | "getImage">;

/** The only way to an object: the user id comes from the authenticated token, never from the client. */
const userObject = (env: Env, userId: string): UserObject => {
	const ns = env.DO_JURISDICTION ? env.USER_DO.jurisdiction(env.DO_JURISDICTION) : env.USER_DO;
	return ns.get(ns.idFromName(userId)) as unknown as UserObject;
};

/** `testProvider` exists only in the local entry (src/index.local.ts); production passes nothing. */
export function createApi(options: { testProvider?: TestProvider } = {}) {
	const app = new Hono<{ Bindings: Env }>();
	const providers = options.testProvider ? (["apple", "google", "test"] as const) : (["apple", "google"] as const);
	const SessionRequest = z.strictObject({
		provider: z.enum(providers),
		idToken: z.string().min(1).max(8192),
		nonce: z.string().min(1).max(512),
		turnstileToken: z.string().min(1).max(4096),
		ageConfirmed: z.boolean(),
		appleAuthCode: z.string().min(1).max(4096).optional(),
	});

	const limitedByIp = async (c: C, route: string) => {
		const { success } = await c.env.RL_IP.limit({ key: `${route}:${c.req.header("cf-connecting-ip") ?? "unknown"}` });
		if (!success) systemEvent(c.env, { event: "rate_limited", route });
		return !success;
	};

	/**
	 * Authenticated route: token parse here, session check inside the user's object, which also applies the per-user
	 * write limit only after it accepted the session. Failed authentication counts against the caller's IP instead,
	 * so knowing someone's user id does not let anyone use up that user's limit.
	 */
	const authed =
		(op: (o: UserObject, auth: { userId: string; secretHash: string }, c: C) => Promise<Result>) =>
		async (c: C) => {
			const header = c.req.header("authorization");
			// The tag is checked here, so a forged token never reaches (or creates) a Durable Object, and no body is read.
			const auth = header?.startsWith("Bearer ") ? await parseSessionToken(header.slice(7), c.env) : null;
			const result = auth ? await op(userObject(c.env, auth.userId), auth, c) : error(401, "unauthenticated", "missing, malformed or forged token");
			if (result.status === 401 && (await limitedByIp(c, "auth"))) return fail(c, 429, "rate_limited", "too many failed authentications");
			return reply(c, result);
		};

	const ids = (c: C, ...names: string[]) => names.every((n) => isId(c.req.param(n) ?? ""));

	app.get("/v1/health", (c) => c.json({ status: "ok", version: 1 }));

	// docs/api.md "Turnstile page": the widget with this environment's site key; /turnstile.js hands the token to the app.
	app.get("/turnstile.html", (c) => {
		const siteKey = c.env.TURNSTILE_SITE_KEY;
		if (!/^[0-9A-Za-z_-]+$/.test(siteKey)) return fail(c, 503, "not_configured", "TURNSTILE_SITE_KEY is not set");
		c.header("content-security-policy", TURNSTILE_CSP);
		c.header("x-content-type-options", "nosniff");
		return c.html(turnstilePage(siteKey));
	});

	app.post("/v1/session/nonce", async (c) => {
		if (await limitedByIp(c, "nonce")) return fail(c, 429, "rate_limited", "too many requests");
		return c.json({ nonce: await issueNonce(c.env, Date.now()) });
	});

	app.post("/v1/session", async (c) => {
		if (await limitedByIp(c, "session")) return fail(c, 429, "rate_limited", "too many requests");
		const rejected = (status: number, code: string) => {
			systemEvent(c.env, { event: "auth_failure", reason: code });
			return fail(c, status, code, `session refused: ${code}`);
		};
		const size = bodyCheck(c, MAX_SESSION_BODY);
		if (size === "length_required") return reply(c, LENGTH_REQUIRED);
		if (size === "too_long") return rejected(400, "invalid_body");
		const text = await c.req.text();
		let parsed: z.infer<typeof SessionRequest>;
		try {
			if (byteLength(text) > MAX_SESSION_BODY) throw new Error("too large");
			parsed = SessionRequest.parse(JSON.parse(text));
		} catch {
			return rejected(400, "invalid_body");
		}
		if (!parsed.ageConfirmed) return rejected(400, "age_confirmation_required");
		if (!(await verifyTurnstile(parsed.turnstileToken, c.req.header("cf-connecting-ip") ?? null, c.env))) return rejected(401, "invalid_turnstile");
		const now = Date.now();
		const nonce = await verifyNonce(parsed.nonce, c.env, now);
		if (!nonce) return rejected(401, "invalid_nonce");
		const subject =
			parsed.provider === "test"
				? await options.testProvider!(parsed.idToken, parsed.nonce)
				: await verifyIdToken(parsed.provider as Provider, parsed.idToken, parsed.nonce, c.env, now);
		if (subject === "unavailable") {
			// Its own event, so a provider outage does not look like a wave of failed sign-ins.
			systemEvent(c.env, { event: "provider_unavailable", provider: parsed.provider });
			return fail(c, 503, "provider_unavailable", "the provider's signing keys could not be fetched");
		}
		if (!subject) return rejected(401, "invalid_token");
		const userId = await deriveUserId(parsed.provider, subject.sub, c.env);
		const { token, secretHash } = await newSessionToken(userId, c.env);
		const result = await userObject(c.env, userId).createSession({ nonceId: nonce.id, nonceExpiresAt: nonce.expiresAt, secretHash, platform: parsed.provider });
		if (result.status !== 200) {
			systemEvent(c.env, { event: "auth_failure", reason: (result.body as { error: { code: string } }).error.code });
			return reply(c, result);
		}
		return c.json({ token, userId, expiresAt: (result.body as { expiresAt: string }).expiresAt });
	});

	app.delete(
		"/v1/session",
		authed((o, a) => o.deleteSession(a.secretHash)),
	);

	app.get(
		"/v1/me",
		authed((o, a) => o.me(a.secretHash, a.userId)),
	);

	app.get(
		"/v1/recipes",
		authed(async (o, a, c) => {
			const since = c.req.query("since") ?? "0";
			if (!/^\d{1,15}$/.test(since)) return error(400, "invalid_body", "since must be a non-negative integer");
			return o.listRecipes(a.secretHash, Number(since));
		}),
	);

	app.put(
		"/v1/recipes/:id",
		authed(async (o, a, c) => {
			if (!ids(c, "id")) return error(400, "invalid_id", "id must be a lowercase UUID");
			const size = bodyCheck(c, MAX_RECIPE_BYTES);
			if (size === "length_required") return LENGTH_REQUIRED;
			if (size === "too_long") return error(413, "recipe_too_large", "recipe over 64 KB");
			const ifNoneMatch = c.req.header("if-none-match");
			const ifMatch = c.req.header("if-match");
			if (ifNoneMatch === undefined && ifMatch === undefined) return error(428, "precondition_required", "send If-None-Match: * or If-Match");
			const text = await c.req.text();
			if (byteLength(text) > MAX_RECIPE_BYTES) return error(413, "recipe_too_large", "recipe over 64 KB");
			let input: RecipeInput;
			try {
				input = RecipeInput.parse(JSON.parse(text));
			} catch {
				return error(400, "invalid_body", "body does not match RecipeInput");
			}
			if (input.id !== c.req.param("id")) return error(400, "invalid_body", "body id differs from path id");
			if (ifNoneMatch !== undefined) {
				if (ifNoneMatch.trim() !== "*") return error(412, "precondition_failed", "If-None-Match must be *");
				return o.putRecipe(a.secretHash, input, { create: true });
			}
			const rev = /^"(\d{1,15})"$/.exec(ifMatch!.trim());
			return o.putRecipe(a.secretHash, input, { ifMatch: rev ? Number(rev[1]) : null });
		}),
	);

	app.delete(
		"/v1/recipes/:id",
		authed(async (o, a, c) => (ids(c, "id") ? o.deleteRecipe(a.secretHash, c.req.param("id")!) : error(400, "invalid_id", "id must be a lowercase UUID"))),
	);

	app.put(
		"/v1/recipes/:id/images/:imageId",
		authed(async (o, a, c) => {
			if (!ids(c, "id", "imageId")) return error(400, "invalid_id", "ids must be lowercase UUIDs");
			const declared = (c.req.header("content-type") ?? "").split(";")[0].trim().toLowerCase();
			if (!(ALLOWED_MIME as readonly string[]).includes(declared)) return error(415, "unsupported_media_type", "use image/jpeg, image/png or image/webp");
			const size = bodyCheck(c, MAX_IMAGE_BYTES);
			if (size === "length_required") return LENGTH_REQUIRED;
			if (size === "too_long") return error(413, "image_too_large", "image over 1.5 MB");
			const bytes = await c.req.arrayBuffer();
			if (bytes.byteLength > MAX_IMAGE_BYTES) return error(413, "image_too_large", "image over 1.5 MB");
			if (sniffImage(new Uint8Array(bytes)) !== declared) return error(415, "unsupported_media_type", "bytes do not match the declared type");
			return o.putImage(a.secretHash, c.req.param("id")!, c.req.param("imageId")!, declared, bytes);
		}),
	);

	app.get(
		"/v1/images/:imageId",
		authed(async (o, a, c) => (ids(c, "imageId") ? o.getImage(a.secretHash, c.req.param("imageId")!) : error(400, "invalid_id", "id must be a lowercase UUID"))),
	);

	app.notFound((c) => fail(c, 404, "not_found", "no such route"));

	return app;
}
