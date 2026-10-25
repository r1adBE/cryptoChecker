// Teile von Stichtag-Export und Arbeitsunterlage: Kennzeichen «Beta» der Länder-Vorlagen, die Rückfrage vor der ersten
// Vorlage, Fortschritt eines laufenden Exports und die Fehlermeldung mit «Erneut versuchen».
// Eigene Datei, weil `PortfolioExportDialog` und `TaxWorksheetScreen` sie beide brauchen.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.TaxTemplate
import com.cryptochecker.app.ui.UiTestTags

/** Kleine Kapsel «Beta» neben dem Namen einer Länder-Vorlage (Regeln vereinfacht). */
@Composable
internal fun TaxBetaBadge() {
    Text(
        stringResource(R.string.tax_template_beta),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/**
 * Länder-Vorlage gewählt und der Hinweis «Beta, vereinfachte Regeln, Transaktionen anderer Plattformen fehlen – vor der
 * Steuererklärung prüfen» noch nie bestätigt: Dialog mit OK (einmal; Zurück schliesst ihn nur bis zum nächsten Mal).
 */
@Composable
internal fun TaxTemplateConfirmDialog(template: TaxTemplate, confirmed: Boolean, onConfirm: () -> Unit) {
    var dismissed by remember(template) { mutableStateOf(false) }
    if (template == TaxTemplate.NEUTRAL || confirmed || dismissed) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.tax_template_confirm_title)) },
        text = { Text(stringResource(R.string.tax_template_confirm_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) } },
    )
}

/** Laufender Export: Kreis und «Historische Kurse werden geladen…» (abbrechen über «Abbrechen» des Aufrufers). */
@Composable
internal fun ExportRunningRow(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.portfolio_export_running),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/**
 * Export fehlgeschlagen: Text nach der Art ([ExportFailureKind]: Datei, Kurse, sonstiges) und «Erneut versuchen».
 * TalkBack liest die Meldung vor, sobald sie erscheint.
 */
@Composable
internal fun ExportFailureRow(kind: ExportFailureKind, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            stringResource(kind.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag(UiTestTags.EXPORT_RETRY)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
