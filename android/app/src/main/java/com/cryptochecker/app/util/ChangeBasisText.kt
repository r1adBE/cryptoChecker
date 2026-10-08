package com.cryptochecker.app.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath

/**
 * Texte zur «Basis der %-Änderung» für App und Widgets: kurzer Zeitraum neben Pille, Puls und
 * Widgets («24h», «heute», «heute UTC+8»), Auswahl («UTC+8, 00:00») und Screenreader-Sätze
 * («up 2.35% today»). Wie `A11y` (iOS).
 */
object ChangeBasisText {

    /** Kurzer Zeitraum: «24h», «heute» (Ortszeit) oder «heute UTC+8». */
    fun shortLabel(context: Context, basis: ChangeBasis): String = when (basis.kind) {
        ChangeBasis.Kind.ROLLING_24H -> context.getString(R.string.widget_range_short_24h)
        ChangeBasis.Kind.LOCAL_DAY -> context.getString(R.string.change_short_today)
        ChangeBasis.Kind.UTC_DAY -> context.getString(R.string.change_short_today_zone, zone(basis))
    }

    /** Zeitraum ausgeschrieben (Screenreader, Chart): «24 hours», «Since 00:00 UTC+8», «Since 00:00 local time». */
    fun longLabel(context: Context, basis: ChangeBasis): String = when (basis.kind) {
        ChangeBasis.Kind.ROLLING_24H -> context.getString(R.string.widget_range_24h)
        ChangeBasis.Kind.LOCAL_DAY -> context.getString(R.string.change_basis_local)
        ChangeBasis.Kind.UTC_DAY -> context.getString(R.string.change_basis_utc_zone, zone(basis))
    }

    /**
     * Eintrag der Auswahl (wie Binance): «Letzte 24 Std.», «UTC+2, 00:00 (Zeitzone des Geräts)»
     * mit der Zone des Geräts zum Zeitpunkt [now], «UTC+8, 00:00».
     */
    fun choiceLabel(context: Context, basis: ChangeBasis, now: Long = System.currentTimeMillis()): String =
        when (basis.kind) {
            ChangeBasis.Kind.ROLLING_24H -> context.getString(R.string.change_basis_rolling)
            ChangeBasis.Kind.LOCAL_DAY ->
                context.getString(R.string.change_basis_device, ChangeBasisMath.deviceZoneLabel(now))
            ChangeBasis.Kind.UTC_DAY -> zoneChoice(basis)
        }

    /** «UTC+8, 00:00» — Zahlen und Zone, in allen Sprachen gleich. */
    fun zoneChoice(basis: ChangeBasis): String = "${zone(basis)}, 00:00"

    /**
     * Veränderung für den Screenreader: «up 2.35% in 24 hours» / «… today» / «… since midnight UTC+8»;
     * ohne Wert «… not available» (Pille «—»).
     */
    fun spoken(context: Context, basis: ChangeBasis, percent: Double?): String {
        val valid = percent?.takeIf { it.isFinite() }
        return when (basis.kind) {
            ChangeBasis.Kind.ROLLING_24H -> A11yText.change24h(context, valid)
            ChangeBasis.Kind.UTC_DAY ->
                if (valid == null) context.getString(R.string.a11y_change_today_zone_none, zone(basis))
                else context.getString(R.string.a11y_change_today_zone, A11yText.change(context, valid), zone(basis))
            ChangeBasis.Kind.LOCAL_DAY ->
                if (valid == null) context.getString(R.string.a11y_change_today_none)
                else context.getString(R.string.a11y_change_today, A11yText.change(context, valid))
        }
    }

    /** Wie [spoken], aber mit fertigem Satzteil (z. B. ein Betrag «up 12.00 CHF»). */
    fun spokenPhrase(context: Context, basis: ChangeBasis, phrase: String): String = when (basis.kind) {
        ChangeBasis.Kind.ROLLING_24H -> context.getString(R.string.a11y_change_24h, phrase)
        ChangeBasis.Kind.UTC_DAY -> context.getString(R.string.a11y_change_today_zone, phrase, zone(basis))
        ChangeBasis.Kind.LOCAL_DAY -> context.getString(R.string.a11y_change_today, phrase)
    }

    /** Wie [shortLabel] in Compose (neu bei Sprachwechsel, wie `stringResource`). */
    @Composable
    @ReadOnlyComposable
    fun shortLabel(basis: ChangeBasis): String {
        LocalConfiguration.current
        return shortLabel(LocalContext.current, basis)
    }

    /** Wie [longLabel] in Compose. */
    @Composable
    @ReadOnlyComposable
    fun longLabel(basis: ChangeBasis): String {
        LocalConfiguration.current
        return longLabel(LocalContext.current, basis)
    }

    /**
     * Wert in der Zeile der Einstellungen: «Letzte 24 Std.», «Seit 00:00 Ortszeit»,
     * «Seit 00:00 UTC+8».
     */
    @Composable
    @ReadOnlyComposable
    fun summary(basis: ChangeBasis): String {
        LocalConfiguration.current
        val context = LocalContext.current
        return if (basis.isDay) longLabel(context, basis) else context.getString(R.string.change_basis_rolling)
    }

    private fun zone(basis: ChangeBasis): String = ChangeBasisMath.zoneLabel(basis) ?: "UTC"
}
