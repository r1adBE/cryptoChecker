package com.cryptochecker.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme

/**
 * Gleicher Anfang im ⋯-Menü von Merkliste, Markt und Portfolio: App-Logo und Name (öffnet
 * «Über»), Trennlinie, «Aktualisieren» (läuft es, dreht ein Kreis statt des Symbols). Danach
 * folgen die eigenen Einträge des Tabs. [onClose] schliesst das Menü vor jeder Aktion.
 */
@Composable
fun AppMenuHead(
    refreshing: Boolean,
    onClose: () -> Unit,
    onOpenAbout: () -> Unit,
    onRefresh: () -> Unit,
    /** Zusätzlich gesperrt (z. B. eben erst aktualisiert); grau statt einer Meldung. */
    refreshEnabled: Boolean = true,
) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        leadingIcon = {
            Image(
                painter = painterResource(LocalAccentColor.current.logoRes(LocalDarkTheme.current)),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        },
        onClick = { onClose(); onOpenAbout() }
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_refresh)) },
        leadingIcon = {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(R.drawable.ic_refresh), null)
            }
        },
        enabled = !refreshing && refreshEnabled,
        onClick = { onClose(); onRefresh() }
    )
}
