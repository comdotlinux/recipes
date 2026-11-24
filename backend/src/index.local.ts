// Local dev and test entry only (wrangler env "local"). The production entry never imports src/testing/.
import { createApi } from "./api";
import { verifyTestIdToken } from "./testing/test-idp";

export { UserDO } from "./user-do";

const api = createApi({ testProvider: verifyTestIdToken });

// Should this entry ever be deployed by mistake, it answers nothing outside the machine or the Android emulator's host alias.
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "10.0.2.2"]);

export default {
	fetch(request, env, ctx) {
		if (!LOCAL_HOSTS.has(new URL(request.url).hostname)) {
			return Response.json({ error: { code: "not_found", message: "local entry serves local hosts only" } }, { status: 404 });
		}
		return api.fetch(request, env, ctx);
	},
} satisfies ExportedHandler<Env>;
