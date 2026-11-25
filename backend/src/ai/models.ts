/**
 * Model ids, checked on developers.cloudflare.com on 2026-11-24 (docs/versions/backend.md).
 * Structuring needs JSON Mode: both are on the Workers AI JSON Mode list.
 * Vision: both pages state image understanding; neither is on the JSON Mode list, so their output is parsed and
 * validated. Image request shape and size limits are not documented there (UNVERIFIED, spike S3).
 */
export const STRUCTURE_MODELS = {
	primary: "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
	fallback: "@cf/meta/llama-3.1-8b-instruct",
} as const;

export const VISION_MODELS = {
	primary: "@cf/meta/llama-4-scout-17b-16e-instruct",
	fallback: "@cf/mistralai/mistral-small-3.1-24b-instruct",
} as const;
