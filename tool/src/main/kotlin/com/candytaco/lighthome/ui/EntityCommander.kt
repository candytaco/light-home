package com.candytaco.lighthome.ui

import com.candytaco.lighthome.domain.Command
import com.candytaco.lighthome.ha.EntityState
import com.candytaco.lighthome.ha.HaSession
import com.candytaco.lighthome.ha.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends service calls for a screen and tracks what's in flight.
 *
 * - [send]: one-shot commands (toggle, run, lock…). The entity shows its pending label until
 *   HA reports a new state; no change within [STATE_CHANGE_TIMEOUT_MS] becomes an error.
 * - [slide]: slider drags. The value shows immediately ([overrides]); the call goes out once the
 *   finger pauses, and the override is dropped shortly after HA has had time to report back.
 */
class EntityCommander(
    private val session: HaSession,
    private val scope: CoroutineScope,
) {
    private val _pending = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Entity id to pending label. */
    val pending: StateFlow<Map<String, String>> = _pending.asStateFlow()

    private val _overrides = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Slider key to the value under the user's finger. */
    val overrides: StateFlow<Map<String, Float>> = _overrides.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val sliderJobs = mutableMapOf<String, Job>()

    fun dismissError() {
        _error.value = null
    }

    fun send(entity: EntityState, command: Command) {
        val id = entity.entityId
        if (id in _pending.value) return
        _pending.update { it + (id to command.pendingLabel) }
        scope.launch {
            try {
                session.callService(command.domain, command.service, id, command.data)
                if (command.expectStateChange) {
                    val changed = withTimeoutOrNull(STATE_CHANGE_TIMEOUT_MS) {
                        session.entities.first { it[id]?.state != entity.state }
                    }
                    if (changed == null) _error.value = "${entity.friendlyName}: no state change"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "${entity.friendlyName}: ${describeError(e)}"
            } finally {
                _pending.update { it - id }
            }
        }
    }

    /** [key] identifies the slider, e.g. "brightness". */
    fun slide(entity: EntityState, key: String, value: Float, command: (Float) -> Command) {
        _overrides.update { it + (key to value) }
        sliderJobs[key]?.cancel()
        sliderJobs[key] = scope.launch {
            delay(SLIDER_SETTLE_MS)
            val call = command(value)
            try {
                session.callService(call.domain, call.service, entity.entityId, call.data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "${entity.friendlyName}: ${describeError(e)}"
            }
            delay(OVERRIDE_HOLD_MS)
            _overrides.update { it - key }
        }
    }

    private companion object {
        const val STATE_CHANGE_TIMEOUT_MS = 10_000L
        const val SLIDER_SETTLE_MS = 300L

        /** Keeps the slider where the finger left it until HA's update has arrived. */
        const val OVERRIDE_HOLD_MS = 1_500L
    }
}
