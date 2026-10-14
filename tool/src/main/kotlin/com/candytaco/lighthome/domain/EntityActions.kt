package com.candytaco.lighthome.domain

import com.candytaco.lighthome.ha.EntityState
import com.candytaco.lighthome.ha.array
import com.candytaco.lighthome.ha.double
import com.candytaco.lighthome.ha.int
import com.candytaco.lighthome.ha.textOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt

/** A Home Assistant service call aimed at one entity. */
data class Command(
    val domain: String,
    val service: String,
    val data: JsonObject? = null,
    /** True when the entity's state should change; the row stays pending until it does. */
    val expectStateChange: Boolean,
    /** Shown in place of the state while the command is pending. Verb-free: "on…", "off…", "…". */
    val pendingLabel: String = "…",
)

private val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean")
private val RUN_DOMAINS = setOf("scene", "script", "button", "input_button")
private val LONG_PRESS_DETAIL_DOMAINS = setOf("light", "fan")

/**
 * What tapping the entity's row does. Null means the tap opens the detail screen instead:
 * covers, climate, locks and media players (no accidental unlock or open) and anything read-only.
 */
fun tapCommand(entity: EntityState): Command? {
    if (entity.state == "unavailable") return null
    return when (entity.domain) {
        in TOGGLE_DOMAINS -> toggleCommand(entity)
        "scene", "script" -> Command(entity.domain, "turn_on", expectStateChange = false)
        "button", "input_button" -> Command(entity.domain, "press", expectStateChange = false)
        else -> null
    }
}

fun tapOpensDetail(entity: EntityState): Boolean = entity.domain !in TOGGLE_DOMAINS && entity.domain !in RUN_DOMAINS

fun longPressOpensDetail(entity: EntityState): Boolean = entity.domain in LONG_PRESS_DETAIL_DOMAINS

/**
 * Explicit `turn_on` / `turn_off` based on the state on screen, not HA's `toggle`: toggle lets HA
 * pick the direction from its own view of the state, which for some lights (groups, some
 * integrations) doesn't match what's displayed, so "on" never happened.
 */
fun toggleCommand(entity: EntityState): Command {
    val isOn = entity.state == "on"
    return Command(
        domain = entity.domain,
        service = if (isOn) "turn_off" else "turn_on",
        expectStateChange = true,
        pendingLabel = if (isOn) "off…" else "on…",
    )
}

// --- Lights ---

data class LightCapabilities(
    val brightness: Boolean,
    val colorTemperature: Boolean,
    val hue: Boolean,
    val minKelvin: Int,
    val maxKelvin: Int,
)

private val HUE_COLOR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")

/** From `supported_color_modes`: `onoff` alone means no brightness; `color_temp` and the color modes add sliders. */
fun lightCapabilities(light: EntityState): LightCapabilities {
    val modes = light.attributes.array("supported_color_modes")?.mapNotNull { it.textOrNull() }.orEmpty()
    return LightCapabilities(
        brightness = modes.any { it != "onoff" },
        colorTemperature = "color_temp" in modes,
        hue = modes.any { it in HUE_COLOR_MODES },
        minKelvin = light.attributes.int("min_color_temp_kelvin") ?: 2000,
        maxKelvin = light.attributes.int("max_color_temp_kelvin") ?: 6500,
    )
}

/** 0..1, or 0 when off (HA reports no brightness for an off light). */
fun lightBrightness(light: EntityState): Float =
    ((light.attributes.double("brightness") ?: 0.0) / 255.0).toFloat().coerceIn(0f, 1f)

fun lightKelvin(light: EntityState): Int? = light.attributes.double("color_temp_kelvin")?.roundToInt()

