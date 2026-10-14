package com.candytaco.lighthome.domain

import com.candytaco.lighthome.ha.EntityState
import com.candytaco.lighthome.ha.double
import com.candytaco.lighthome.ha.string
import kotlin.math.abs
import kotlin.math.roundToInt

/** The short state text shown at the right of an entity row. */
fun formatState(entity: EntityState): String {
    val state = entity.state
    if (state == "unavailable") return "unavailable"
    if (state == "unknown") return "–"

    return when (entity.domain) {
        "light" -> if (state == "on") {
            entity.number("brightness")?.let { "on ${(it / 255.0 * 100).roundToInt()}%" } ?: "on"
        } else {
            state
        }
        "fan" -> if (state == "on") {
            entity.number("percentage")?.let { "on ${it.roundToInt()}%" } ?: "on"
        } else {
            state
        }
        "cover" -> {
            val position = entity.number("current_position")?.roundToInt()
            if (position != null && state == "open" && position in 1..99) "open $position%" else humanize(state)
        }
        "climate" -> {
            val current = entity.number("current_temperature")?.let { "${formatNumber(it)}°" }
            listOfNotNull(humanize(state), current).joinToString(" ")
        }
        "sensor" -> formatSensor(entity)
        "binary_sensor" -> formatBinarySensor(state, entity.attributes.string("device_class"))
        "person", "device_tracker" -> when (state) {
            "home" -> "home"
            "not_home" -> "away"
            else -> humanize(state)
        }
        "script" -> if (state == "on") "running" else ""
        // Scenes report the time they last ran; buttons the time they were last pressed.
        "scene", "button", "input_button" -> ""
        else -> humanize(state)
    }
}

private fun formatSensor(entity: EntityState): String {
    val unit = entity.attributes.string("unit_of_measurement")
    val value = entity.state.toDoubleOrNull()?.let(::formatNumber) ?: humanize(entity.state)
    return when {
        unit.isNullOrBlank() -> value
        unit == "%" || unit.startsWith("°") -> "$value$unit"
        else -> "$value $unit"
    }
}

private fun formatBinarySensor(state: String, deviceClass: String?): String {
    val on = state == "on"
    return when (deviceClass) {
        "door", "window", "opening", "garage_door" -> if (on) "open" else "closed"
        "motion", "occupancy", "presence" -> if (on) "detected" else "clear"
        "moisture" -> if (on) "wet" else "dry"
        "lock" -> if (on) "unlocked" else "locked"
        "battery" -> if (on) "low" else "normal"
        "connectivity" -> if (on) "connected" else "disconnected"
        "plug", "power" -> if (on) "on" else "off"
        "problem", "safety", "smoke", "gas", "carbon_monoxide", "tamper" -> if (on) "alert" else "ok"
        else -> state
    }
}

/** Up to one decimal, dropping a trailing ".0". */
internal fun formatNumber(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (abs(rounded - rounded.roundToInt()) < 1e-9) rounded.roundToInt().toString() else rounded.toString()
}

private fun humanize(state: String) = state.replace('_', ' ')

private fun EntityState.number(attribute: String): Double? = attributes.double(attribute)
