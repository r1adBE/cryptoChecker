@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.theme.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Kopfzeile von «Mehr»: ganze Zeile tippbar (48 dp), Pfeil dreht sich. Screenreader: Knopf
 * «Mehr» mit Zustand «aufgeklappt»/«zugeklappt» und der passenden Aktion.
 */
@Composable
internal fun SheetMoreToggle(expanded: Boolean, onToggle: () -> Unit) {
    val title = stringResource(R.string.sheet_more)
    val state = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = state
                role = Role.Button
                onClick { onToggle(); true }
                if (expanded) collapse { onToggle(); true } else expand { onToggle(); true }
            }
            .padding(vertical = 8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) -90f else 90f)
        )
    }
}

/**
 * Grosse Aktion oben im Aktionsblatt (drei nebeneinander): Symbol über kurzem Text.
 * [checked] != null = Umschalter (Favorit); der Screenreader sagt dann «ein»/«aus».
 */
@Composable
internal fun PrimarySheetAction(
    icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
) {
    val action = if (checked != null) {
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onClick() })
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(AppColors.accentTint(if (checked == true) 0.16f else 0.08f))
            .then(action)
            .padding(horizontal = Spacing.xs, vertical = Spacing.md)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
}

/**
 * Vorschau im Aktionsblatt (Paar nicht in der Merkliste): gross «Zur Merkliste hinzufügen» in der
 * Themenfarbe, daneben «Warum?» ([showWhy]) als Kachel wie sonst. [adding]: Knopf gesperrt,
 * «Zur Merkliste hinzugefügt.» bis das Blatt zum gespeicherten Eintrag wechselt.
 */
@Composable
internal fun PreviewSheetActions(
    showWhy: Boolean,
    adding: Boolean,
    onAdd: () -> Unit,
    onWhy: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // Gleich hoch, auch wenn ein Text umbricht
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 12.dp)
    ) {
        Button(
            onClick = onAdd,
            enabled = !adding,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
            modifier = Modifier.weight(2f).fillMaxHeight().heightIn(min = 72.dp)
        ) {
            Icon(
                painterResource(if (adding) R.drawable.ic_check else R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = stringResource(if (adding) R.string.explorer_added_to_watchlist else R.string.explorer_add_to_watchlist),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
        if (showWhy) {
            PrimarySheetAction(
                icon = R.drawable.ic_lightbulb,
                text = stringResource(R.string.watch_action_why),
                onClick = onWhy,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun SheetAction(icon: Int, text: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.lg)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (danger) color else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
