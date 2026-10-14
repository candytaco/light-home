package com.candytaco.lighthome.ha

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

@Serializable
data class FloorEntry(
    @SerialName("floor_id") val floorId: String,
    val name: String,
)

@Serializable
data class AreaEntry(
    @SerialName("area_id") val areaId: String,
    val name: String,
    @SerialName("floor_id") val floorId: String? = null,
    /** The area's primary sensors, as set in HA's area settings (shown on area cards). */
    @SerialName("temperature_entity_id") val temperatureEntityId: String? = null,
    @SerialName("humidity_entity_id") val humidityEntityId: String? = null,
)

@Serializable
data class DeviceEntry(
    val id: String,
    @SerialName("area_id") val areaId: String? = null,
    val name: String? = null,
    @SerialName("name_by_user") val nameByUser: String? = null,
) {
    val displayName: String? get() = nameByUser ?: name
}

@Serializable
data class EntityEntry(
    @SerialName("entity_id") val entityId: String,
    /** The name the user set in the entity's settings; null when they didn't. */
    val name: String? = null,
    /** The integration's name for the entity (without the device name). */
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    /** "config" / "diagnostic" for entities that aren't meant for everyday use. */
    @SerialName("entity_category") val entityCategory: String? = null,
    @SerialName("hidden_by") val hiddenBy: String? = null,
    @SerialName("disabled_by") val disabledBy: String? = null,
)

/**
 * Floors, areas, devices and entity registry entries, fetched once per connection.
 * Floors and areas keep HA's registry order, which is the order the user arranged them in.
 */
data class Registry(
    val floors: List<FloorEntry>,
    val areas: List<AreaEntry>,
    val devices: List<DeviceEntry>,
    val entities: List<EntityEntry>,
) {
    private val devicesById = devices.associateBy { it.id }
    private val entityEntries = entities.associateBy { it.entityId }
    val areasById: Map<String, AreaEntry> = areas.associateBy { it.areaId }

    fun entry(entityId: String): EntityEntry? = entityEntries[entityId]

    fun device(deviceId: String?): DeviceEntry? = deviceId?.let { devicesById[it] }

    /** The entity's own area, falling back to its device's area. */
    fun areaIdOf(entityId: String): String? {
        val entry = entityEntries[entityId] ?: return null
        return (entry.areaId ?: device(entry.deviceId)?.areaId)?.takeIf { it in areasById }
    }

    /** Not hidden or disabled, and not a domain with nothing to show as text. */
    fun isVisible(entityId: String): Boolean {
        if (entityId.substringBefore('.') in TEXTLESS_DOMAINS) return false
        val entry = entityEntries[entityId] ?: return true // YAML entities have no registry entry
        return entry.hiddenBy == null && entry.disabledBy == null
    }

    /** The name the user gave the entity in HA's entity settings, if any. */
    fun customName(entityId: String): String? = entityEntries[entityId]?.name?.takeIf { it.isNotBlank() }

    /** HA's `entity_category: "none"` filter: everyday entities, not config/diagnostic ones. */
    fun isPrimary(entityId: String): Boolean = entityEntries[entityId]?.entityCategory == null

    companion object {
        /** Domains with no useful text form (images, cameras) or that are pure plumbing. */
        private val TEXTLESS_DOMAINS = setOf(
            "update", "zone", "tts", "stt", "conversation", "wake_word", "assist_satellite",
            "ai_task", "event", "image", "camera", "tag", "notify",
        )

        fun parse(floors: JsonElement?, areas: JsonElement?, devices: JsonElement?, entities: JsonElement?) = Registry(
            floors = floors.decodeList(FloorEntry.serializer()),
            areas = areas.decodeList(AreaEntry.serializer()),
            devices = devices.decodeList(DeviceEntry.serializer()),
            entities = entities.decodeList(EntityEntry.serializer()),
        )

        private fun <T> JsonElement?.decodeList(serializer: KSerializer<T>): List<T> =
            if (this == null) emptyList() else HaJson.decodeFromJsonElement(ListSerializer(serializer), this)
    }
}

/**
 * The name to show for an entity: the name the user gave it in HA when there is one, else HA's
 * friendly name with a redundant [prefix] (area or device name, already in the heading) removed.
 */
fun Registry.entityDisplayName(entity: EntityState, prefix: String?): String =
    customName(entity.entityId) ?: displayName(entity, prefix)

/**
 * The entity's name with a redundant prefix removed: HA friendly names usually start with the
 * area or device name ("Living Room Ceiling Light"), which the surrounding heading already says.
 */
fun displayName(entity: EntityState, prefix: String?): String {
    val name = entity.friendlyName.trim()
    if (prefix.isNullOrBlank()) return name
    val stripped = name.removePrefix(prefix).trimStart()
    if (stripped.length == name.length || stripped.isEmpty()) return name
    return stripped.replaceFirstChar { it.uppercaseChar() }
}
