package com.linux.recipes.data

sealed interface PdfResult {
    /** The recipe as stored (original language), rendered by the server. */
    class Ready(val bytes: ByteArray) : PdfResult
    data object Limit : PdfResult                   // 429 pdf_limit
    data object Unavailable : PdfResult             // 503 pdf_unavailable
    data object Offline : PdfResult                 // no connection, 429 rate_limited or another 5xx
    data class Rejected(val code: String) : PdfResult
}

/** `GET /recipes/:id/pdf`, free for every signed-in user. A 401 ends the session like everywhere else. */
class PdfDownload(
    private val api: Api,
    private val currentToken: () -> String? = { null },
    private val onUnauthorized: suspend () -> Unit = {},
) {
    suspend fun download(recipeId: String): PdfResult {
        val token = currentToken()
        return when (val result = api.pdf(recipeId)) {
            is ApiResult.Ok -> PdfResult.Ready(result.value)
            ApiResult.Offline -> PdfResult.Offline
            is ApiResult.Failure -> when {
                result.wire == "pdf_limit" -> PdfResult.Limit
                result.wire == "pdf_unavailable" -> PdfResult.Unavailable
                result.status == 429 || result.status >= 500 -> PdfResult.Offline
                else -> PdfResult.Rejected(result.wire).also {
                    if (result.status == 401 && token != null && token == currentToken()) onUnauthorized()
                }
            }
        }
    }
}
