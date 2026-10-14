package com.candytaco.lighthome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.candytaco.lighthome.ha.SessionStatus
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** LightOS theme (follows the system light/dark choice) on a full-screen background. */
@Composable
fun HomeTheme(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/** Text for a status the user should see, or null when everything is fine. */
fun statusMessage(status: SessionStatus): String? = when (status) {
    SessionStatus.Idle, is SessionStatus.Connected -> null
    SessionStatus.Connecting -> "Connection in progress…"
    SessionStatus.Offline -> "No network"
    is SessionStatus.Retrying -> status.message
    is SessionStatus.NeedsSetup -> status.message
    is SessionStatus.NeedsSignIn -> status.message
    is SessionStatus.Blocked -> status.message
}

/** One fine line under the top bar while connecting or after an error. */
@Composable
fun StatusLine(status: SessionStatus) {
    val message = statusMessage(status) ?: return
    LightText(
        text = message,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp())
            .padding(bottom = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
    }
}
