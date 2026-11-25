// Optional secret, set only during a SERVER_KEY rotation (plan 4.2). Deliberately not in secrets.required.
interface Env {
	SERVER_KEY_PREVIOUS?: string;
}
declare namespace Cloudflare {
	interface Env {
		SERVER_KEY_PREVIOUS?: string;
	}
}
