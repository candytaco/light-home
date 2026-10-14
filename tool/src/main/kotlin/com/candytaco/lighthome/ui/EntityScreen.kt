package com.candytaco.lighthome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.candytaco.lighthome.AppGraph
import com.candytaco.lighthome.domain.Command
import com.candytaco.lighthome.domain.climateInfo
import com.candytaco.lighthome.domain.coverCapabilities
import com.candytaco.lighthome.domain.coverCommand
import com.candytaco.lighthome.domain.coverPosition
import com.candytaco.lighthome.domain.fanPercentage
import com.candytaco.lighthome.domain.formatNumber
import com.candytaco.lighthome.domain.formatState
import com.candytaco.lighthome.domain.hueName
import com.candytaco.lighthome.domain.kelvinName
import com.candytaco.lighthome.domain.lightBrightness
import com.candytaco.lighthome.domain.lightCapabilities
import com.candytaco.lighthome.domain.lightHue
import com.candytaco.lighthome.domain.lightKelvin
import com.candytaco.lighthome.domain.lockCommand
import com.candytaco.lighthome.domain.mediaCommand
import com.candytaco.lighthome.domain.mediaVolume
import com.candytaco.lighthome.domain.nextHvacMode
import com.candytaco.lighthome.domain.setBrightnessCommand
import com.candytaco.lighthome.domain.setCoverPositionCommand
import com.candytaco.lighthome.domain.setFanPercentageCommand
import com.candytaco.lighthome.domain.setHueCommand
import com.candytaco.lighthome.domain.setHvacModeCommand
import com.candytaco.lighthome.domain.setKelvinCommand
import com.candytaco.lighthome.domain.setTemperatureCommand
import com.candytaco.lighthome.domain.setVolumeCommand
import com.candytaco.lighthome.domain.tapCommand
import com.candytaco.lighthome.domain.toggleCommand
import com.candytaco.lighthome.ha.EntityState
import com.candytaco.lighthome.ha.HaSession
import com.candytaco.lighthome.ha.SessionStatus
import com.candytaco.lighthome.ha.string
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** A lock action waiting for the user to confirm. */
data class PendingConfirmation(val message: String, val lock: Boolean)

