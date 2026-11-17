// Texte des Einzel-Widgets: Wert mit Zeitraum, Paar — Teil von SingleWidgetRenderer.
package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.domain.BoundedCache
import com.cryptochecker.app.domain.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.TimedPrice
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.util.BidiText
import com.cryptochecker.app.domain.util.DefaultDispatcher
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.RetiredWatch
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.domain.widget.LatestPerWidget
import com.cryptochecker.app.domain.widget.WidgetCandle
import com.cryptochecker.app.domain.widget.WidgetChartGeometry
import com.cryptochecker.app.domain.widget.WidgetFreshness
import com.cryptochecker.app.domain.widget.WidgetStatusRule
import com.cryptochecker.app.domain.widget.WidgetTextFit
import com.cryptochecker.app.domain.widget.WidgetType
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.logoRes
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.AppIntents
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * «↘ −0.30% 24h»: Wert in der Farbe der Zeile, [period] dahinter verkleinert ([PERIOD_SCALE]) und
 * grau [grey] — der Zeitraum ist nie rot/grün. Ohne Zeitraum nur der Wert.
 */
internal fun SingleWidgetRenderer.periodSuffixed(value: String, period: String, grey: Int): CharSequence {
    if (period.isEmpty()) return value
    val text = "$value $period"
    return android.text.SpannableString(text).apply {
        setSpan(
            android.text.style.RelativeSizeSpan(SingleWidgetRenderer.PERIOD_SCALE),
            value.length,
            text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        setSpan(android.text.style.ForegroundColorSpan(grey), value.length, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Paar eines Eintrags als Text für das Einzel-Widget ([WidgetPrefs.chosenWatch]). */
fun widgetPairText(watch: com.cryptochecker.app.domain.model.WatchEntity): String =
    com.cryptochecker.app.domain.watch.WatchIdentity.text(
        com.cryptochecker.app.domain.watch.WatchIdentity.key(
            watch.exchangeKey,
            watch.baseAsset,
            watch.quoteAsset,
            watch.contractType.name,
            watch.pairId,
        ),
    )
