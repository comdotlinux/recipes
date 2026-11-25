package com.linux.recipes.platform

/**
 * Small secrets (the session token). Android: Keystore AES-GCM over a private file. iOS: Keychain.
 * Never throws for an unavailable store (an iPhone locked since boot, a process without a Keychain):
 * a value that cannot be read is absent, and [put] and [remove] return false when they did not happen.
 */
interface SecureStore {
    fun get(key: String): String?

    /** True when the value is stored. */
    fun put(key: String, value: String): Boolean

    /** True when no value is stored under [key] afterwards. */
    fun remove(key: String): Boolean
}
