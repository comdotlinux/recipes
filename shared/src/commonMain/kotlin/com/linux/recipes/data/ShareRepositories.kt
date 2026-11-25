package com.linux.recipes.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// Signatures fixed by docs/api.md, "Module boundaries / Client, milestone M5". Change only together with that section.

/**
 * An active link of the user. [url] is known only on the device that made the link (the server never hands the token
 * out again), so "Copy link" exists only there; [recipients] is how many people added the recipe, when the server says.
 */
@Serializable
data class ShareLink(val shareId: String, val recipeId: String, val url: String? = null, val expiresAt: String, val recipients: Int? = null)

sealed interface ShareCreate {
    data class Created(val link: ShareLink) : ShareCreate
    data object SubscriptionRequired : ShareCreate      // 402 subscription_required
    data object ConsentRequired : ShareCreate           // 403 consent_required (the recipe has pictures)
    data object PictureRejected : ShareCreate           // 422 picture_rejected
    data object ShareLimit : ShareCreate                // 429 share_limit
    data object ScreeningUnavailable : ShareCreate      // 503 screening_unavailable
    data object Offline : ShareCreate                   // no connection, 429 rate_limited or another 5xx
    data class Rejected(val code: String) : ShareCreate // 404 and anything else
}

sealed interface ShareOpen {
    data class Preview(val preview: SharePreview) : ShareOpen
    data object Gone : ShareOpen                        // 410 share_gone
    data object NotFound : ShareOpen                    // 404: not a genuine link
    data object OwnShare : ShareOpen                    // 409 own_share
    data object Offline : ShareOpen
    data class Rejected(val code: String) : ShareOpen
}

sealed interface ShareAccept {
    /** The copy; it reaches the recipe list through a sync, which [ShareRepository.accept] runs. */
    data class Accepted(val recipe: Recipe) : ShareAccept
    data object RecipeLimit : ShareAccept               // 409 recipe_limit
    data object RecipeStorageCap : ShareAccept          // 413 recipe_storage_cap
    data object StorageCap : ShareAccept                // 413 storage_cap
    data object Unreadable : ShareAccept                // 422 share_unreadable
    data object Gone : ShareAccept
    data object NotFound : ShareAccept
    data object OwnShare : ShareAccept
    data object Offline : ShareAccept
    data class Rejected(val code: String) : ShareAccept
}

interface ShareRepository {
    /** The user's active links, newest first: from `GET /shares` once [refresh] worked, else those made here. Cleared on sign-out. */
    val links: StateFlow<List<ShareLink>>
    /** Loads `GET /shares` into [links], keeping the URLs of links made here. False when offline or the route is missing. */
    suspend fun refresh(): Boolean
    suspend fun create(recipeId: String): ShareCreate
    /** True once the link is gone on the server (204, or 404 because it no longer exists). */
    suspend fun revoke(shareId: String): Boolean
    suspend fun open(token: String): ShareOpen
    suspend fun accept(token: String): ShareAccept
    /** A report revokes the link at once; reporting it again changes nothing. */
    suspend fun report(token: String, reason: ReportReason, note: String): Boolean
}

/** Share tokens from incoming links: `https://<host>/s#<token>` only, nothing looser. */
object ShareLinks {
    /** 80 bytes in base64url (docs/api.md, `ShareCreated.token`). */
    private val TOKEN = Regex("^[A-Za-z0-9_-]{107}$")

    fun isToken(value: String?): Boolean = value != null && TOKEN.matches(value)

    /**
     * The token of [url] when it is exactly `https://[host]/s#<token>`: https, the exact host (no port, no user,
     * any letter case of the host), path `/s`, no query, and a fragment that is one well-formed token. Else null.
     */
    fun tokenFromUrl(url: String?, host: String): String? {
        if (url == null) return null
        val prefix = "https://"
        if (!url.startsWith(prefix, ignoreCase = true)) return null
        val rest = url.substring(prefix.length)
        val hash = rest.indexOf('#')
        if (hash < 0) return null
        val beforeFragment = rest.substring(0, hash)
        val token = rest.substring(hash + 1)
        val slash = beforeFragment.indexOf('/')
        if (slash < 0) return null
        if (!beforeFragment.substring(0, slash).equals(host, ignoreCase = true)) return null
        if (beforeFragment.substring(slash) != "/s") return null
        return token.takeIf(::isToken)
    }

    /** The host of an `https://host` base URL such as `AppConfig.API_BASE_URL`. */
    fun hostOf(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore('/')

    /** The token from an Android intent: the link's fragment first, else the landing page's `shareToken` extra. */
    fun tokenFromIntent(dataUrl: String?, extra: String?, host: String): String? =
        tokenFromUrl(dataUrl, host) ?: extra?.takeIf(::isToken)
}
