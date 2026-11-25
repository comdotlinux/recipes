package com.linux.recipes.data

import kotlinx.serialization.Serializable

// Wire shapes of docs/api.md, "Milestone M5 / Sharing by link".

/** `POST /recipes/:id/shares`: the only time the app sees [token]; [shareId] revokes the link. */
@Serializable data class ShareCreated(val shareId: String, val token: String, val expiresAt: String, val url: String)

/**
 * `GET /shares`: the caller's active links, without tokens. Every field but the ids and the end is optional, so the
 * app reads the list before and after the backend adds fields.
 */
@Serializable data class ShareList(val shares: List<ShareSummary> = emptyList())

@Serializable
data class ShareSummary(val shareId: String, val recipeId: String, val expiresAt: String, val createdAt: String? = null, val recipients: Int? = null)

/** `POST /shares/open`: the recipe's text only, the number of pictures the copy will get, and the link's end. */
@Serializable data class SharePreview(val recipe: SharedRecipe, val imageCount: Int, val expiresAt: String)

@Serializable
data class SharedRecipe(
    val title: String,
    val description: String = "",
    val servings: Int? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val language: String = "",
)

/** The body of `POST /shares/open` and `POST /shares/accept`: the token travels only in bodies, never in a URL. */
@Serializable data class ShareTokenBody(val shareToken: String)

/** `POST /reports` of kind `share`; [reason] and [note] as in [Report]. */
@Serializable data class ShareReport(val kind: String = "share", val shareToken: String, val reason: String, val note: String)

internal fun ReportReason.wire(): String = when (this) {
    ReportReason.WrongContent -> "wrong_content"
    ReportReason.Offensive -> "offensive"
    ReportReason.Other -> "other"
}
