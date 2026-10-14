package com.candytaco.lighthome.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.candytaco.lighthome.AppGraph
import com.candytaco.lighthome.domain.Destination
import com.candytaco.lighthome.domain.HomeSnapshot
import com.candytaco.lighthome.domain.ListRow
import com.candytaco.lighthome.domain.ListSection
import com.candytaco.lighthome.domain.tapCommand
import com.candytaco.lighthome.ha.HaSession
import com.candytaco.lighthome.ha.SessionStatus
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
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Live sections for one list screen, rebuilt whenever states, registries, settings or pending commands change. */
class SectionedListViewModel(
    private val session: HaSession,
    build: HomeSnapshot.() -> List<ListSection>,
) : LightViewModel<Unit>() {
    val status: StateFlow<SessionStatus> = session.status
    val commander = EntityCommander(session, viewModelScope)

    val sections: StateFlow<List<ListSection>> = combine(
        session.registry,
        session.entities,
        session.homeConfig,
        commander.pending,
        session.dashboardData,
    ) { registry, states, config, pending, dashboardData ->
        if (registry == null) emptyList() else HomeSnapshot(registry, states, config, pending, dashboardData).build()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Runs the row's tap command (toggle, run, press). Rows that open a detail screen don't get here. */
    fun tap(entityId: String) {
        val entity = session.entities.value[entityId] ?: return
        tapCommand(entity)?.let { commander.send(entity, it) }
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

/**
 * A titled screen of sections: entity rows (tap acts or opens detail, long-press on lights and
 * fans opens detail) and link rows that open other screens. Home, area, summary and device lists
 * are all this screen with a different section builder.
 */
open class SectionedListScreen(
    private val sealedActivity: SealedLightActivity,
    private val title: String,
    private val showBack: Boolean,
    private val emptyText: String,
    private val build: HomeSnapshot.() -> List<ListSection>,
    /** Gear button in the bottom bar, opening [SettingsScreen] (Home only). */
    private val showSettings: Boolean = false,
) : LightScreen<Unit, SectionedListViewModel>(sealedActivity) {

    override val viewModelClass: Class<SectionedListViewModel>
        get() = SectionedListViewModel::class.java

    override fun createViewModel() = SectionedListViewModel(AppGraph.session(lightContext), build)

    private fun open(destination: Destination) {
        when (destination) {
            is Destination.Area -> navigateTo({ AreaScreen(it, destination.areaId, destination.name) })
            is Destination.SummaryList -> navigateTo({ SummaryScreen(it, destination.summary) })
            Destination.OtherDevices -> navigateTo({ OtherDevicesScreen(it) })
            is Destination.EntityDetail -> navigateTo({ EntityScreen(it, destination.entityId, destination.title) })
            is Destination.View -> navigateTo({ DashboardViewScreen(it, destination.path, destination.title) })
            Destination.SignIn -> navigateTo({ SignInScreen(it) })
            // Only rows on the settings screen lead to these.
            is Destination.SelectDashboard, Destination.ScanSetupCode, Destination.EditServerUrl,
            Destination.EditCfAccess, Destination.RemoveCfAccess, Destination.SignOut -> Unit
        }
    }

    @Composable
    override fun Content() {
        val status by viewModel.status.collectAsState()
        val sections by viewModel.sections.collectAsState()
        val error by viewModel.commander.error.collectAsState()
        val hapticsEnabled = LocalHapticsEnabled.current
        val haptic = { if (hapticsEnabled) sealedActivity.vibrator.click() }

        HomeTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = if (showBack) {
                            LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }, contentDescription = "Back")
                        } else {
                            null
                        },
                        center = LightTopBarCenter.Text(title),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                    if (sections.isEmpty()) {
                        CenteredMessage(
                            text = statusMessage(status) ?: emptyText,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else {
                        StatusLine(status)
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = 1f.gridUnitsAsDp()),
                        ) {
                            sections.forEach { section ->
                                SectionView(
                                    section = section,
                                    onEntityClick = { row ->
                                        haptic()
                                        if (row.tapOpensDetail) {
                                            open(Destination.EntityDetail(row.entityId, row.name))
                                        } else {
                                            viewModel.tap(row.entityId)
                                        }
                                    },
                                    onEntityLongClick = { row ->
                                        haptic()
                                        open(Destination.EntityDetail(row.entityId, row.name))
                                    },
                                    onLinkClick = { row ->
                                        haptic()
                                        open(row.destination)
                                    },
                                )
                            }
                        }
                    }
                    // The one thing to do next, when there is one: set up a server, or sign in.
                    val nextStep = when (status) {
                        is SessionStatus.NeedsSetup ->
                            LightBarButton.Text(text = "SET UP", onClick = { navigateTo({ SettingsScreen(it) }) })
                        is SessionStatus.NeedsSignIn ->
                            LightBarButton.Text(text = "SIGN IN", onClick = { navigateTo({ SignInScreen(it) }) })
                        else -> null
                    }
                    when {
                        showSettings -> LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.SETTINGS,
                                    onClick = { navigateTo({ SettingsScreen(it) }) },
                                    contentDescription = "Settings",
                                ),
                                nextStep,
                                null,
                            ),
                        )
                        nextStep != null -> LightBottomBar(items = listOf(nextStep))
                    }
                }

                error?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel.commander::dismissError)
                }
            }
        }
    }
}

@Composable
internal fun SectionView(
    section: ListSection,
    onEntityClick: (ListRow.Entity) -> Unit,
    onEntityLongClick: (ListRow.Entity) -> Unit,
    onLinkClick: (ListRow.Link) -> Unit,
) {
    section.title?.let {
        LightText(
            text = it,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
        )
    }
    section.rows.forEach { row ->
        when (row) {
            is ListRow.Entity -> RowView(
                name = row.name,
                value = row.value,
                onClick = { onEntityClick(row) },
                onLongClick = if (row.longPressOpensDetail) ({ onEntityLongClick(row) }) else null,
            )
            is ListRow.Link -> RowView(
                name = row.label,
                value = row.value,
                onClick = { onLinkClick(row) },
                onLongClick = null,
            )
            is ListRow.Text -> LightText(
                text = row.text,
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
            )
            is ListRow.Info -> RowView(name = row.label, value = row.value, onClick = null, onLongClick = null)
        }
    }
}

/** Name on the left, value on the right; the whole row is the touch target (when [onClick] is set). */
@Composable
private fun RowView(name: String, value: String, onClick: (() -> Unit)?, onLongClick: (() -> Unit)?) {
    val clickable = if (onClick != null) {
        Modifier.combinedClickable(
            interactionSource = null,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            LightText(
                text = value,
                variant = LightTextVariant.Copy,
                lighten = true,
                align = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}