class EntityViewModel(
    private val session: HaSession,
    private val entityId: String,
) : LightViewModel<Unit>() {
    val status: StateFlow<SessionStatus> = session.status
    val commander = EntityCommander(session, viewModelScope)

    val entity: StateFlow<EntityState?> = session.entities
        .map { it[entityId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.entities.value[entityId])

    private val _confirmation = MutableStateFlow<PendingConfirmation?>(null)
    val confirmation: StateFlow<PendingConfirmation?> = _confirmation.asStateFlow()

    private fun current(): EntityState? = entity.value

    fun toggle() {
        val e = current() ?: return
        commander.send(e, toggleCommand(e))
    }

    /** Scenes, scripts and buttons: the same command a row tap sends. */
    fun run() {
        val e = current() ?: return
        tapCommand(e)?.let { commander.send(e, it) }
    }

    fun setBrightness(fraction: Float) = slide(KEY_BRIGHTNESS, fraction) { setBrightnessCommand(it) }

    fun setKelvinFraction(fraction: Float) {
        val e = current() ?: return
        val caps = lightCapabilities(e)
        slide(KEY_KELVIN, fraction) { setKelvinCommand(fractionToKelvin(it, caps.minKelvin, caps.maxKelvin)) }
    }

    fun setHueFraction(fraction: Float) = slide(KEY_HUE, fraction) { setHueCommand(it * 360f) }

    fun setFanSpeed(fraction: Float) = slide(KEY_FAN, fraction) { setFanPercentageCommand(it) }

    fun setCoverPosition(fraction: Float) = slide(KEY_POSITION, fraction) { setCoverPositionCommand(it) }

    fun cover(service: String) {
        val e = current() ?: return
        commander.send(e, coverCommand(service))
    }

    /** −/+ on a thermostat: moves the shown target at once and sends after a short pause. */
    fun stepTarget(direction: Int) {
        val e = current() ?: return
        val info = climateInfo(e)
        val shown = commander.overrides.value[KEY_TARGET]?.toDouble() ?: info.target ?: return
        val next = (shown + direction * info.step).coerceIn(info.min, info.max)
        commander.slide(e, KEY_TARGET, next.toFloat()) { setTemperatureCommand(it.toDouble()) }
    }

    fun cycleHvacMode() {
        val e = current() ?: return
        nextHvacMode(e, climateInfo(e))?.let { commander.send(e, setHvacModeCommand(it)) }
    }

    fun requestLock(lock: Boolean) {
        val e = current() ?: return
        val target = if (lock) "locked" else "unlocked"
        _confirmation.value = PendingConfirmation("${e.friendlyName} → $target", lock)
    }

    fun confirm() {
        val pending = _confirmation.value ?: return
        _confirmation.value = null
        val e = current() ?: return
        commander.send(e, lockCommand(pending.lock))
    }

    fun cancelConfirmation() {
        _confirmation.value = null
    }

    fun media(service: String, expectStateChange: Boolean = false) {
        val e = current() ?: return
        commander.send(e, mediaCommand(service, expectStateChange))
    }

    fun setVolume(fraction: Float) = slide(KEY_VOLUME, fraction) { setVolumeCommand(it) }

    private fun slide(key: String, value: Float, command: (Float) -> Command) {
        val e = current() ?: return
        commander.slide(e, key, value, command)
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        session.start()
    }

    override fun onAppPause() {
        super.onAppPause()
        session.stop()
    }

    companion object {
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_KELVIN = "kelvin"
        const val KEY_HUE = "hue"
        const val KEY_FAN = "fan"
        const val KEY_POSITION = "position"
        const val KEY_TARGET = "target"
        const val KEY_VOLUME = "volume"
    }
}

private fun fractionToKelvin(fraction: Float, min: Int, max: Int): Int =
    (min + fraction * (max - min)).roundToInt()

private fun kelvinToFraction(kelvin: Int, min: Int, max: Int): Float =
    if (max <= min) 0f else ((kelvin - min).toFloat() / (max - min)).coerceIn(0f, 1f)

/** One entity: its state, then the controls its domain supports. */
class EntityScreen(
    sealedActivity: SealedLightActivity,
    private val entityId: String,
    /** The name shown on the row that opened this screen (card name or the user's name for it). */
    private val title: String,
) : LightScreen<Unit, EntityViewModel>(sealedActivity) {

    override val viewModelClass: Class<EntityViewModel>
        get() = EntityViewModel::class.java

    override fun createViewModel() = EntityViewModel(AppGraph.session(lightContext), entityId)

    @Composable
    override fun Content() {
        val entity by viewModel.entity.collectAsState()
        val status by viewModel.status.collectAsState()
        val pending by viewModel.commander.pending.collectAsState()
        val overrides by viewModel.commander.overrides.collectAsState()
        val error by viewModel.commander.error.collectAsState()
        val confirmation by viewModel.confirmation.collectAsState()

        HomeTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                val e = entity
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text(title),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                    StatusLine(status)
                    if (e == null) {
                        CenteredMessage(
                            text = "Entity not found",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else {
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            StateHeader(e, pending[e.entityId])
                            if (e.state != "unavailable") {
                                Controls(e, overrides, viewModel)
                            }
                        }
                        if (e.state != "unavailable") {
                            BottomActions(e, overrides, viewModel)
                        }
                    }
                }

                confirmation?.let { ConfirmOverlay(it, onConfirm = viewModel::confirm, onCancel = viewModel::cancelConfirmation) }
                error?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel.commander::dismissError)
                }
            }
        }
    }
}

