package com.candytaco.lighthome.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.candytaco.lighthome.AppGraph
import com.candytaco.lighthome.ha.FormField
import com.candytaco.lighthome.ha.HaSession
import com.candytaco.lighthome.ha.LoginStep
import com.candytaco.lighthome.ha.describeError
import com.candytaco.lighthome.ha.loginErrorMessage
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SignInUi {
    data object Working : SignInUi

    /** A text field: username, password, an MFA code, … */
    data class Entry(
        val field: FormField,
        val initialValue: String,
        val isLast: Boolean,
        /** Changes for every field shown, so each gets fresh keyboard state. */
        val editorKey: String,
    ) : SignInUi

    /** A `select` field, e.g. choosing an MFA module. */
    data class Choice(val field: FormField) : SignInUi

    data object Done : SignInUi
}

/**
 * Walks Home Assistant's login flow one field per screen. Each form step's fields come from HA's
 * `data_schema`, so username/password, MFA module choice and MFA code all use the same path.
 */
class SignInViewModel(private val session: HaSession) : LightViewModel<Boolean>() {
    private val _ui = MutableStateFlow<SignInUi>(SignInUi.Working)
    val ui: StateFlow<SignInUi> = _ui.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var form: LoginStep.Form? = null
    private var fieldIndex = 0
    private val values = mutableMapOf<String, String>()
    private var shown = 0

    /** After a network failure or abort, the flow may be gone on HA's side; start a new one. */
    private var restartOnDismiss = false

    init {
        begin()
    }

    private fun begin() {
        form = null
        _ui.value = SignInUi.Working
        launchStep { session.startSignIn() }
    }

    fun submitField(value: String) {
        val current = form ?: return
        val field = current.fields.getOrNull(fieldIndex) ?: return
        if (field.required && value.isBlank()) {
            _error.value = "${fieldLabel(field.name)} required"
            return
        }
        values[field.name] = value
        if (fieldIndex < current.fields.lastIndex) {
            fieldIndex++
            showField()
        } else {
            _ui.value = SignInUi.Working
            val submitted = current.fields.associate { it.name to values[it.name].orEmpty() }
            launchStep { session.submitSignIn(current.flowId, submitted) }
        }
    }

    /**
     * Back (top-bar arrow or system gesture) steps to the previous field; on the first field it
     * isn't consumed, so the screen closes.
     */
    override fun onBackPressed(): Boolean {
        if (fieldIndex == 0 || form == null || _ui.value is SignInUi.Working) return false
        fieldIndex--
        showField()
        return true
    }

    fun dismissError() {
        _error.value = null
        if (restartOnDismiss) {
            restartOnDismiss = false
            begin()
        }
    }

    private fun launchStep(call: suspend () -> LoginStep) {
        viewModelScope.launch {
            try {
                handle(call())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restartOnDismiss = true
                _error.value = describeError(e)
            }
        }
    }

    private suspend fun handle(step: LoginStep) {
        when (step) {
            is LoginStep.Form -> {
                form = step
                fieldIndex = 0
                if (step.errors.isNotEmpty()) {
                    // Same step again (e.g. wrong password): keep the username, clear secrets.
                    step.fields.filter { it.isSecret }.forEach { values.remove(it.name) }
                    _error.value = loginErrorMessage(step.errors.values.first())
                }
                if (step.fields.isEmpty()) {
                    // A step with nothing to fill in: just submit it.
                    _ui.value = SignInUi.Working
                    handle(session.submitSignIn(step.flowId, emptyMap()))
                } else {
                    showField()
                }
            }
            is LoginStep.Done -> {
                session.finishSignIn(step.authCode)
                _ui.value = SignInUi.Done
            }
            is LoginStep.Aborted -> {
                restartOnDismiss = true
                _error.value = "Sign-in aborted (${step.reason})"
            }
        }
    }

    private fun showField() {
        val current = form ?: return
        val field = current.fields[fieldIndex]
        shown++
        _ui.value = if (field.isSelect) {
            SignInUi.Choice(field)
        } else {
            SignInUi.Entry(
                field = field,
                initialValue = values[field.name].orEmpty(),
                isLast = fieldIndex == current.fields.lastIndex,
                editorKey = "${current.flowId}-${current.stepId}-${field.name}-$shown",
            )
        }
    }
}

class SignInScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SignInViewModel>(sealedActivity) {

    override val viewModelClass: Class<SignInViewModel>
        get() = SignInViewModel::class.java

    override fun createViewModel() = SignInViewModel(AppGraph.session(lightContext))

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val error by viewModel.error.collectAsState()
        val hapticsEnabled = LocalHapticsEnabled.current

        LaunchedEffect(ui) {
            if (ui is SignInUi.Done) goBack(true)
        }

        HomeTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = ui) {
                    SignInUi.Working, SignInUi.Done -> Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(center = LightTopBarCenter.Text("Home Assistant"))
                        CenteredMessage(
                            text = "Sign-in in progress…",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                    is SignInUi.Entry -> key(state.editorKey) {
                        FieldEditor(
                            title = fieldLabel(state.field.name),
                            initialValue = state.initialValue,
                            secret = state.field.isSecret,
                            submitLabel = if (state.isLast) "SIGN IN" else "NEXT",
                            editorKey = state.editorKey,
                            onSubmit = viewModel::submitField,
                            onBack = { goBack(false) },
                            onHaptic = { if (hapticsEnabled) sealedActivity.vibrator.click() },
                        )
                    }
                    is SignInUi.Choice -> ChoiceList(
                        field = state.field,
                        onChoose = viewModel::submitField,
                        onBack = { goBack(false) },
                    )
                }

                error?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun ChoiceList(field: FormField, onChoose: (String) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = "Back"),
            center = LightTopBarCenter.Text(fieldLabel(field.name)),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            field.options.forEach { (value, label) ->
                LightText(
                    text = label,
                    variant = LightTextVariant.Heading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(onClick = { onChoose(value) })
                        .padding(vertical = 0.6f.gridUnitsAsDp()),
                )
            }
        }
    }
}

/** Title for an HA login-flow field. */
private fun fieldLabel(name: String): String = when (name) {
    "username" -> "Username"
    "password" -> "Password"
    "code" -> "Code"
    "mfa_module" -> "Two-factor method"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}
