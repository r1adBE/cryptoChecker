// Teile des Export-Dialogs: Kennzeichen «Beta» der Länder-Vorlagen und die Rückfrage vor der ersten Vorlage.
// Eigene Datei, weil `PortfolioExportDialog` sonst zu lang würde.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.TaxTemplate

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
