// Zeilen der Auswahlliste von DropdownField: Abschnittstitel, Unterzeile, Höhe und Eintrag.
package com.cryptochecker.app.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.DisabledColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

/** Titelzeile eines Abschnitts: nicht wählbar, für Bedienungshilfen eine Überschrift (mit Unterzeile als eine). */
@Composable
internal fun ComboHeader(title: String, subtitle: String?, rowHeight: Dp) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) ComboSubtitle(subtitle)
    }
}

/** Kleine zweite Zeile unter Eintrag oder Abschnittstitel; liest der Screenreader mit der ersten. */
@Composable
internal fun ComboSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
}

/** Zeilenhöhe: eine Textzeile bodyLarge plus Innenabstand, nie unter 48 dp (wächst mit der Schrift). */
@Composable
internal fun comboRowHeight(): Dp {
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    return maxOf(RowHeight, lineHeight + 16.dp)
}

/**
 * Eine Zeile: eigene Zeile statt DropdownMenuItem, weil dieses kein langes Drücken kennt. Der
 * Favorit lässt sich per Stern, langem Drücken oder Bedienungshilfe umschalten.
 */
@Composable
internal fun ComboRow(
    entry: ComboEntry,
    selected: Boolean,
    favorite: Boolean,
    subtitle: String?,
    rowHeight: Dp,
    onChoose: (Int) -> Unit,
    onToggleFavorite: ((String) -> Unit)?,
) {
    val haptics = rememberHaptics()
    val toggleLabel = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add)

    var rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = rowHeight)
    if (selected) rowModifier = rowModifier.background(AppColors.accentTint(0.3f))
    rowModifier = rowModifier
        .semantics {
            if (onToggleFavorite != null) {
                customActions = listOf(
                    CustomAccessibilityAction(toggleLabel) {
                        onToggleFavorite(entry.text)
                        true
                    },
                )
            }
        }
        .combinedClickable(
            onClick = { onChoose(entry.index) },
            onLongClick = onToggleFavorite?.let { toggle ->
                {
                    haptics.play(HapticEvent.LONG_PRESS)
                    toggle(entry.text)
                }
            },
        )
        .padding(start = 12.dp, end = 4.dp)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyLarge,
                // Eine Zeile mit «…»: die Listenhöhe rechnet mit einer Textzeile je Eintrag (Unterzeile: Zeile wächst)
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) ComboSubtitle(subtitle)
        }
        when {
            // Stern direkt in der Zeile; langes Drücken bleibt
            onToggleFavorite != null -> IconButton(
                onClick = { onToggleFavorite(entry.text) },
                // Tippfläche so hoch wie die Zeile, das Symbol bleibt 20 dp
                modifier = Modifier.size(RowHeight),
            ) {
                Icon(
                    painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline),
                    contentDescription = toggleLabel,
                    // Leerer Stern auf der getönten gewählten Zeile in onSurfaceVariant: outline hielt dort < 3:1
                    tint = when {
                        favorite -> MaterialTheme.colorScheme.primary
                        selected -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            // Symbol statt «★»: TalkBack las das Zeichen als «schwarzer Stern»
            favorite -> Icon(
                painterResource(R.drawable.ic_star),
                contentDescription = stringResource(R.string.a11y_favorite),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = Spacing.sm).size(16.dp),
            )
        }
    }
}
