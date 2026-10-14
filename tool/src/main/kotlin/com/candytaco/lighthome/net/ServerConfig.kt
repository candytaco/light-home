package com.candytaco.lighthome.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Cloudflare Access service token, sent as headers on every request. */
data class CfAccessToken(val clientId: String, val clientSecret: String) {
    override fun toString() = "CfAccessToken(clientId=$clientId, clientSecret=***)"
}

/**
 * Where Home Assistant lives and how to get past anything in front of it.
 * [baseUrl] has no trailing slash, e.g. `https://ha.example.com` or `http://192.168.1.10:8123`.
 */
data class ServerConfig(
    val baseUrl: String,
    val cfAccess: CfAccessToken? = null,
) {
    val webSocketUrl: String
        get() = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> baseUrl
        } + "/api/websocket"

    companion object {
        /**
         * Parses a setup QR code: JSON `{"url": …, "cf_client_id": …, "cf_client_secret": …}`
         * (from tools/make_setup_qr.py), or a bare http(s) URL for a server without Cloudflare
         * Access. Returns null for anything else.
         */
        fun fromSetupCode(text: String): ServerConfig? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{")) return create(trimmed)
            val json = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
            fun field(name: String) = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return create(field("url"), field("cf_client_id"), field("cf_client_secret"))
        }

        /** Normalizes user input; returns null when it isn't an http(s) URL. */
        fun create(rawUrl: String, cfClientId: String = "", cfClientSecret: String = ""): ServerConfig? {
            val url = rawUrl.trim().trimEnd('/')
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null
            if (url.substringAfter("://").isBlank()) return null
            val cf = if (cfClientId.isNotBlank() && cfClientSecret.isNotBlank()) {
                CfAccessToken(cfClientId.trim(), cfClientSecret.trim())
            } else {
                null
            }
            return ServerConfig(url, cf)
        }
    }
}
