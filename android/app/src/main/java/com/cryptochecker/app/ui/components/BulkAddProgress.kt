package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.util.LocaleNumbers

/**
 * Fortschritt von «Mehrere Paare auf einmal» ([com.cryptochecker.app.data.BulkAddRunner]): Balken und
 * «Paare werden hinzugefügt … 123 / 746». Im Explorer unter dem Knopf und schmal über der Merkliste.
 */
@Composable
fun BulkAddProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        LinearProgressIndicator(
            progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                R.string.bulk_add_progress,
                LocaleNumbers.integer(done, grouping = true),
                LocaleNumbers.integer(total, grouping = true),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
