package app.recipebox.contract

import app.recipebox.data.Health
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthContractTest {
    private val fixture = contractFixtures.getValue("health.json")

    @Test
    fun decodesAndReEncodesFixture() {
        val decoded = Json.decodeFromString<Health>(fixture)
        assertEquals(Health(status = "ok", version = 1), decoded)
        assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(Json.encodeToString(decoded)))
    }
}
