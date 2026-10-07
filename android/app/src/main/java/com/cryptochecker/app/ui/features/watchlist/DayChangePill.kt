package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Veränderung über 24 Stunden (`WatchEntity.change24h`) als Pille wie [ChangePill],
 * daneben klein «24h». Ohne 24-h-Bezug eine graue Pille «—» ohne Pfeil — nie die
 * Veränderung seit der letzten Abfrage. Screenreader: «up 2.30% in 24 hours».
 */
@Composable
internal fun DayChangePill(change: Double?, modifier: Modifier = Modifier) {
    val value = change?.takeIf { it.isFinite() }
    val formatted = PriceFormat.changePercent(value)
    // Pfeil folgt dem Vorzeichen, nie dem Farbtausch; bei 0.00% und «—» keiner
    val text = when {
        value == null -> "—"
        formatted != null -> "${PriceFormat.changeArrow(value)} $formatted"
        else -> "0.00%"
    }
    val color = if (value == null || formatted == null) MaterialTheme.colorScheme.onSurfaceVariant
    else PriceColors.forChange(value)
    val spoken = A11yText.change24h(LocalContext.current, value)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(top = 3.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.amountNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Text(
            text = stringResource(R.string.widget_range_short_24h),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
