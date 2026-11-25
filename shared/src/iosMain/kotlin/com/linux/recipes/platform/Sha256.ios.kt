package com.linux.recipes.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(text: String): String {
    val input = text.encodeToByteArray()
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    // addressOf(0) needs at least one element; the length passed stays the real one.
    val pinnedInput = if (input.isEmpty()) ByteArray(1) else input
    pinnedInput.usePinned { source ->
        digest.usePinned { target ->
            CC_SHA256(source.addressOf(0), input.size.convert(), target.addressOf(0))
        }
    }
    return digest.joinToString("") { it.toString(16).padStart(2, '0') }
}
