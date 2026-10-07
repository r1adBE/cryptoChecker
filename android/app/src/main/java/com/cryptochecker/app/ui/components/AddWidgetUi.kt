@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.widget.WidgetKind
import com.cryptochecker.app.widget.WidgetPinner

/*
 * Runde 13b: Widgets direkt aus der App hinzufügen (requestPinAppWidget). Einstellungen ›
 * Darstellung › «Widgets» öffnet ein Blatt mit allen Widget-Arten samt Vorschaubild; das
 * Aktionen-Blatt der Merkliste legt ein Einzel-Widget für das Paar an. Kann der
 * Startbildschirm das nicht, steht stattdessen eine kurze Anleitung da.
 */

/** Zeile «Widgets» in den Einstellungen; öffnet das Blatt mit den Widget-Arten. */
@Composable
fun WidgetsSettingsRow(portfolioEnabled: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { open = true }
            .padding(vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.settings_widgets), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_widgets_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (open) {
        AddWidgetsSheet(portfolioEnabled = portfolioEnabled, onDismiss = { open = false })
    }
}

/**
 * Blatt mit den Widget-Arten: Merkliste, Einzel-Coin, Portfolio (nur wenn eingeschaltet),
 * Was gerade auffällt — je Vorschaubild, Name, ein Satz und «Hinzufügen». Ohne Unterstützung
 * des Startbildschirms oben die Anleitung und keine Knöpfe.
 */
@Composable
fun AddWidgetsSheet(portfolioEnabled: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val supported = remember { WidgetPinner.isSupported(context) }
    var showManual by remember { mutableStateOf(false) }
    val kinds = WidgetKind.entries.filter { it != WidgetKind.PORTFOLIO || portfolioEnabled }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Gleich in voller Höhe: ändert sich der Inhalt (Laden, Auswahl), springt das Blatt nicht
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            Text(
                stringResource(R.string.widgets_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }
            )
            if (!supported) {
                Text(
                    stringResource(R.string.widgets_manual_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            kinds.forEachIndexed { index, kind ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                WidgetKindRow(
                    kind = kind,
                    showAdd = supported,
                    onAdd = { if (!WidgetPinner.request(context, kind)) showManual = true }
                )
            }
        }
    }
    if (showManual) WidgetManualDialog(onDismiss = { showManual = false })
}

/** Eine Widget-Art: Vorschau links (wie in der Widget-Auswahl), rechts Name, Satz, Knopf. */
@Composable
private fun WidgetKindRow(kind: WidgetKind, showAdd: Boolean, onAdd: () -> Unit) {
    val name = stringResource(kind.nameRes)
    val addLabel = stringResource(R.string.widgets_add_named, name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    ) {
        // Reine Vorschau: Name und Satz daneben sagen dem Screenreader alles
        Image(
            painter = painterResource(kind.previewRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(96.dp).height(80.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(kind.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (showAdd) {
                FilledTonalButton(
                    onClick = onAdd,
                    modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = addLabel }
                ) {
                    Text(stringResource(R.string.widgets_add))
                }
            }
        }
    }
}

/** Kurze Anleitung, wenn der Startbildschirm Widgets nicht aus der App annimmt. */
@Composable
fun WidgetManualDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.widgets_manual_title)) },
        text = { Text(stringResource(R.string.widgets_manual_text)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}
