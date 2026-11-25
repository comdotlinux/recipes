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
import { MAX_TEXT_CHARS, validateUrl } from "./ai/fetch-url";
import { ALLOWED_MIME, MAX_IMAGE_BYTES, sniffImage } from "./images";
import { TRANSLATION_LANGUAGES } from "./ai/translate";
import { systemEvent } from "./metrics";
import { ID_PATTERN, MAX_RECIPE_BYTES, RecipeInput, isId } from "./recipes";
import { SHARE_ID, openShareToken } from "./share";
import { type UserDO, userNamespace } from "./user-do";

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

/** Android application id and iOS bundle id of the app (owner, 2026-11-25). */
const APP_ID = "com.linux.recipes";
const CERT_FINGERPRINT = /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/;
const LANDING_CSP = "default-src 'none'; script-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

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
type UserObject = Pick<
	UserDO,
	| "createSession"
	| "deleteSession"
	| "me"
	| "listRecipes"
	| "putRecipe"
	| "deleteRecipe"
	| "putImage"
	| "getImage"
	| "setConsent"
	| "clearConsent"
	| "startImport"
	| "getJob"
	| "startTranslation"
	| "createShare"
	| "deleteShare"
	| "previewShare"
	| "acceptShare"
	| "reportShare"
	| "pdf"
	| "report"
	| "refreshFromClient"
	| "entitlementChanged"
	| "deleteAccount"
>;

const MAX_WEBHOOK_BODY = 64 * 1024;
/** Our user ids (auth deriveUserId): anything else in a webhook is not one of our accounts. */
const USER_ID = /^[A-Za-z0-9_-]{43}$/;
/** Event types named in RevenueCat's documentation (docs/versions/backend.md); only used to label the metric. */
const WEBHOOK_TYPES = new Set([
	"TEST",
	"INITIAL_PURCHASE",
	"RENEWAL",
	"CANCELLATION",
	"UNCANCELLATION",
	"NON_RENEWING_PURCHASE",
	"SUBSCRIPTION_PAUSED",
	"EXPIRATION",
	"BILLING_ISSUE",
	"PRODUCT_CHANGE",
	"TRANSFER",
	"SUBSCRIPTION_EXTENDED",
	"REFUND_REVERSED",
	"TEMPORARY_ENTITLEMENT_GRANT",
	"INVOICE_ISSUANCE",
]);
const AppUserIds = z.array(z.string().max(512)).max(5).nullish();
/** Only the fields that name accounts; the rest of the payload is not trusted and not read (plan 4.6). */
const WebhookBody = z.object({
	event: z.object({ type: z.string().min(1).max(64), app_user_id: z.string().max(512).nullish(), transferred_from: AppUserIds, transferred_to: AppUserIds }),
});

/** Constant time: both sides are hashed first, so the comparison never depends on where they differ or on their lengths. */
async function webhookAuthorized(header: string | undefined, secret: string | undefined): Promise<boolean> {
	if (!secret || header === undefined) return false;
	const digest = (s: string) => crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
	const [a, b] = await Promise.all([digest(header), digest(secret)]);
	return crypto.subtle.timingSafeEqual(a, b);
}

