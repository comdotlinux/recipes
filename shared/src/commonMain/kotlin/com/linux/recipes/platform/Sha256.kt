package com.linux.recipes.platform

/**
 * Lowercase hex SHA-256 of the UTF-8 bytes of [text].
 * Nonce convention (docs/api.md): for Apple the app passes `sha256Hex(serverNonce)` to Apple, which echoes it
 * into the id token, and sends the unhashed server nonce to `POST /session`; the server compares the claim
 * with the SHA-256 of the nonce it issued. Google gets the server nonce unchanged.
 */
expect fun sha256Hex(text: String): String
