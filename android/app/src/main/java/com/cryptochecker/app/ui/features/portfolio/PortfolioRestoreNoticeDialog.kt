package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.PortfolioRestoreNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einmaliger Hinweis beim Öffnen des Portfolios: Einträge aus der Systemsicherung waren ungültig und
 * wurden nicht übernommen ([PortfolioRestoreNotice]). Wie iOS `systemRestoreNotice()`.
 */
@Composable
internal fun SystemRestoreNoticeDialog() {
    val context = LocalContext.current
    // Saveable: die Anzahl ist nach take() gelöscht, der Dialog muss Drehen überstehen
    var invalid by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val count = withContext(Dispatchers.IO) { PortfolioRestoreNotice.take(context.applicationContext) }
        if (count > 0) invalid = count
    }
    if (invalid > 0) {
        AlertDialog(
            onDismissRequest = { invalid = 0 },
            text = { Text(pluralStringResource(R.plurals.portfolio_system_restore_invalid, invalid, invalid)) },
            confirmButton = {
                TextButton(onClick = { invalid = 0 }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}
