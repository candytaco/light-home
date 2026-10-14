package com.candytaco.lighthome.domain

import com.candytaco.lighthome.ha.DashboardData
import com.candytaco.lighthome.ha.SessionStatus
import com.candytaco.lighthome.net.ServerConfig

/** What the settings screen shows, captured at one moment. */
data class SettingsSnapshot(
    val server: ServerConfig?,
    val signedIn: Boolean,
    val userName: String?,
    val status: SessionStatus,
    val dashboardData: DashboardData,
)

/** Dashboard choice, server connection (address, Cloudflare key, state), and the HA account. */
fun SettingsSnapshot.settingsSections(): List<ListSection> {
    val cf = server?.cfAccess
    return listOf(
        ListSection("Dashboard", dashboardRows(dashboardData)),
        ListSection(
            "Server",
            listOfNotNull(
                ListRow.Link("Scan setup code", "", Destination.ScanSetupCode),
                ListRow.Link(
                    label = "Address",
                    value = server?.baseUrl ?: "none",
                    destination = Destination.EditServerUrl,
                    subtitle = connectionText(status),
                ),
                ListRow.Link("Cloudflare access key", if (cf != null) "set" else "none", Destination.EditCfAccess),
                if (cf != null) ListRow.Link("Remove Cloudflare access key", "", Destination.RemoveCfAccess) else null,
                ListRow.Info("Connection", connectionText(status)),
            ),
        ),
        ListSection(
            "Home Assistant",
            if (signedIn) {
                listOf(
                    ListRow.Info("Account", userName ?: "signed in"),
                    ListRow.Link("Sign out", "", Destination.SignOut),
                )
            } else {
                listOf(ListRow.Link("Sign in", "", Destination.SignIn))
            },
        ),
    )
}

private fun connectionText(status: SessionStatus): String = when (status) {
    is SessionStatus.Connected -> "connected (HA ${status.haVersion})"
    SessionStatus.Connecting -> "in progress…"
    SessionStatus.Offline -> "no network"
    SessionStatus.Idle -> "idle"
    is SessionStatus.Retrying -> status.message
    is SessionStatus.NeedsSetup -> status.message
    is SessionStatus.NeedsSignIn -> status.message
    is SessionStatus.Blocked -> status.message
}
