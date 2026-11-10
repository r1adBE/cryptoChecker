package com.cryptochecker.wear

import android.content.Context
import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchPortfolio
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Texte des Portfolio-Werts auf der Uhr (Zeile der Uhr-App und [PortfolioComplicationService]): Wert in der Währung des
 * Handys, Veränderung heute; verborgen («Beträge verbergen» bzw. Portfolio-Sperre auf dem Handy) «•••». Wie iOS
 * `WatchText.portfolio…`.
 */
object WatchPortfolioText {
    const val HIDDEN = "•••"

    /** «CHF 12’345.67»; ohne Wert «—». */
    fun total(p: WatchPortfolio, locale: Locale = Locale.getDefault()): String {
        if (p.hidden) return HIDDEN
        val total = p.total ?: return "—"
        return runCatching {
            NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance(p.currency) }.format(total)
        }.getOrElse { "${p.currency} ${WatchFormat.price(total, locale)}" }
    }

    /** Kurz für die kleine Komplikation: «12.3K»; verborgen «•••». */
    fun compact(p: WatchPortfolio, locale: Locale = Locale.getDefault()): String {
        if (p.hidden) return HIDDEN
        val total = p.total ?: return "—"
        return CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).format(total)
    }

    /** Lange Komplikation ohne Wert (Schalter aus): «Portfolio · am Handy einschalten». Wie iOS `WatchPortfolioOff.line`. */
    fun offLine(title: String, hint: String): String = "$title · $hint"

    /** Dasselbe für den Screenreader: «Portfolio, am Handy einschalten». */
    fun offSpoken(title: String, hint: String): String = "$title, $hint"

    /** «+1.20 % heute»; verborgen «•••». */
    fun today(context: Context, p: WatchPortfolio): String =
        if (p.hidden) HIDDEN else context.getString(R.string.watch_portfolio_today, WatchFormat.change(p.changePercent))

    /** Für den Screenreader: «Portfolio, CHF 12’345.67, +1.20 % heute» bzw. «Portfolio, Beträge verborgen». */
    fun spoken(context: Context, p: WatchPortfolio): String {
        val title = context.getString(R.string.watch_portfolio)
        return if (p.hidden) {
            "$title, ${context.getString(R.string.a11y_watch_portfolio_hidden)}"
        } else {
            "$title, ${total(p)}, ${today(context, p)}"
        }
    }
}

/** Zeile «Portfolio» der Uhr-App (ohne Aktion, wie die Kurszeilen); veraltet grau mit Alter. */
@Composable
internal fun PortfolioRow(p: WatchPortfolio, now: Long) {
    val context = LocalContext.current
    val outdated = WatchFreshness.isOutdated(p.time, now)
    val spoken = WatchPortfolioText.spoken(context, p) + if (outdated) ", ${WatchAge.spoken(context, p.time, now)}" else ""
    val detail = WatchPortfolioText.today(context, p) + if (outdated) "  ·  ${WatchAge.short(p.time, now)}" else ""
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChipDefaults.Height)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colors.surface)
            .padding(ChipDefaults.ContentPadding)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(stringResource(R.string.watch_portfolio), maxLines = 1, style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant)
        // Zu eng: Schrift schrumpft bis 60 % statt Ziffern abzuschneiden (wie iOS minimumScaleFactor)
        FitText(WatchPortfolioText.total(p), style = MaterialTheme.typography.button,
            color = if (outdated) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.onSurface)
        Text(detail, maxLines = 1, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurface)
    }
}
