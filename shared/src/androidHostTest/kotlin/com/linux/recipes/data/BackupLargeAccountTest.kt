package com.linux.recipes.data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * AC27 on the JVM: a 500 MB account (334 pictures of 1.5 MB, about 668 MB as backup text) exported and restored.
 * Runs only in `:shared:backupMemoryTest`, whose heap is 128 MB (checked below): a run that held the file or all
 * pictures would end in OutOfMemoryError. The assertion also bounds the class's own buffers to one picture.
 */
class BackupLargeAccountTest {
    @Test
    fun a500MbAccountStreamsThroughExportAndRestoreIn128Mb() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        val heap = Runtime.getRuntime().maxMemory()
        assertTrue(heap <= 140L * 1024 * 1024, "the heap ($heap bytes) must be the task's 128 MB for this test to mean anything")
        val peak = SyntheticBackup.run(pictures = 334)
        assertTrue(peak <= SyntheticBackup.BOUND, "peak $peak bytes")
    }
}
