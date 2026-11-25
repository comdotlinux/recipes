import { createAdmin } from "./admin/index";
import { createApi } from "./api";
import { dispatch } from "./worker";

export { UserDO } from "./user-do";

export const app = createApi();
const admin = createAdmin();

export default {
	fetch: (request, env, ctx) => dispatch(app, admin, request, env, ctx),
} satisfies ExportedHandler<Env>;
