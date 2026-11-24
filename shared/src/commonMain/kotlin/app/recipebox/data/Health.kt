package app.recipebox.data

import kotlinx.serialization.Serializable

/** Body of `GET /v1/health`; shape fixed by contract/fixtures/health.json. */
@Serializable
data class Health(val status: String, val version: Int)
