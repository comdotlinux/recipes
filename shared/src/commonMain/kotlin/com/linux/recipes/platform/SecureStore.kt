package com.linux.recipes.platform

/** Small secrets (the session token). Android: Keystore AES-GCM over a private file. iOS: Keychain. */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}
