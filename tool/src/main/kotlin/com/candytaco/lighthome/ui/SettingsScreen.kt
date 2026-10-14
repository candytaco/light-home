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
import com.candytaco.lighthome.domain.Destination
import com.candytaco.lighthome.domain.ListSection
import com.candytaco.lighthome.domain.SettingsSnapshot
import com.candytaco.lighthome.domain.settingsSections
import com.candytaco.lighthome.ha.HaSession
import com.candytaco.lighthome.ha.describeError
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val session: HaSession) : LightViewModel<Unit>() {
    val server = session.server

    val sections: StateFlow<List<ListSection>> = combine(
        session.server,
        session.signedIn,
        session.userName,
        session.status,
        session.dashboardData,
    ) { server, signedIn, userName, status, dashboards ->
        SettingsSnapshot(server, signedIn, userName, status, dashboards).settingsSections()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    fun selectDashboard(urlPath: String?) = session.selectDashboard(urlPath)

    fun applySetupCode(text: String) = launchSafely {
        if (!session.applySetupCode(text)) _error.value = "Unrecognized setup code"
    }

    fun setServerUrl(url: String) = launchSafely {
        if (!session.setServerUrl(url)) _error.value = "Invalid address (http:// or https:// required)"
    }

    fun setCfAccess(clientId: String, clientSecret: String) = launchSafely { session.setCfAccess(clientId, clientSecret) }

    fun signOut() = launchSafely { session.signOut() }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = describeError(e)
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        session.start()
    }

    override fun onAppPause() {
        super.onAppPause()
        session.stop()
    }
}

/** Dashboard choice, server address, Cloudflare access key, and the Home Assistant account. */
class SettingsScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(AppGraph.session(lightContext))

    private fun open(destination: Destination) {
        when (destination) {
            is Destination.SelectDashboard -> viewModel.selectDashboard(destination.urlPath)
            Destination.ScanSetupCode -> navigateTo({ SetupScanScreen(it) }) { text -> viewModel.applySetupCode(text) }
            Destination.EditServerUrl -> navigateTo({
                TextEntryScreen(it, "Server address", viewModel.server.value?.baseUrl ?: "https://", secret = false)
            }) { url -> viewModel.setServerUrl(url) }
            Destination.EditCfAccess -> navigateTo({
                TextEntryScreen(it, "Access key ID", viewModel.server.value?.cfAccess?.clientId.orEmpty(), secret = false)
            }) { clientId ->
                navigateTo({ TextEntryScreen(it, "Access key secret", "", secret = true) }) { secret ->
                    viewModel.setCfAccess(clientId, secret)
                }
            }
            Destination.RemoveCfAccess -> navigateTo({ ConfirmScreen(it, "Cloudflare access key removal") }) { confirmed ->
                if (confirmed) viewModel.setCfAccess("", "")
            }
            Destination.SignIn -> navigateTo({ SignInScreen(it) })
            Destination.SignOut -> navigateTo({ ConfirmScreen(it, "Home Assistant sign-out") }) { confirmed ->
                if (confirmed) viewModel.signOut()
            }
            else -> Unit
        }
    }

    @Composable
    override fun Content() {
        val sections by viewModel.sections.collectAsState()
        val error by viewModel.error.collectAsState()
        val hapticsEnabled = LocalHapticsEnabled.current
        val haptic = { if (hapticsEnabled) sealedActivity.vibrator.click() }

        HomeTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }, contentDescription = "Back"),
                        center = LightTopBarCenter.Text("Settings"),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        sections.forEach { section ->
                            SectionView(
                                section = section,
                                onEntityClick = {},
                                onEntityLongClick = {},
                                onLinkClick = { row ->
                                    haptic()
                                    open(row.destination)
                                },
                            )
                        }
                    }
                }
                error?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissError) }
            }
        }
    }
}

/**
 * Camera scanner for a setup QR code (made with tools/make_setup_qr.py); returns the code's text.
 * The SDK scanner asks for the camera permission itself.
 */
class SetupScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    /** The scanner keeps reporting while the code is in view; only the first one counts. */
    private var delivered = false

    @Composable
    override fun Content() {
        HomeTheme {
            LightQrCodeScanner(
                title = "Setup code",
                onScanned = { text ->
                    if (!delivered) {
                        delivered = true
                        goBack(text)
                    }
                },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}

/** One text field on the LP3 keyboard; returns the entered text, or nothing on back. */
class TextEntryScreen(
    private val sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String,
    private val secret: Boolean,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val hapticsEnabled = LocalHapticsEnabled.current
        HomeTheme {
            FieldEditor(
                title = title,
                initialValue = initialValue,
                secret = secret,
                submitLabel = "SAVE",
                editorKey = "settings-$title",
                onSubmit = { value -> goBack(value.trim()) },
                onBack = { goBack() },
                onHaptic = { if (hapticsEnabled) sealedActivity.vibrator.click() },
            )
        }
    }
}

/** Full-screen yes/no for a setting change that's hard to undo. Returns true on confirm. */
class ConfirmScreen(sealedActivity: SealedLightActivity, private val message: String) :
    SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        HomeTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                CenteredMessage(
                    text = message,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(LightIcons.DENY, onClick = { goBack(false) }, contentDescription = "Cancel"),
                        null,
                        LightBarButton.LightIcon(LightIcons.ACCEPT, onClick = { goBack(true) }, contentDescription = "Confirm"),
                    ),
                )
            }
        }
    }
}
