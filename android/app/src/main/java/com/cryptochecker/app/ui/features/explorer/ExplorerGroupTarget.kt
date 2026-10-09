package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.GroupNameDialog
import com.cryptochecker.app.ui.components.canonicalGroupName
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.Spacing

/** Zustand und Aktion für «In Gruppe» beim Hinzufügen. */
class GroupTargetUi(
    /** Gewählte Gruppe; null = «Keine Gruppe». */
    val target: String? = null,
    /** Bestehende Gruppen der Merkliste. */
    val groups: List<String> = emptyList(),
    val onSelect: (String?) -> Unit = {},
)

/**
 * Kompakte Auswahl «In Gruppe: …» neben den Hinzufügen-Knöpfen.
 * Gilt nur für neue Paare; bereits vorhandene behalten ihre Gruppe.
 */
@Composable
internal fun GroupTargetSelector(ui: GroupTargetUi, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    if (naming) {
        GroupNameDialog(
            title = stringResource(R.string.group_add),
            initial = "",
            confirmText = stringResource(R.string.action_save),
            onConfirm = { name ->
                naming = false
                ui.onSelect(canonicalGroupName(name, ui.groups))
            },
            onDismiss = { naming = false }
        )
    }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { menuOpen = true }
                .padding(start = 12.dp, end = 8.dp, top = Spacing.sm, bottom = Spacing.sm)
        ) {
            Icon(
                painterResource(R.drawable.ic_list),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.group_target),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs)
            )
            Text(
                text = ui.target ?: stringResource(R.string.group_none),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.xs)
            )
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp)
                    .graphicsLayer { rotationZ = 90f }
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val check: @Composable () -> Unit = {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_none)) },
                trailingIcon = if (ui.target == null) check else null,
                onClick = { menuOpen = false; ui.onSelect(null) }
            )
            // Auch eine eben neu benannte (noch leere) Gruppe zur Auswahl anbieten
            val names = (ui.groups + listOfNotNull(ui.target))
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            names.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (ui.target == name) check else null,
                    onClick = { menuOpen = false; ui.onSelect(name) }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_new)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                },
                onClick = { menuOpen = false; naming = true }
            )
        }
    }
}
