package com.cryptochecker.app.ui.features.portfolio

import java.math.BigDecimal
import java.time.LocalDate

/** Laden des Devisenkurses für die Eingabe in der Anzeigewährung. */
internal enum class EntryFxState { LOADING, READY, FAILED }

/**
 * Geladener Faktor USDT → Anzeigewährung ([com.cryptochecker.app.domain.portfolio.PortfolioTxForm.entryRate]) mit dem
 * tatsächlichen Kurstag; [usdtAssumed]: ohne plausiblen USDT/USD-Kurs mit USDT = 1 gerechnet — das Blatt sagt es dann.
 */
data class EntryFx(val rate: BigDecimal, val day: LocalDate, val usdtAssumed: Boolean = false)
