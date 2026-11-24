import { Hono } from "hono";

export { UserDO } from "./user-do";

export const app = new Hono<{ Bindings: Env }>();

app.get("/v1/health", (c) => c.json({ status: "ok", version: 1 }));

export default app;
