package com.linux.recipes.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Generic-password Keychain items under [service], one per key. Items are readable after the first unlock
 * and never leave the device (`ThisDeviceOnly`: excluded from backups and device transfer).
 * Never throws: before the first unlock, or in a process without a Keychain (`errSecNotAvailable`,
 * `errSecMissingEntitlement`), every call fails; a value that cannot be read is absent, and a failed write or
 * delete returns false. Failures are logged with their OSStatus only.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecureStore(private val service: String) : SecureStore {
    override fun get(key: String): String? = memScoped {
        val query = itemQuery(key, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (!succeeded("read", status)) return@memScoped null
        val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
        val bytes = CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).convert()) ?: ByteArray(0)
        CFRelease(data)
        bytes.decodeToString()
    }

    override fun put(key: String, value: String): Boolean {
        if (!remove(key)) return false
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }
        val query = itemQuery(key, kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(query, null)
        CFRelease(query)
        CFRelease(data)
        return succeeded("add", status)
    }

    override fun remove(key: String): Boolean = succeeded("delete", delete(key))

    /** The raw OSStatus of deleting [key]; tests use it to tell a working Keychain from an unavailable one. */
    internal fun delete(key: String): Int {
        val query = itemQuery(key)
        val status = SecItemDelete(query)
        CFRelease(query)
        return status
    }

    /** Success or "no such item"; anything else is logged with its status code only. */
    private fun succeeded(operation: String, status: Int): Boolean {
        val ok = status == errSecSuccess || status == errSecItemNotFound
        if (!ok) println("RecipesKeychain: $operation failed: OSStatus $status")
        return ok
    }

    private fun itemQuery(key: String, vararg extra: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val serviceRef = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)
        val accountRef = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, serviceRef)
        CFDictionaryAddValue(dict, kSecAttrAccount, accountRef)
        extra.forEach { (k, v) -> CFDictionaryAddValue(dict, k, v) }
        // The dictionary retains its values.
        CFRelease(serviceRef)
        CFRelease(accountRef)
        return dict
    }
}
