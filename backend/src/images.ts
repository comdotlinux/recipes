export const MAX_IMAGE_BYTES = 1_572_864;
export const FREE_CAP_BYTES = 52_428_800;
export const SUBSCRIBER_CAP_BYTES = 524_288_000;
export const ALLOWED_MIME = ["image/jpeg", "image/png", "image/webp"] as const;

const startsWith = (b: Uint8Array, sig: number[], offset = 0) => sig.every((v, i) => b[offset + i] === v);

/** The type the bytes really are, or null when they are none of the allowed types. */
export function sniffImage(b: Uint8Array): (typeof ALLOWED_MIME)[number] | null {
	if (startsWith(b, [0xff, 0xd8, 0xff])) return "image/jpeg";
	if (startsWith(b, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return "image/png";
	if (startsWith(b, [0x52, 0x49, 0x46, 0x46]) && startsWith(b, [0x57, 0x45, 0x42, 0x50], 8)) return "image/webp";
	return null;
}
