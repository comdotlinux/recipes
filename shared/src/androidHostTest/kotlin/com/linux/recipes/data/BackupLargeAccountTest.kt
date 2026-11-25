package com.linux.recipes.data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * AC27's 500 MB account on the JVM: 400 pictures of 1.5 MB (600 MB, about 800 MB as backup text, more than AC27's 500 MB) exported and
 * restored. The test JVM's heap is Gradle's default for test workers (512 MB), smaller than the pictures alone, so a
 * run that held the file or all pictures would fail with OutOfMemoryError; the assertion bounds the class's own buffers
 * to one picture. It does not run with a 128 MB heap: that needs a Gradle change to the test task.
 */
class BackupLargeAccountTest {
    @Test
    fun a600MbAccountStreamsThroughExportAndRestore() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        val peak = SyntheticBackup.run(pictures = 400)
        assertTrue(peak <= SyntheticBackup.BOUND, "peak $peak bytes")
        val heap = Runtime.getRuntime().maxMemory()
        assertTrue(heap < 400L * SyntheticBackup.PICTURE, "the heap ($heap bytes) must be smaller than the account for this test to mean anything")
    }
}
