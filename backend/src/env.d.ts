// Optional secret, set only during a SERVER_KEY rotation (plan 4.2). Deliberately not in secrets.required.
// Optional variables filled by the provisioning step once known (docs/api.md, Milestone M5, "App links"):
// ANDROID_CERT_SHA256 (comma-separated signing certificate fingerprints) and APPLE_TEAM_ID. Unset: the files answer 404.
interface Env {
	SERVER_KEY_PREVIOUS?: string;
	ANDROID_CERT_SHA256?: string;
	APPLE_TEAM_ID?: string;
}
declare namespace Cloudflare {
	interface Env {
		SERVER_KEY_PREVIOUS?: string;
		ANDROID_CERT_SHA256?: string;
		APPLE_TEAM_ID?: string;
	}
}
