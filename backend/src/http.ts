/** What the user's object answers; the Worker turns it into a Response without changing it. */
export type Result = { status: number; body?: unknown; bytes?: ArrayBuffer; headers?: Record<string, string> };

export const error = (status: number, code: string, message: string): Result => ({ status, body: { error: { code, message } } });

export function toResponse(r: Result): Response {
	if (r.bytes) return new Response(r.bytes, { status: r.status, headers: r.headers });
	if (r.body === undefined) return new Response(null, { status: r.status, headers: r.headers });
	return Response.json(r.body, { status: r.status, headers: r.headers });
}
