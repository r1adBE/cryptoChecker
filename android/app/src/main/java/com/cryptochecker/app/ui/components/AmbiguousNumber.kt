package com.cryptochecker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.ThresholdParser
import com.cryptochecker.app.domain.settings.inputDecimalSeparator

/**
 * Fehlertext eines Zahlenfelds für eine mehrdeutige Eingabe («1.234» in DE: «Mehrdeutig – 1234 oder 1,234?»), sonst
 * null. Der Parser liest solche Eingaben nicht ([ThresholdParser.ambiguity]); dieser Text sagt, warum — für jedes
 * Zahlenfeld der App (wie iOS `ambiguousNumberText`).
 */
@Composable
@ReadOnlyComposable
fun ambiguousNumberText(text: String, decimalSeparator: Char = inputDecimalSeparator(), priceHint: Double? = null): String? =
    ThresholdParser.ambiguity(text, decimalSeparator, priceHint)?.let { stringResource(R.string.number_ambiguous, it.grouped, it.decimal) }
