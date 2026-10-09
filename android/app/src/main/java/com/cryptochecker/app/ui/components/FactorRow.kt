package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.WhyMark
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers

/**
 * Eine Zeile der Faktor-Checkliste (Crypto Pulse «Warum?» und «Warum bewegt
 * sich das?»): Markierung ✓ / – / !, kurzer Titel, Wert rechtsbündig, optional
 * eine Erklärung darunter. Screenreader: ein Element «Volumen, stützt die
 * Bewegung, 1,34-mal so viel wie üblich».
 */
@Composable
internal fun FactorRow(
    mark: WhyMark,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    spokenValue: String = value,
    detail: String? = null,
) {
    val markWord = stringResource(
        when (mark) {
            WhyMark.SUPPORTS -> R.string.why_dot_supports
            WhyMark.CAUTION -> R.string.why_dot_caution
            WhyMark.NEUTRAL -> R.string.why_dot_neutral
        }
    )
    val spoken = listOf(title, markWord, spokenValue).filter { it.isNotBlank() }.joinToString(", ") +
        (detail?.let { ". $it" } ?: "")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            FactorMarkGlyph(mark)
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (value.isNotEmpty()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = MARK_WIDTH, top = 2.dp)
            )
        }
    }
}

/** ✓ in der «ok»-Farbe (Steigend-Farbe des Schemas, nie getauscht), ! in Bernstein, – grau. */
@Composable
private fun FactorMarkGlyph(mark: WhyMark) {
    val (glyph, color) = when (mark) {
        WhyMark.SUPPORTS -> "✓" to PriceColors.ok
        WhyMark.CAUTION -> "!" to AppColors.warningText
        WhyMark.NEUTRAL -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        glyph,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.width(MARK_WIDTH)
    )
}

private val MARK_WIDTH = 20.dp
