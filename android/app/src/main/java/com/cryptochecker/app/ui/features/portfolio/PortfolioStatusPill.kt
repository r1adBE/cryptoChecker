// Status der Portfolio-Kurse über der Liste (aktuell · veraltet · offline) und die Zeitangabe «Stand …».
// Eigene Datei, weil Portfolio-Seite und Gesamtkarte sie gemeinsam nutzen (wie `PortfolioStatusPill.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.cryptochecker.app.domain.format.A11ySpeech
import com.cryptochecker.app.domain.portfolio.PortfolioPriceState
import com.cryptochecker.app.ui.components.DataStatusPill
import com.cryptochecker.app.ui.components.DataStatusTone
import com.cryptochecker.app.ui.features.watchlist.ago
import com.cryptochecker.app.ui.features.watchlist.spokenAgo
import com.cryptochecker.resources.R

/** Uhrzeit des Stands, bei älteren Kursen (nicht von heute) mit Datum — «Stand 14:05» bzw. «Stand 3. Okt., 14:05». */
@Composable
internal fun portfolioStampTime(millis: Long): String {
    val context = LocalContext.current
    val flags = if (DateUtils.isToday(millis)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateTime(context, millis, flags)
}

/**
 * Status der Portfolio-Kurse wie in der Merkliste ([DataStatusPill]): grün «Alles aktuell · vor 2 Min.»,
 * rot «Kurse veraltet · Stand 09:12», neutral «Offline · Stand 14:05» (bzw. «Offline» ohne Stand).
 * Bei [PortfolioPriceState.NONE] (noch kein Kurs, beim Öffnen) eine unsichtbare Pille gleicher Höhe: Die Liste darunter
 * springt nicht, wenn der Stand kommt, und öffnet nicht unter der Pille gescrollt.
 */
@Composable
internal fun PortfolioStatusPill(
    state: PortfolioPriceState,
    updatedAt: Long,
    now: Long,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (state == PortfolioPriceState.NONE) {
        DataStatusPill(
            text = " ",
            tone = DataStatusTone.NEUTRAL,
            modifier = modifier.alpha(0f).clearAndSetSemantics { },
        )
        return
    }
    val text = when (state) {
        PortfolioPriceState.NONE -> ""
        PortfolioPriceState.FRESH -> stringResource(R.string.watchlist_all_fresh, ago(updatedAt, now))
        PortfolioPriceState.STALE -> stringResource(R.string.portfolio_status_stale, portfolioStampTime(updatedAt))
        PortfolioPriceState.OFFLINE -> if (updatedAt > 0) {
            stringResource(R.string.offline_status_since, portfolioStampTime(updatedAt))
        } else {
            stringResource(R.string.offline_status)
        }
    }
    val tone = when (state) {
        PortfolioPriceState.FRESH -> DataStatusTone.FRESH
        PortfolioPriceState.STALE -> DataStatusTone.WARN
        else -> DataStatusTone.NEUTRAL
    }
    // Screenreader: Zeit ausgeschrieben («vor 2 Minuten») wie die Merkliste
    val spoken = if (state == PortfolioPriceState.FRESH) {
        A11ySpeech.spokenDots(text.replace(ago(updatedAt, now), spokenAgo(updatedAt, now)))
    } else {
        null
    }
    DataStatusPill(text = text, tone = tone, reduceMotion = reduceMotion, spoken = spoken, modifier = modifier)
}