@Composable
private fun StateHeader(entity: EntityState, pendingLabel: String?) {
    val text = pendingLabel ?: formatState(entity).ifEmpty { entity.state.replace('_', ' ') }
    LightText(text = text, variant = LightTextVariant.Heading)
    entity.lastChanged?.let {
        LightText(
            text = "Last change ${formatTimestamp(it)}",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
    }
    if (entity.domain == "media_player") {
        listOfNotNull(entity.attributes.string("media_title"), entity.attributes.string("media_artist"))
            .forEach { LightText(text = it, variant = LightTextVariant.Copy) }
    }
}

@Composable
private fun Controls(entity: EntityState, overrides: Map<String, Float>, vm: EntityViewModel) {
    when (entity.domain) {
        "light" -> LightControls(entity, overrides, vm)
        "fan" -> fanPercentage(entity)?.let { speed ->
            val value = overrides[EntityViewModel.KEY_FAN] ?: speed
            SliderRow("Speed", "${(value * 100).roundToInt()}%", value, vm::setFanSpeed)
        }
        "cover" -> if (coverCapabilities(entity).position) {
            val value = overrides[EntityViewModel.KEY_POSITION] ?: coverPosition(entity) ?: 0f
            SliderRow("Position", "${(value * 100).roundToInt()}% open", value, vm::setCoverPosition)
        }
        "climate" -> ClimateDetails(entity, overrides)
        "media_player" -> mediaVolume(entity)?.let { volume ->
            val value = overrides[EntityViewModel.KEY_VOLUME] ?: volume
            SliderRow("Volume", "${(value * 100).roundToInt()}%", value, vm::setVolume)
        }
    }
}

/** Brightness, color temperature and hue, each only when the light supports it. */
@Composable
private fun LightControls(light: EntityState, overrides: Map<String, Float>, vm: EntityViewModel) {
    val caps = lightCapabilities(light)
    if (caps.brightness) {
        val value = overrides[EntityViewModel.KEY_BRIGHTNESS] ?: lightBrightness(light)
        SliderRow("Brightness", "${(value * 100).roundToInt()}%", value, vm::setBrightness)
    }
    if (caps.colorTemperature) {
        val current = lightKelvin(light)
        val value = overrides[EntityViewModel.KEY_KELVIN]
            ?: current?.let { kelvinToFraction(it, caps.minKelvin, caps.maxKelvin) }
            ?: 0.5f
        val kelvin = fractionToKelvin(value, caps.minKelvin, caps.maxKelvin)
        // HA reports no color temperature while the light shows a color; say so instead of a stale number.
        val label = if (current == null && EntityViewModel.KEY_KELVIN !in overrides) "–" else "$kelvin K, ${kelvinName(kelvin)}"
        SliderRow("Color temperature", label, value, vm::setKelvinFraction)
    }
    if (caps.hue) {
        val hue = lightHue(light)
        val value = overrides[EntityViewModel.KEY_HUE] ?: hue?.let { it / 360f } ?: 0f
        val degrees = value * 360f
        val label = if (hue == null && EntityViewModel.KEY_HUE !in overrides) "–" else "${degrees.roundToInt()}°, ${hueName(degrees)}"
        SliderRow("Hue", label, value, vm::setHueFraction)
    }
}

@Composable
private fun ClimateDetails(climate: EntityState, overrides: Map<String, Float>) {
    val info = climateInfo(climate)
    info.current?.let { DetailLine("Current", "${formatNumber(it)}°") }
    val target = overrides[EntityViewModel.KEY_TARGET]?.toDouble() ?: info.target
    when {
        target != null -> DetailLine("Target", "${formatNumber(target)}°")
        info.targetLow != null && info.targetHigh != null ->
            DetailLine("Range", "${formatNumber(info.targetLow)}–${formatNumber(info.targetHigh)}°")
    }
    climate.attributes.string("hvac_action")?.let { DetailLine("Activity", it.replace('_', ' ')) }
}

@Composable
private fun BottomActions(entity: EntityState, overrides: Map<String, Float>, vm: EntityViewModel) {
    val items: List<LightBarButton?> = when (entity.domain) {
        "light", "fan", "switch", "input_boolean" -> listOf(
            LightBarButton.LightIcon(
                icon = if (entity.state == "on") LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                onClick = vm::toggle,
                contentDescription = if (entity.state == "on") "On" else "Off",
            ),
        )
        "scene", "script" -> listOf(LightBarButton.Text("RUN", onClick = vm::run))
        "button", "input_button" -> listOf(LightBarButton.Text("PRESS", onClick = vm::run))
        "cover" -> {
            val caps = coverCapabilities(entity)
            listOf(
                if (caps.open) LightBarButton.Text("OPEN", onClick = { vm.cover("open_cover") }) else null,
                if (caps.stop) LightBarButton.Text("STOP", onClick = { vm.cover("stop_cover") }) else null,
                if (caps.close) LightBarButton.Text("CLOSE", onClick = { vm.cover("close_cover") }) else null,
            )
        }
        "climate" -> {
            val info = climateInfo(entity)
            val hasTarget = (overrides[EntityViewModel.KEY_TARGET]?.toDouble() ?: info.target) != null
            listOf(
                if (hasTarget) LightBarButton.Text("−", contentDescription = "Lower", onClick = { vm.stepTarget(-1) }) else null,
                if (info.modes.size > 1) {
                    LightBarButton.Text(entity.state.replace('_', ' ').uppercase(), contentDescription = "Mode", onClick = vm::cycleHvacMode)
                } else {
                    null
                },
                if (hasTarget) LightBarButton.Text("+", contentDescription = "Higher", onClick = { vm.stepTarget(1) }) else null,
            )
        }
        "lock" -> listOf(
            if (entity.state == "locked") {
                LightBarButton.Text("UNLOCK", onClick = { vm.requestLock(false) })
            } else {
                LightBarButton.Text("LOCK", onClick = { vm.requestLock(true) })
            },
        )
        "media_player" -> if (entity.state == "off") {
            emptyList()
        } else {
            listOf(
                LightBarButton.LightIcon(LightIcons.REWIND, onClick = { vm.media("media_previous_track") }, contentDescription = "Previous"),
                LightBarButton.LightIcon(
                    icon = if (entity.state == "playing") LightIcons.PAUSE else LightIcons.PLAY,
                    onClick = { vm.media("media_play_pause", expectStateChange = true) },
                    contentDescription = if (entity.state == "playing") "Pause" else "Play",
                ),
                LightBarButton.LightIcon(LightIcons.FAST_FORWARD, onClick = { vm.media("media_next_track") }, contentDescription = "Next"),
            )
        }
        else -> emptyList()
    }
    // Read-only entities (and a cover with no supported actions) get no bar at all.
    if (items.any { it != null }) LightBottomBar(items = items)
}

@Composable
private fun SliderRow(label: String, valueText: String, fraction: Float, onChange: (Float) -> Unit) {
    LightText(
        text = "$label  $valueText",
        variant = LightTextVariant.Detail,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
    )
    LightTouchableProgressBar(
        colors = LightThemeTokens.colors,
        progress = fraction,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    LightText(text = "$label  $value", variant = LightTextVariant.Copy)
}

/** Full-screen yes/no for actions that shouldn't happen by accident (locks). */
@Composable
private fun ConfirmOverlay(confirmation: PendingConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        CenteredMessage(
            text = confirmation.message,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(LightIcons.DENY, onClick = onCancel, contentDescription = "Cancel"),
                null,
                LightBarButton.LightIcon(LightIcons.ACCEPT, onClick = onConfirm, contentDescription = "Confirm"),
            ),
        )
    }
}

/** Today: just the time. Earlier: date and time. Locale formats. */
private fun formatTimestamp(epochSeconds: Double): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(zone)
    val time = moment.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return if (moment.toLocalDate() == LocalDate.now(zone)) {
        time
    } else {
        moment.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}
