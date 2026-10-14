package com.candytaco.lighthome.net

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

/** Adds the Access service-token headers. Every request to the server must go through this. */
fun HttpRequestBuilder.cfAccess(token: CfAccessToken?) {
    token ?: return
    header("CF-Access-Client-Id", token.clientId)
    header("CF-Access-Client-Secret", token.clientSecret)
}

/** Why Cloudflare Access stopped a request before it reached Home Assistant. */
enum class CfAccessFailure(val message: String) {
    /** 401 from Access: unknown/invalid client id or secret, or an expired/deleted token. */
    TokenRejected("Cloudflare access key rejected"),

    /** 403 from Access: the token is valid, but no Service Auth policy on this app allows it. */
    TokenNotAllowed("Cloudflare access key valid, but not permitted by this server's Access policy"),

    /** 302 to the Access login page: no token headers were sent. */
    TokenMissing("Server behind Cloudflare Access; no access key configured"),
}

class CfAccessException(val failure: CfAccessFailure) : Exception(failure.message)

/**
 * Returns the Access failure this response represents, or null when it came from Home Assistant
 * (or anything else past Access). Requires redirect-following to be off, so a 302 stays visible.
 *
 * Observed on a strict-mode Access app (2026-10-11): Access responses carry `Cf-Access-Domain`;
 * Home Assistant's own 401 does not.
 */
fun classifyCfAccess(status: Int, header: (String) -> String?): CfAccessFailure? {
    val fromAccess = header("Cf-Access-Domain") != null
    return when {
        fromAccess && status == 401 -> CfAccessFailure.TokenRejected
        fromAccess && status == 403 -> CfAccessFailure.TokenNotAllowed
        status in 300..399 && isAccessLoginRedirect(header(HttpHeaders.Location)) -> CfAccessFailure.TokenMissing
        else -> null
    }
}

private fun isAccessLoginRedirect(location: String?): Boolean {
    if (location == null) return false
    val host = location.substringAfter("://", "").substringBefore('/').substringBefore(':')
    return host.endsWith(".cloudflareaccess.com") || location.contains("/cdn-cgi/access/login")
}

/** Throws [CfAccessException] when Access blocked [response]. */
fun throwIfCfAccessBlocked(response: HttpResponse) {
    classifyCfAccess(response.status.value) { response.headers[it] }?.let { throw CfAccessException(it) }
}
