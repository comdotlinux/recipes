import type { TestProvider } from "../api";

// Marker that CI greps for: it must appear in the local bundle and never in the production bundle (AC30).
export const TEST_IDP = "__TEST_IDP__";

/** An id token for provider "test": `__TEST_IDP__|<sub>|<nonce>`. Accepted only by the local entry. */
export const testIdToken = (sub: string, nonce: string) => `${TEST_IDP}|${sub}|${nonce}`;

export const verifyTestIdToken: TestProvider = async (idToken, nonce) => {
	const [marker, sub, claim, ...rest] = idToken.split("|");
	return marker === TEST_IDP && sub && claim === nonce && rest.length === 0 ? { sub } : null;
};
