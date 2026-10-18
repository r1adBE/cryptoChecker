// Hilfen der Transaktions-Eingabe: Name und Erklärung je Art, Coin-Suche, Datumswahl und Zeitpunkt des
// gewählten Tags, Umrechnung der Eingabe USDT ↔ Anzeigewährung.
// Eigene Datei, weil es reine Regeln ohne eigenes Layout sind (Umrechnung wie `PortfolioTxPrefill.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.size
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.util.PriceFormat
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Kurze Erklärung unter der Wahl der Art (nur die neuen Arten). */
internal fun PortfolioTxType.explainRes(): Int? = when (this) {
    PortfolioTxType.BUY, PortfolioTxType.SELL -> null
    PortfolioTxType.TRANSFER -> R.string.portfolio_tx_explain_transfer
    PortfolioTxType.REWARD -> R.string.portfolio_tx_explain_reward
    PortfolioTxType.DEPOSIT -> R.string.portfolio_tx_explain_deposit
    PortfolioTxType.WITHDRAWAL -> R.string.portfolio_tx_explain_withdrawal
    PortfolioTxType.SWAP -> R.string.portfolio_tx_explain_swap
    PortfolioTxType.GIFT -> R.string.portfolio_tx_explain_gift
    PortfolioTxType.PAYMENT -> R.string.portfolio_tx_explain_payment
}

internal fun PortfolioTxType.labelRes(): Int = when (this) {
    PortfolioTxType.BUY -> R.string.portfolio_buy
    PortfolioTxType.SELL -> R.string.portfolio_sell
    PortfolioTxType.TRANSFER -> R.string.portfolio_tx_transfer
    PortfolioTxType.REWARD -> R.string.portfolio_tx_reward
    PortfolioTxType.DEPOSIT -> R.string.portfolio_tx_deposit
    PortfolioTxType.WITHDRAWAL -> R.string.portfolio_tx_withdrawal
    PortfolioTxType.SWAP -> R.string.portfolio_tx_swap
    PortfolioTxType.GIFT -> R.string.portfolio_tx_gift
    PortfolioTxType.PAYMENT -> R.string.portfolio_tx_payment
}

/** Treffer für die Suche: erst «beginnt mit», dann «enthält»; exakter Treffer allein blendet aus. */
internal fun matchCoins(coins: List<String>, query: String): List<String> {
    if (query.isEmpty() || coins.isEmpty()) return emptyList()
    val starts = coins.filter { it.startsWith(query) }.sortedWith(compareBy({ it.length }, { it }))
    if (starts.size == 1 && starts[0] == query) return emptyList()
    val contains = coins.filter { !it.startsWith(query) && it.contains(query) }.sortedWith(compareBy({ it.length }, { it }))
    return (starts + contains).take(12)
}

/** Datumswahl (bis heute bzw. bis [maxDate]); auch vom Stichtag-Export verwendet. */
@Composable
internal fun DateDialog(selected: Long, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit, maxDate: LocalDate? = null) {
    val today = maxDate?.takeIf { it.isBefore(LocalDate.now()) } ?: LocalDate.now()
    val todayUtc = utcMidnight(today)
    val state = rememberDatePickerState(
        // DatePickerState wirft, wenn das Startdatum ausserhalb von yearRange liegt
        // (z. B. Zeitstempel aus einer Sicherung) — daher in beide Richtungen begrenzen.
        initialSelectedDateMillis = utcMidnight(localDate(selected))
            .coerceIn(utcMidnight(LocalDate.of(MIN_YEAR, 1, 1)), todayUtc),
        yearRange = MIN_YEAR..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtc
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { utc ->
                        onSelect(Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    ) {
        DatePicker(state = state)
    }
}

private const val MIN_YEAR = 2009

internal fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** Der DatePicker rechnet in UTC-Mitternacht. */
private fun utcMidnight(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Zeitpunkt für ein gewähltes Datum: unverändertes Datum behält die Uhrzeit,
 * heute = jetzt, sonst 12:00 Ortszeit (Reihenfolge am selben Tag nach Erfassung).
 */
internal fun timeFor(date: LocalDate, original: Long): Long = when (date) {
    localDate(original) -> original
    LocalDate.now() -> System.currentTimeMillis()
    else -> date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/**
 * Feldtext in die andere Währung umrechnen ([convert]: USDT → Anzeigewährung); leer bzw. unlesbar bleibt, wie es ist.
 */
internal fun convertEntry(text: String, separator: Char, hint: Double?, convert: (BigDecimal) -> BigDecimal?): String {
    if (text.isBlank()) return text
    val value = PriceFormat.parseDecimalAmount(text, separator, hint) ?: return text
    return convert(value)?.let { PriceFormat.amountForInput(roundForInput(it), separator) } ?: text
}

/** Umgerechneter Betrag fürs Feld: 8 gültige Stellen, halbe auf gerade wie iOS (sonst stünden 34 Stellen aus der Teilung da). */
internal fun roundForInput(value: BigDecimal): BigDecimal {
    val r = value.round(java.math.MathContext(8, java.math.RoundingMode.HALF_EVEN)).stripTrailingZeros()
    return if (r.scale() < 0) r.setScale(0) else r
}
