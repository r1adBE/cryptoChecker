package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.RetiredWatch
import com.cryptochecker.marketdata.config.ExchangeRetirement
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Sprache der App (beobachtet, für Datumsangaben). */
@Composable
@ReadOnlyComposable
internal fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** Tag des Endes der Börse [exchange] in der Sprache der App («1. Februar 2027»), als UTC-Datum (Ende um 00:00 UTC). */
internal fun retirementDate(exchange: String, locale: Locale): String {
    val end = ExchangeRetirement.endsAt(exchange) ?: return ""
    val format = DateFormat.getDateInstance(DateFormat.LONG, locale)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(end))
}

/**
 * Grund zur Stilllegung der Börse des Paars mit Datum ([ExchangeRetirement]): nach dem Ende (eine Regel,
 * [RetiredWatch]) «Bitpanda liefert seit 1. Februar 2027 keine Kurse mehr» — auf der Alarmseite ([alarms]) mit «… – Alarme
 * dieses Paars prüfen nichts mehr» —, während der Ankündigung «Bitpanda liefert ab 1. Februar 2027 keine Kurse mehr»
 * (sonst ändert sich davor nichts). null = weder stillgelegt noch angekündigt. Wie iOS `RetirementText.reason`.
 */
@Composable
internal fun retirementReason(watch: WatchEntity, alarms: Boolean): String? {
    // Entschieden über den Schlüssel der Börse, gezeigt der Name aus dem Katalog
    val retired = RetiredWatch.exchangeOf(watch)
    val exchange = retired
        ?: RetiredWatch.displayName(watch).takeIf { ExchangeRetirement.isAnnounced(watch.exchangeKey) } ?: return null
    val date = retirementDate(watch.exchangeKey, appLocale())
    return when {
        retired != null && alarms -> stringResource(R.string.exchange_retired_alarms, exchange, date)
        retired != null -> stringResource(R.string.exchange_retired_since, exchange, date)
        else -> stringResource(R.string.exchange_retiring_reason, exchange, date)
    }
}
