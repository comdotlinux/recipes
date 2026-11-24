// Local dev and test entry only (wrangler env "local"). The production entry never imports src/testing/.
import { Hono } from "hono";
import { app } from "./index";
import { testIdp } from "./testing/test-idp";

export { UserDO } from "./index";

// A separate app, so the production app object stays without the test route.
export default new Hono<{ Bindings: Env }>().route("/", testIdp).route("/", app);
