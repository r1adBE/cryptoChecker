package com.cryptochecker.marketdata.util

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.cryptochecker.marketdata.model.CurrencySubunit
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

object FormatUtilsBase {

    /** Formate sofort neu anlegen (z. B. nach einem Sprachwechsel); sonst geschieht das beim nächsten Aufruf. */
    fun updateLocale() {
        decimalFormatStore = DecimalFormatStore(Locale.getDefault(Locale.Category.FORMAT))
    }

    // ========================================
    // Double formatting (using default locale)
    // ========================================
    private class DecimalFormatStore(val locale: Locale) {
        private val symbols = DecimalFormatSymbols.getInstance(locale)
        val formatNoDecimal = DecimalFormat("#,###", symbols)
        val formatTwoDecimal = DecimalFormat("#,###.00", symbols)
        val formatFourSignificantAtMost = DecimalFormat("@###", symbols)
        val formatEightSignificantAtMost = DecimalFormat("@#######", symbols)
    }

    @Volatile
    private var decimalFormatStore = DecimalFormatStore(Locale.getDefault(Locale.Category.FORMAT))

    /**
     * Formate zur aktuellen Sprache: Nach einem Sprachwechsel in der App (`Locale.setDefault`)
     * werden sie beim nächsten Aufruf neu angelegt — ohne Neustart, auch ohne [updateLocale].
     */
    private val store: DecimalFormatStore
        get() {
            val locale = Locale.getDefault(Locale.Category.FORMAT)
            val current = decimalFormatStore
            if (current.locale == locale) return current
            return DecimalFormatStore(locale).also { decimalFormatStore = it }
        }

    // ====================
    // Format methods
    // ====================
    fun formatDouble(value: Double/*, isPrice: Boolean*/): String {
        val decimalFormat: DecimalFormat = when {
            value < 10 -> store.formatFourSignificantAtMost
            value < 10000 -> store.formatTwoDecimal
            else -> store.formatNoDecimal
        }

        return formatDouble(decimalFormat, value)
    }

    @Suppress("unused")
    fun formatDoubleWithEightMax(value: Double): String {
        return formatDouble(store.formatEightSignificantAtMost, value)
    }

    @Suppress("unused")
    fun formatDoubleWithFourMax(value: Double): String {
        return formatDouble(store.formatFourSignificantAtMost, value)
    }

    private fun formatDouble(decimalFormat: DecimalFormat, value: Double): String {
        synchronized(decimalFormat) {
            try {
                return decimalFormat.format(value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return value.toString()
        }
    }

    // ====================
    // Price formatting
    // ====================
    fun formatPriceWithCurrency(price: Double, subunitDst: CurrencySubunit): String {
        return formatPriceWithCurrency(price, subunitDst, true)
    }

    fun formatPriceWithCurrency(price: Double, subunitDst: CurrencySubunit, showCurrencyDst: Boolean): String {
        var priceString = formatPriceValueForSubunit(price, subunitDst, forceInteger = false, skipNoSignificantDecimal = false)
        if (showCurrencyDst) {
            priceString = formatPriceWithCurrency(priceString, subunitDst.name)
        }
        return priceString
    }

    fun formatPriceWithCurrency(value: Double, currency: String): String {
        return formatPriceWithCurrency(formatDouble(value), currency)
    }

    private fun formatPriceWithCurrency(priceString: String, currency: String): String {
        return priceString + " " + CurrencyUtils.getCurrencySymbol(currency)
    }

    fun formatPriceValueForSubunit(price: Double, subunitDst: CurrencySubunit, forceInteger: Boolean, skipNoSignificantDecimal: Boolean): String {
        val calcPrice = price * subunitDst.subunitToUnit.toDouble()
        return if (!subunitDst.allowDecimal || forceInteger) return (calcPrice + 0.5f).toLong().toString()
            else if (skipNoSignificantDecimal) formatDoubleWithEightMax(calcPrice) else formatDouble(calcPrice)
    }

    // ====================
    // Date && Time formatting
    // ====================
    @JvmStatic
    fun formatSameDayTimeOrDate(context: Context?, time: Long): String {
        return if (DateUtils.isToday(time)) {
            DateFormat.getTimeFormat(context).format(Date(time))
        } else {
            DateFormat.getDateFormat(context).format(Date(time))
        }
    }

    fun formatDateTime(context: Context?, time: Long): String {
        val date = Date(time)
        return DateFormat.getTimeFormat(context).format(date) + ", " + DateFormat.getDateFormat(context).format(date)
    }
}