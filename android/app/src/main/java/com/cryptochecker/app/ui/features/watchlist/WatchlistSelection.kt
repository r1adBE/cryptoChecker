package com.cryptochecker.app.ui.features.watchlist

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R

/**
 * Leiste der Mehrfachauswahl unten über der Liste: Favorit · Gruppe · Deaktivieren · Löschen für
 * die angehakten Paare. Ohne Auswahl grau. «Favorit» setzt den Stern bei allen — oder nimmt ihn
 * weg, wenn schon alle Favoriten sind ([allFavorites]); «Deaktivieren» ebenso («Aktivieren», wenn
 * alle deaktiviert sind). Symbole wie überall: Stern, Gruppe (Liste, wie «In Gruppe» beim
 * Hinzufügen und im Aktionsblatt), Pause bzw. Abspielen, Papierkorb.
 * Wie `WatchlistSelectionBar` (iOS).
 */
@Composable
internal fun SelectionBar(
    count: Int,
    allFavorites: Boolean,
    onFavorite: () -> Unit,
    onGroup: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Alle angehakten Paare sind deaktiviert: «Aktivieren» statt «Deaktivieren». */
    allDisabled: Boolean = false,
    /** Deaktivieren bzw. (wenn [allDisabled]) wieder aktivieren. */
    onToggleDisabled: () -> Unit = {},
) {
    val enabled = count > 0
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            SelectionAction(
                icon = if (allFavorites) R.drawable.ic_star else R.drawable.ic_star_outline,
                label = stringResource(R.string.a11y_favorite),
                description = stringResource(if (allFavorites) R.string.favorite_remove else R.string.favorite_add),
                enabled = enabled,
                onClick = onFavorite,
            )
            SelectionAction(
                icon = R.drawable.ic_list,
                label = stringResource(R.string.group_title),
                enabled = enabled,
                onClick = onGroup,
            )
            // Deaktivieren (keine Abfragen, keine Meldungen) bzw. wieder aktivieren
            SelectionAction(
                icon = if (allDisabled) R.drawable.ic_play_circle else R.drawable.ic_pause_circle,
                label = stringResource(if (allDisabled) R.string.watch_enable else R.string.watch_disable),
                enabled = enabled,
                onClick = onToggleDisabled,
            )
            SelectionAction(
                icon = R.drawable.ic_delete,
                label = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
                enabled = enabled,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun RowScope.SelectionAction(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Vorgelesen statt [label], z. B. «Zu Favoriten hinzufügen». */
    description: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(vertical = 8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