/** The only way to an object: the user id comes from the authenticated token, never from the client. */
const userObject = (env: Env, userId: string): UserObject => {
	const ns = userNamespace(env);
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

	// docs/api.md "Milestone M5": a share link opened without the app, `/s#<token>`. The token is in the fragment, which
	// browsers never send, so it reaches neither this Worker nor any log. The page is static and shows nothing of the recipe.
	app.get("/s", async (c) => {
		const page = await c.env.ASSETS.fetch(new URL("/s/", c.req.url));
		return new Response(page.body, {
			status: page.ok ? 200 : 404,
			headers: {
				"content-type": "text/html; charset=utf-8",
				"content-security-policy": LANDING_CSP,
				"x-content-type-options": "nosniff",
				"referrer-policy": "no-referrer",
				"cache-control": "no-store",
			},
		});
	});

	// App Links and Universal Links for share links. The values come from the environment at request time and are filled
	// by the provisioning step; until then (or when malformed) the files answer 404.
	app.get("/.well-known/assetlinks.json", (c) => {
		const prints = (c.env.ANDROID_CERT_SHA256 ?? "").split(",").map((p) => p.trim().toUpperCase());
		if (!prints.every((p) => CERT_FINGERPRINT.test(p))) return fail(c, 404, "not_found", "ANDROID_CERT_SHA256 is not set");
		return c.json([
			{ relation: ["delegate_permission/common.handle_all_urls"], target: { namespace: "android_app", package_name: APP_ID, sha256_cert_fingerprints: prints } },
		]);
	});
	app.get("/.well-known/apple-app-site-association", (c) => {
		const team = c.env.APPLE_TEAM_ID ?? "";
		if (!/^[A-Z0-9]{10}$/.test(team)) return fail(c, 404, "not_found", "APPLE_TEAM_ID is not set");
		return c.json({ applinks: { details: [{ appIDs: [`${team}.${APP_ID}`], components: [{ "/": "/s", comment: "share links; the token is in the fragment" }] }] } });
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
		// The code is exchanged later for the refresh token that account deletion revokes (plan 4.7).
		const clientId = (subject as { aud?: string }).aud;
		const apple = parsed.provider === "apple" && parsed.appleAuthCode && clientId ? { code: parsed.appleAuthCode, clientId } : undefined;
		const result = await userObject(c.env, userId).createSession({ nonceId: nonce.id, nonceExpiresAt: nonce.expiresAt, secretHash, platform: parsed.provider, apple });
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

	// docs/api.md "Milestone M6": starts erasure; every other call answers 401 from now on.
	app.delete(
		"/v1/account",
		authed((o, a) => o.deleteAccount(a.secretHash)),
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

	/** Size-checked JSON body, or the error to answer with. */
	const readJson = async (c: C, max: number): Promise<{ value: unknown } | { fail: Result }> => {
		const size = bodyCheck(c, max);
		if (size === "length_required") return { fail: LENGTH_REQUIRED };
		if (size === "too_long") return { fail: error(413, "text_too_large", "body too large") };
		try {
			return { value: JSON.parse(await c.req.text()) };
		} catch {
			return { fail: error(400, "invalid_body", "body is not JSON") };
		}
	};

	app.put(
		"/v1/me/consent",
		authed(async (o, a, c) => {
			const body = await readJson(c, 1024);
			if ("fail" in body) return body.fail;
			const parsed = z.strictObject({ version: z.number().int().min(1) }).safeParse(body.value);
			return parsed.success ? o.setConsent(a.secretHash, parsed.data.version) : error(400, "invalid_body", "send { version }");
		}),
	);

	app.delete(
		"/v1/me/consent",
		authed((o, a) => o.clearConsent(a.secretHash)),
	);

	const ImportBody = z.discriminatedUnion("kind", [
		z.strictObject({ kind: z.literal("url"), url: z.string().min(1) }),
		z.strictObject({ kind: z.literal("text"), text: z.string().min(1) }),
	]);
	// JSON overhead plus 20,000 characters of up to 4 UTF-8 bytes each.
	const MAX_IMPORT_JSON = 4 * MAX_TEXT_CHARS + 4096;

	app.post(
		"/v1/imports",
		authed(async (o, a, c) => {
			const key = c.req.header("idempotency-key") ?? "";
			if (!isId(key)) return error(400, "invalid_id", "Idempotency-Key must be a lowercase UUID");
			const type = (c.req.header("content-type") ?? "").split(";")[0].trim().toLowerCase();
			if (type === "image/jpeg") {
				const size = bodyCheck(c, MAX_IMAGE_BYTES);
				if (size === "length_required") return LENGTH_REQUIRED;
				if (size === "too_long") return error(413, "image_too_large", "photo over 1.5 MB");
				const bytes = await c.req.arrayBuffer();
				if (bytes.byteLength > MAX_IMAGE_BYTES) return error(413, "image_too_large", "photo over 1.5 MB");
				if (sniffImage(new Uint8Array(bytes)) !== "image/jpeg") return error(415, "unsupported_media_type", "photo must be a JPEG");
				return o.startImport(a.secretHash, key, { kind: "photo", bytes });
			}
			if (type !== "application/json") return error(415, "unsupported_media_type", "send JSON or image/jpeg");
			const body = await readJson(c, MAX_IMPORT_JSON);
			if ("fail" in body) return body.fail;
			const parsed = ImportBody.safeParse(body.value);
			if (!parsed.success) return error(400, "invalid_body", "send { kind: url, url } or { kind: text, text }");
			if (parsed.data.kind === "text") {
				if (parsed.data.text.length > MAX_TEXT_CHARS) return error(413, "text_too_large", "text over 20,000 characters");
				return o.startImport(a.secretHash, key, parsed.data);
			}
			const check = validateUrl(parsed.data.url);
			if (!check.ok) return error(400, "invalid_url", `url rejected: ${check.reason}`);
			return o.startImport(a.secretHash, key, { kind: "url", url: check.url.toString() });
		}),
	);

	app.get(
		"/v1/jobs/:id",
		authed(async (o, a, c) => (ids(c, "id") ? o.getJob(a.secretHash, c.req.param("id")!) : error(400, "invalid_id", "id must be a lowercase UUID"))),
	);

	// docs/api.md "Milestone M4": a translation job, polled through GET /jobs/:id like an import.
	app.post(
		"/v1/recipes/:id/translations",
		authed(async (o, a, c) => {
			if (!ids(c, "id")) return error(400, "invalid_id", "id must be a lowercase UUID");
			const key = c.req.header("idempotency-key") ?? "";
			if (!isId(key)) return error(400, "invalid_id", "Idempotency-Key must be a lowercase UUID");
			const body = await readJson(c, 1024);
			if ("fail" in body) return body.fail;
			const parsed = z.strictObject({ language: z.enum(TRANSLATION_LANGUAGES) }).safeParse(body.value);
			if (!parsed.success) return error(400, "invalid_body", `send { language } with one of ${TRANSLATION_LANGUAGES.join(", ")}`);
			return o.startTranslation(a.secretHash, key, c.req.param("id")!, parsed.data.language);
		}),
	);

	/**
	 * The share token from the body (never a URL: proxies and request logs keep URLs), opened here: one that does not
	 * authenticate is a 404 before any object is used.
	 */
	const sharedBy = async (c: C, token: string) => {
		const opened = await openShareToken(token, c.env);
		if (!opened) systemEvent(c.env, { event: "auth_failure", reason: "share_token" });
		return opened;
	};
	const SHARE_NOT_FOUND = error(404, "not_found", "no such share");

	// docs/api.md "Milestone M5": sharing a recipe by link.
	app.post(
		"/v1/recipes/:id/shares",
		authed(async (o, a, c) => {
			if (!ids(c, "id")) return error(400, "invalid_id", "id must be a lowercase UUID");
			const result = await o.createShare(a.secretHash, c.req.param("id")!);
			if (result.status !== 201) return result;
			const body = result.body as { token: string };
			return { ...result, body: { ...body, url: `${new URL(c.req.url).origin}/s#${body.token}` } };
		}),
	);

	app.delete(
		"/v1/shares/:shareId",
		authed(async (o, a, c) => (SHARE_ID.test(c.req.param("shareId")!) ? o.deleteShare(a.secretHash, c.req.param("shareId")!) : error(400, "invalid_id", "not a share id"))),
	);

	/** `{ shareToken }` from the body, opened; or the answer to give. */
	const shareFromBody = async (c: C) => {
		const body = await readJson(c, 1024);
		if ("fail" in body) return body.fail;
		const parsed = z.strictObject({ shareToken: z.string().max(200) }).safeParse(body.value);
		if (!parsed.success) return error(400, "invalid_body", "send { shareToken }");
		return (await sharedBy(c, parsed.data.shareToken)) ?? SHARE_NOT_FOUND;
	};

	app.post(
		"/v1/shares/open",
		authed(async (o, a, c) => {
			const share = await shareFromBody(c);
			return "ownerId" in share ? o.previewShare(a.secretHash, share.ownerId, share.shareId) : share;
		}),
	);

	app.post(
		"/v1/shares/accept",
		authed(async (o, a, c) => {
			const share = await shareFromBody(c);
			return "ownerId" in share ? o.acceptShare(a.secretHash, share.ownerId, share.shareId) : share;
		}),
	);

	// docs/api.md "Milestone M5": the recipe as a PDF.
	app.get(
		"/v1/recipes/:id/pdf",
		authed(async (o, a, c) => (ids(c, "id") ? o.pdf(a.secretHash, c.req.param("id")!) : error(400, "invalid_id", "id must be a lowercase UUID"))),
	);

	const Reason = z.enum(["wrong_content", "offensive", "other"]);
	const ReportBody = z.discriminatedUnion("kind", [
		z.strictObject({ kind: z.enum(["import_result", "translation"]), jobId: z.string().regex(ID_PATTERN), reason: Reason, note: z.string().max(1000).optional() }),
		z.strictObject({ kind: z.literal("share"), shareToken: z.string().max(200), reason: Reason, note: z.string().max(1000).optional() }),
	]);

	app.post(
		"/v1/reports",
		authed(async (o, a, c) => {
			const body = await readJson(c, 8192);
			if ("fail" in body) return body.fail;
			const parsed = ReportBody.safeParse(body.value);
			if (!parsed.success) return error(400, "invalid_body", "body does not match Report");
			const r = parsed.data;
			if (r.kind !== "share") return o.report(a.secretHash, { kind: r.kind, jobId: r.jobId, reason: r.reason, note: r.note ?? "" });
			const share = await sharedBy(c, r.shareToken);
			if (!share) return SHARE_NOT_FOUND;
			return o.reportShare(a.secretHash, share.ownerId, share.shareId, r.reason, r.note ?? "");
		}),
	);

	// docs/api.md "Milestone M3": the app calls this after a purchase or a restore.
	app.post(
		"/v1/entitlement/refresh",
		authed((o, a) => o.refreshFromClient(a.secretHash)),
	);

	// RevenueCat webhook: outside the bearer auth, authorised by the header value set in the RevenueCat dashboard.
	app.post("/v1/webhooks/revenuecat", async (c) => {
		if (!(await webhookAuthorized(c.req.header("authorization"), c.env.REVENUECAT_WEBHOOK_SECRET))) {
			systemEvent(c.env, { event: "auth_failure", reason: "webhook_unauthorized" });
			if (await limitedByIp(c, "webhook")) return fail(c, 429, "rate_limited", "too many failed authentications");
			return fail(c, 401, "unauthenticated", "wrong webhook authorization");
		}
		// One key for the route: bounds the objects and RevenueCat calls one leaked secret can cause; RevenueCat retries a 429.
		if (!(await c.env.RL_WEBHOOK.limit({ key: "revenuecat" })).success) {
			systemEvent(c.env, { event: "rate_limited", route: "webhook" });
			return fail(c, 429, "rate_limited", "too many webhook requests");
		}
		const size = bodyCheck(c, MAX_WEBHOOK_BODY);
		if (size === "length_required") return reply(c, LENGTH_REQUIRED);
		if (size === "too_long") return fail(c, 413, "body_too_large", "webhook body over 64 KB");
		let event: z.infer<typeof WebhookBody>["event"];
		try {
			const text = await c.req.text();
			if (byteLength(text) > MAX_WEBHOOK_BODY) return fail(c, 413, "body_too_large", "webhook body over 64 KB");
			event = WebhookBody.parse(JSON.parse(text)).event;
		} catch {
			return fail(c, 400, "invalid_body", "not a RevenueCat event");
		}
		systemEvent(c.env, { event: "billing_webhook", type: WEBHOOK_TYPES.has(event.type) ? event.type : "OTHER" });
		// A transfer refreshes both sides; every other event its own customer. Ids of other shapes are ignored.
		const ids = new Set([event.app_user_id, ...(event.transferred_from ?? []), ...(event.transferred_to ?? [])].filter((id) => id && USER_ID.test(id)));
		await Promise.all([...ids].map((id) => userObject(c.env, id!).entitlementChanged()));
		return c.json({ received: true });
	});

	app.notFound((c) => fail(c, 404, "not_found", "no such route"));

	return app;
}
