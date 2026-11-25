package com.linux.recipes.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The server rejects any other notice version, so the client constant must follow backend/src/ai/gateway.ts. */
class ConsentVersionTest {
    @Test
    fun consentVersionMatchesTheBackend() {
        // Host tests run in the shared module directory.
        val gateway = File("../backend/src/ai/gateway.ts").readText()
        val version = Regex("export const AI_NOTICE_VERSION = (\\d+);").find(gateway)!!.groupValues[1].toInt()
        assertEquals(version, DefaultConsentRepository.AI_NOTICE_VERSION)
    }
}
