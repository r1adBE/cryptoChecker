// Quellzeile und Takt eines Devisen-Referenzkurses (EZB, NBU, NBP): in «Paar hinzufügen» und auf der Paar-Seite gleich.
// Wie iOS `ReferenceSourceText.swift`.
package com.cryptochecker.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.marketdata.model.ReferenceSource

/**
 * «Quelle: EZB» / «Quelle: Nationalbank der Ukraine» / «Quelle: Polnische Nationalbank», mit «· umgerechnet», wenn der
 * gezeigte Wert von [base] ein Kreuzkurs oder eine berechnete Veränderung ([hasChange]) ist ([ReferenceSource.converted]).
 */
@Composable
fun referenceSourceText(source: ReferenceSource, base: String, hasChange: Boolean): String {
    val name = referenceSourceName(source)
    if (!source.converted(base, hasChange)) return name
    return name + " · " + stringResource(R.string.explorer_source_converted)
}

/** Nur «Quelle: …» ohne Hinweis (Liste der Börsen). */
@Composable
fun referenceSourceName(source: ReferenceSource): String = stringResource(
    when (source) {
        ReferenceSource.ECB -> R.string.explorer_source_ecb
        ReferenceSource.NBU -> R.string.explorer_source_nbu
        ReferenceSource.NBP -> R.string.explorer_source_nbp
    },
)

/** «Referenzkurs, täglich» bzw. «Referenzkurs, wöchentlich» nach dem Takt der Quelle. */
@Composable
fun referencePeriodText(source: ReferenceSource): String =
    stringResource(if (source.weekly) R.string.watch_price_reference_weekly else R.string.watch_price_reference)

/** Kurz «täglich» bzw. «wöchentlich» (Zeile der Merkliste, Börsenliste). */
@Composable
fun referencePeriodShort(source: ReferenceSource): String =
    stringResource(if (source.weekly) R.string.watch_price_weekly else R.string.watch_price_daily)

/** Quellzeile klein unter einem Referenzkurs ([referenceSourceText]). */
@Composable
fun ReferenceSourceLine(source: ReferenceSource, base: String, hasChange: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = referenceSourceText(source, base, hasChange),
        style = MaterialTheme.typography.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
