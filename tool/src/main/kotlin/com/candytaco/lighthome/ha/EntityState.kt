package com.candytaco.lighthome.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    /** When the state (not just an attribute) last changed, epoch seconds. */
    val lastChanged: Double? = null,
) {
    val domain: String get() = entityId.substringBefore('.')
    val friendlyName: String get() = attributes.string("friendly_name") ?: entityId.substringAfter('.')
}

/**
 * Applies one `subscribe_entities` event (HA's compressed state format) to [current]:
 * - `a`: entities added or fully replaced: `{entity_id: {s: state, a: attributes, …}}`
 * - `c`: changes: `{entity_id: {"+": {s, a: changed attributes}, "-": {a: [removed attribute names]}}}`
 * - `r`: removed entity ids
 * The first event after subscribing carries every entity in `a`.
 */
internal fun applyEntityEvent(current: Map<String, EntityState>, event: JsonObject): Map<String, EntityState> {
    val next = current.toMutableMap()

    event.obj("a")?.forEach { (entityId, compressed) ->
        val obj = compressed as? JsonObject ?: return@forEach
        next[entityId] = EntityState(
            entityId = entityId,
            state = obj["s"].textOrNull() ?: "unknown",
            attributes = obj.obj("a") ?: JsonObject(emptyMap()),
            lastChanged = obj.double("lc"),
        )
    }

    event.obj("c")?.forEach { (entityId, diff) ->
        val existing = next[entityId] ?: return@forEach
        val changes = diff as? JsonObject ?: return@forEach
        val added = changes.obj("+")
        val removedNames = changes.obj("-")?.array("a")?.mapNotNull { it.textOrNull() }.orEmpty()

        val attributes = existing.attributes.toMutableMap<String, JsonElement>()
        added?.obj("a")?.forEach { (name, value) -> attributes[name] = value }
        removedNames.forEach { attributes.remove(it) }

        next[entityId] = existing.copy(
            state = added?.get("s").textOrNull() ?: existing.state,
            attributes = JsonObject(attributes),
            // "lc" is only present when the state itself changed (attribute-only updates send "lu").
            lastChanged = added?.double("lc") ?: existing.lastChanged,
        )
    }

    (event["r"] as? JsonArray)?.forEach { removed ->
        removed.textOrNull()?.let { next.remove(it) }
    }

    return next
}