fun lightHue(light: EntityState): Float? =
    (light.attributes.array("hs_color")?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull?.toFloat()

fun setBrightnessCommand(fraction: Float) = Command(
    domain = "light",
    service = "turn_on",
    data = buildJsonObject { put("brightness_pct", (fraction * 100).roundToInt().coerceIn(1, 100)) },
    expectStateChange = false,
)

fun setKelvinCommand(kelvin: Int) = Command(
    domain = "light",
    service = "turn_on",
    data = buildJsonObject { put("color_temp_kelvin", kelvin) },
    expectStateChange = false,
)

/** Full saturation, so the chosen hue is actually visible. */
fun setHueCommand(hueDegrees: Float) = Command(
    domain = "light",
    service = "turn_on",
    data = buildJsonObject {
        putJsonArray("hs_color") {
            add(JsonPrimitive(hueDegrees.roundToInt().coerceIn(0, 360)))
            add(JsonPrimitive(100))
        }
    },
    expectStateChange = false,
)

/** A plain name for a hue, since the screen shows text rather than a color swatch. */
fun hueName(hueDegrees: Float): String = when (((hueDegrees % 360) + 360) % 360) {
    in 0f..<15f -> "red"
    in 15f..<45f -> "orange"
    in 45f..<70f -> "yellow"
    in 70f..<160f -> "green"
    in 160f..<200f -> "cyan"
    in 200f..<255f -> "blue"
    in 255f..<290f -> "purple"
    in 290f..<345f -> "pink"
    else -> "red"
}

/** "warm" / "neutral" / "cool" for a color temperature. */
fun kelvinName(kelvin: Int): String = when {
    kelvin < 3300 -> "warm"
    kelvin < 5000 -> "neutral"
    else -> "cool"
}

// --- Fans ---

fun fanPercentage(fan: EntityState): Float? = fan.attributes.double("percentage")?.let { (it / 100.0).toFloat() }

fun setFanPercentageCommand(fraction: Float) = Command(
    domain = "fan",
    service = "set_percentage",
    data = buildJsonObject { put("percentage", (fraction * 100).roundToInt().coerceIn(0, 100)) },
    expectStateChange = false,
)

// --- Covers ---

/** HA CoverEntityFeature bits. */
private const val COVER_OPEN = 1
private const val COVER_CLOSE = 2
private const val COVER_SET_POSITION = 4
private const val COVER_STOP = 8

data class CoverCapabilities(val open: Boolean, val close: Boolean, val stop: Boolean, val position: Boolean)

fun coverCapabilities(cover: EntityState): CoverCapabilities {
    val features = cover.attributes.int("supported_features") ?: 0
    return CoverCapabilities(
        open = features and COVER_OPEN != 0,
        close = features and COVER_CLOSE != 0,
        stop = features and COVER_STOP != 0,
        position = features and COVER_SET_POSITION != 0 && cover.attributes.double("current_position") != null,
    )
}

fun coverPosition(cover: EntityState): Float? = cover.attributes.double("current_position")?.let { (it / 100.0).toFloat() }

fun coverCommand(service: String) = Command("cover", service, expectStateChange = false)

fun setCoverPositionCommand(fraction: Float) = Command(
    domain = "cover",
    service = "set_cover_position",
    data = buildJsonObject { put("position", (fraction * 100).roundToInt().coerceIn(0, 100)) },
    expectStateChange = false,
)

// --- Climate ---

data class ClimateInfo(
    val current: Double?,
    /** Single target; null when the thermostat uses a low/high range instead. */
    val target: Double?,
    val targetLow: Double?,
    val targetHigh: Double?,
    val step: Double,
    val min: Double,
    val max: Double,
    val modes: List<String>,
)

fun climateInfo(climate: EntityState): ClimateInfo {
    val a = climate.attributes
    return ClimateInfo(
        current = a.double("current_temperature"),
        target = a.double("temperature"),
        targetLow = a.double("target_temp_low"),
        targetHigh = a.double("target_temp_high"),
        step = a.double("target_temp_step") ?: 0.5,
        min = a.double("min_temp") ?: 7.0,
        max = a.double("max_temp") ?: 35.0,
        modes = a.array("hvac_modes")?.mapNotNull { it.textOrNull() }.orEmpty(),
    )
}

fun setTemperatureCommand(target: Double) = Command(
    domain = "climate",
    service = "set_temperature",
    data = buildJsonObject { put("temperature", target) },
    expectStateChange = false,
)

/** The mode after the current one, wrapping around; null when there's nothing to cycle. */
fun nextHvacMode(climate: EntityState, info: ClimateInfo): String? {
    if (info.modes.size < 2) return null
    val index = info.modes.indexOf(climate.state)
    return info.modes[(index + 1).mod(info.modes.size)]
}

fun setHvacModeCommand(mode: String) = Command(
    domain = "climate",
    service = "set_hvac_mode",
    data = buildJsonObject { put("hvac_mode", mode) },
    expectStateChange = true,
    pendingLabel = "${mode.replace('_', ' ')}…",
)

// --- Locks ---

fun lockCommand(lock: Boolean) = Command(
    domain = "lock",
    service = if (lock) "lock" else "unlock",
    expectStateChange = true,
    pendingLabel = if (lock) "locked…" else "unlocked…",
)

// --- Media players ---

fun mediaVolume(player: EntityState): Float? = player.attributes.double("volume_level")?.toFloat()

fun mediaCommand(service: String, expectStateChange: Boolean = false) =
    Command("media_player", service, expectStateChange = expectStateChange)

fun setVolumeCommand(fraction: Float) = Command(
    domain = "media_player",
    service = "volume_set",
    data = buildJsonObject { put("volume_level", (fraction * 100).roundToInt() / 100.0) },
    expectStateChange = false,
)
