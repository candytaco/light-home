package com.candytaco.lighthome.ha

import kotlinx.serialization.json.JsonObject

data class HaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
) {
    /** True when the access token is expired or about to be; refresh before using it. */
    fun needsRefresh(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis - EXPIRY_MARGIN_MS

    override fun toString() = "HaTokens(expiresAtMillis=$expiresAtMillis)"

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

/**
 * Parses an `/auth/token` response. A refresh response has no `refresh_token`, so the caller
 * passes the one it used as [currentRefreshToken].
 */
internal fun parseTokenResponse(json: JsonObject, nowMillis: Long, currentRefreshToken: String? = null): HaTokens {
    val access = json.string("access_token") ?: throw HaProtocolException("token response without access_token")
    val refresh = json.string("refresh_token") ?: currentRefreshToken
        ?: throw HaProtocolException("token response without refresh_token")
    val expiresIn = json.int("expires_in") ?: 1800
    return HaTokens(access, refresh, nowMillis + expiresIn * 1000L)
}
