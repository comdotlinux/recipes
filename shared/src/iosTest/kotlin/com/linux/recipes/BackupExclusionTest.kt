package com.linux.recipes

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

class BackupExclusionTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun cacheDirectoryIsExcludedFromBackup() {
        val support = NSURL.fileURLWithPath("${NSTemporaryDirectory()}backup-test-${Random.nextLong().toULong()}", isDirectory = true)
        assertTrue(excludeCacheFromBackup(support))

        val cacheDir = requireNotNull(support.URLByAppendingPathComponent(AppGraph.CACHE_DIR))
        val values = cacheDir.resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
        assertEquals(true, (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue)
    }
}
