package com.candytaco.lighthome.ha

import com.candytaco.lighthome.net.ServerConfig
import com.candytaco.lighthome.net.cfAccess
import com.candytaco.lighthome.net.throwIfCfAccessBlocked
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Home Assistant's HTTP auth endpoints, used without a browser:
 * `/auth/login_flow` → auth code → `/auth/token` → access + refresh tokens.
 * See homeassistant/components/auth/login_flow.py and indieauth.py in HA core.
 */
class HaAuthApi(
    private val client: HttpClient,
    private val server: ServerConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * `GET /api/` with the Access headers. Home Assistant answers 401 (no bearer token),
     * which proves Cloudflare let us through; Access failures throw [com.candytaco.lighthome.net.CfAccessException].
     */
    suspend fun checkReachable() {
        val response = client.get(server.baseUrl + "/api/") {
            cfAccess(server.cfAccess)
            timeout { requestTimeoutMillis = TIMEOUT_MS }
        }
        throwIfCfAccessBlocked(response)
        if (response.status.value !in setOf(200, 401)) {
            throw HaHttpException(response.status.value, "Unexpected server response (${response.status.value}); possibly not a Home Assistant URL")
        }
    }

    suspend fun startLoginFlow(): LoginStep {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("redirect_uri", REDIRECT_URI)
            put("handler", JsonArray(listOf(JsonPrimitive("homeassistant"), JsonNull)))
        }
        return parseLoginStep(postJson("/auth/login_flow", body))
    }

    /** Submits one form step; [values] are keyed by [FormField.name]. */
    suspend fun submitStep(flowId: String, values: Map<String, String>): LoginStep {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            values.forEach { (name, value) -> put(name, value) }
        }
        return parseLoginStep(postJson("/auth/login_flow/$flowId", body))
    }

    suspend fun exchangeCode(authCode: String): HaTokens {
        val response = postTokenForm(
            "grant_type" to "authorization_code",
            "code" to authCode,
            "client_id" to CLIENT_ID,
        )
        return parseTokenResponse(response, clock())
    }

    /** Throws [HaAuthRejectedException] when the refresh token was revoked or is invalid. */
    suspend fun refresh(refreshToken: String): HaTokens {
        val response = postTokenForm(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to CLIENT_ID,
        )
        return parseTokenResponse(response, clock(), currentRefreshToken = refreshToken)
    }

    /** Revokes the refresh token and every access token it issued. HA always answers 200. */
    suspend fun revoke(refreshToken: String) {
        val response = client.submitForm(
            url = server.baseUrl + "/auth/revoke",
            formParameters = parameters { append("token", refreshToken) },
        ) {
            cfAccess(server.cfAccess)
            timeout { requestTimeoutMillis = TIMEOUT_MS }
        }
        throwIfCfAccessBlocked(response)
    }

    private suspend fun postJson(path: String, body: JsonObject): JsonObject {
        val response = client.post(server.baseUrl + path) {
            cfAccess(server.cfAccess)
            timeout { requestTimeoutMillis = TIMEOUT_MS }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return readJson(response)
    }

    private suspend fun postTokenForm(vararg fields: Pair<String, String>): JsonObject {
        val response = client.submitForm(
            url = server.baseUrl + "/auth/token",
            formParameters = parameters { fields.forEach { (k, v) -> append(k, v) } },
        ) {
            cfAccess(server.cfAccess)
            timeout { requestTimeoutMillis = TIMEOUT_MS }
        }
        if (response.status.value == 400 || response.status.value == 403) {
            throwIfCfAccessBlocked(response)
            // invalid_grant / invalid_request / inactive user: the refresh token is no good.
            throw HaAuthRejectedException(errorText(response))
        }
        return readJson(response)
    }

    private suspend fun readJson(response: HttpResponse): JsonObject {
        throwIfCfAccessBlocked(response)
        val status = response.status.value
        if (status !in 200..299) throw HaHttpException(status, errorText(response))
        return HaJson.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: throw HaProtocolException("expected a JSON object from Home Assistant")
    }

    private suspend fun errorText(response: HttpResponse): String {
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val json = runCatching { HaJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val detail = json?.string("message") ?: json?.string("error_description") ?: json?.string("error")
            ?: text.take(200)
        return "Home Assistant ${response.status.value}: $detail"
    }

    companion object {
        /**
         * IndieAuth client id. HA shows it next to the refresh token in the user's profile.
         * [REDIRECT_URI] shares its scheme and host, so HA never fetches either URL, and since we
         * read the auth code from the JSON response no redirect ever happens.
         */
        const val CLIENT_ID = "https://github.com/candytaco/light-home-assistant"
        const val REDIRECT_URI = "https://github.com/candytaco/light-home-assistant/auth-callback"
        private const val TIMEOUT_MS = 20_000L
    }
}
