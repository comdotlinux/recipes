import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
	plugins: [
		cloudflareTest({
			// Test entry = local entry plus the S11 spike object; env "local" has no account-bound bindings.
			main: "./test/worker.ts",
			wrangler: { configPath: "./wrangler.jsonc", environment: "local" },
			remoteBindings: false,
			miniflare: {
				durableObjects: { SPIKE_DO: { className: "SpikeDO", useSQLite: true } },
				// Test-only secret values; they protect nothing.
				bindings: {
					USER_ID_PEPPER: "test-pepper-not-a-secret",
					SERVER_KEY: "test-server-key-not-a-secret",
					// With a key the object uses the real RevenueCat client; tests intercept its fetch (test/api/helpers.ts).
					REVENUECAT_API_KEY: "test-revenuecat-key-not-a-secret",
					REVENUECAT_WEBHOOK_SECRET: "Bearer test-webhook-secret-not-a-secret",
				},
			},
		}),
	],
	test: {
		coverage: {
			provider: "istanbul",
			include: ["src/**"],
			reporter: ["text", "lcov"],
			thresholds: { lines: 85 },
		},
	},
});
