import { Hono } from "hono";

// Marker that CI greps for: it must appear in the local bundle and never in the production bundle (AC30).
export const TEST_IDP = "__TEST_IDP__";

export const testIdp = new Hono<{ Bindings: Env }>();

testIdp.get("/v1/test/idp", (c) => c.json({ issuer: TEST_IDP }));
