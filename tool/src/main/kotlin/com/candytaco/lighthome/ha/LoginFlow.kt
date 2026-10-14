package com.candytaco.lighthome.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One step of Home Assistant's login flow (`/auth/login_flow`). The same shape covers the
 * username/password step, MFA module selection and the MFA code step, so the UI draws
 * whatever [Form.fields] asks for instead of hard-coding screens.
 */
sealed interface LoginStep {
    data class Form(
        val flowId: String,
        val stepId: String,
        val fields: List<FormField>,
        /** Field name (or "base") to an HA error key such as `invalid_auth` or `invalid_code`. */
        val errors: Map<String, String>,
    ) : LoginStep

    /** The flow finished; [authCode] is exchanged at `/auth/token`. */
    data class Done(val authCode: String) : LoginStep

    data class Aborted(val reason: String) : LoginStep
}

data class FormField(
    val name: String,
    val type: String,
    val required: Boolean,
    /** For `select` fields: value to label. */
    val options: List<Pair<String, String>> = emptyList(),
) {
    val isSecret: Boolean get() = name == "password" || name == "code"
    val isSelect: Boolean get() = type == "select"
}

internal fun parseLoginStep(json: JsonObject): LoginStep = when (json.string("type")) {
    "form" -> LoginStep.Form(
        flowId = json.string("flow_id") ?: throw HaProtocolException("login form without flow_id"),
        stepId = json.string("step_id") ?: "",
        fields = json.array("data_schema").orEmpty().mapNotNull { (it as? JsonObject)?.toFormField() },
        errors = json.obj("errors").orEmpty().mapNotNull { (key, value) ->
            value.textOrNull()?.let { key to it }
        }.toMap(),
    )
    "create_entry" -> LoginStep.Done(
        authCode = json.string("result") ?: throw HaProtocolException("login finished without an auth code"),
    )
    "abort" -> LoginStep.Aborted(json.string("reason") ?: "unknown")
    else -> throw HaProtocolException("unexpected login flow response type: ${json.string("type")}")
}

private fun JsonObject.toFormField(): FormField? {
    val name = string("name") ?: return null
    return FormField(
        name = name,
        type = string("type") ?: "string",
        required = boolean("required") ?: false,
        options = parseOptions(this["options"]),
    )
}

/** voluptuous-serialize writes select options either as `[[value, label], …]` or `{value: label}`. */
private fun parseOptions(element: JsonElement?): List<Pair<String, String>> = when (element) {
    is JsonArray -> element.mapNotNull { option ->
        when (option) {
            is JsonArray -> {
                val value = option.getOrNull(0).textOrNull()
                value?.let { it to (option.getOrNull(1).textOrNull() ?: it) }
            }
            is JsonPrimitive -> option.content.let { it to it }
            else -> null
        }
    }
    is JsonObject -> element.mapNotNull { (value, label) -> value to (label.textOrNull() ?: value) }
    else -> emptyList()
}

/** Human-readable text for the error keys HA's auth providers use. */
fun loginErrorMessage(errorKey: String): String = when (errorKey) {
    "invalid_auth" -> "Wrong username or password"
    "invalid_code" -> "Invalid code"
    "too_many_retry" -> "Too many attempts"
    "login_expired" -> "Sign-in expired"
    else -> "Sign-in failure ($errorKey)"
}
