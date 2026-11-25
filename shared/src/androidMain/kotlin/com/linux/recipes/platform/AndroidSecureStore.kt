package com.linux.recipes.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Each value is AES-256-GCM encrypted with a non-exportable Android Keystore key and stored as
 * `<dir>/<key>.bin` (12-byte IV followed by the ciphertext). Use a directory inside `noBackupFilesDir`.
 * A value that can no longer be decrypted (for example after the key was lost) reads as absent and is removed.
 */
class AndroidSecureStore(
    private val dir: File,
    private val secretKey: () -> SecretKey = ::keystoreKey,
) : SecureStore {
    override fun get(key: String): String? {
        val file = file(key)
        if (!file.exists()) return null
        val data = file.readBytes()
        if (data.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES).decodeToString()
        } catch (e: GeneralSecurityException) {
            discard(key, file, e)
        } catch (e: ProviderException) {
            discard(key, file, e)
        }
    }

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(data)
        check(tmp.renameTo(file(key))) { "could not store $key" }
    }

    override fun remove(key: String) {
        file(key).delete()
    }

    /** A value the Keystore can no longer decrypt (key gone after a data clear or restore) is removed: signed out. */
    private fun discard(key: String, file: File, cause: Exception): String? {
        Log.w(TAG, "discarding unreadable secure value '$key': ${cause::class.simpleName}")
        file.delete()
        return null
    }

    private fun file(key: String): File {
        require(KEY_NAME.matches(key)) { "invalid key name" }
        return File(dir, "$key.bin")
    }

    companion object {
        private const val TAG = "RecipesSecureStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val ALIAS = "recipes_secure_store"
        private val KEY_NAME = Regex("^[a-z_]{1,64}$")

        fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
