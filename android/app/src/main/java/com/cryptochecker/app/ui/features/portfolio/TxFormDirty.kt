// Geänderte Eingaben im Buchungsblatt (Rückfrage beim Schliessen) — Teil von TxFormState.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.cryptochecker.app.domain.model.NOTE_MAX
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxForm
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.fiatToUsdt
import com.cryptochecker.app.domain.portfolio.fields
import com.cryptochecker.app.domain.settings.inputDecimalSeparator
import com.cryptochecker.app.domain.util.TextLimit
import com.cryptochecker.app.util.PriceFormat
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Vom Nutzer geändert? Vorbelegte Preise (heutiger Kurs, Tagesschluss) zählen nicht als Änderung. */
internal fun TxFormState.isDirty(): Boolean {
    val sep = decimalSeparator
    return type != initial.type || coinQuery != initial.coin ||
        amountText != PriceFormat.amountForInput(initial.amount, sep) ||
        (!priceAuto && priceHistoricDay == null && priceText != PriceFormat.amountForInput(initial.priceUsdt, sep)) ||
        time != initial.time || acquiredTime != initial.acquiredTime ||
        note != TextLimit.takeGraphemes(initial.note.orEmpty(), NOTE_MAX) ||
        feeText != PriceFormat.amountForInput(initial.fee, sep) || feeUnit != initial.feeUnit ||
        exchange != initial.exchange.orEmpty() || transferFrom != initial.transferFrom.orEmpty() ||
        transferTo != initial.transferTo.orEmpty() || giftToSpouse != initialSpouse || interest != initialInterest ||
        feeCoinText != initial.feeCoin.orEmpty() ||
        feeCoinAmountText != PriceFormat.amountForInput(initial.feeCoinAmount, sep) ||
        (!feeCoinPriceAuto && feeCoinPriceText != PriceFormat.amountForInput(initial.feeCoinPriceUsdt, sep)) ||
        toCoinQuery != initial.toCoin.orEmpty() ||
        toAmountText != PriceFormat.amountForInput(initial.toAmount, sep) ||
        swapValueText != PriceFormat.amountForInput(initial.swapValueUsdt, sep)
}
