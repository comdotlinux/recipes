package com.linux.recipes.data

import kotlinx.serialization.Serializable

/** Body of `GET /v1/health`; shape fixed by contract/fixtures/health.json. */
@Serializable
data class Health(val status: String, val version: Int)
