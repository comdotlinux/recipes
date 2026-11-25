// Optional secret, set only during a SERVER_KEY rotation (plan 4.2). Deliberately not in secrets.required.
// Optional variables filled by the provisioning step once known (docs/api.md, Milestone M5, "App links"):
// ANDROID_CERT_SHA256 (comma-separated signing certificate fingerprints) and APPLE_TEAM_ID. Unset: the files answer 404.
// Web deletion (docs/api.md, Milestone M6): GOOGLE_WEB_CLIENT_ID (a Google "Web application" client id) and
// APPLE_WEB_SERVICES_ID (the Sign in with Apple Services ID); each must also be listed in GOOGLE_AUDIENCES / APPLE_AUDIENCES.
// While neither is set, GET /account/delete answers 404.
interface Env {
	SERVER_KEY_PREVIOUS?: string;
	ANDROID_CERT_SHA256?: string;
	APPLE_TEAM_ID?: string;
	GOOGLE_WEB_CLIENT_ID?: string;
	APPLE_WEB_SERVICES_ID?: string;
}
declare namespace Cloudflare {
	interface Env {
		SERVER_KEY_PREVIOUS?: string;
		ANDROID_CERT_SHA256?: string;
		APPLE_TEAM_ID?: string;
		GOOGLE_WEB_CLIENT_ID?: string;
		APPLE_WEB_SERVICES_ID?: string;
	}
}
