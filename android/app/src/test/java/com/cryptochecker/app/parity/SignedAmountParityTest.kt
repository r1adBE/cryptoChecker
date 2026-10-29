package com.cryptochecker.app.parity

import com.cryptochecker.app.ui.features.portfolio.PortfolioFormat
import com.cryptochecker.app.ui.features.portfolio.signedAxisValue
import com.cryptochecker.app.ui.features.portfolio.signedValue
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Portfolio-Beträge mit Vorzeichen (`testdata/parity/signed_amount.json`): unter einem halben Cent ohne Vorzeichen
 * (nie «-0.00»), echtes Minus, Steuer-Summen nur bei Verlust mit Vorzeichen, Vorzeichen und Zahl als
 * links-nach-rechts-Insel. Gegenstück: `Tests/SignedAmountParityTests.swift`.
 */
class SignedAmountParityTest {

    private fun actual(kind: String, value: Double, unit: String): String = when (kind) {
        "money" -> PortfolioFormat.signedMoney(value, unit)
        "value" -> signedValue(value, unit)
        else -> signedAxisValue(value)
    }

    private fun expected(c: Map<String, Any?>): String {
        val sign = c["sign"] as String
        val magnitude = c["magnitude"].num()!!
        val unit = c["unit"] as String
        return when (c["kind"]) {
            "money" -> BidiText.ltr(sign + PriceFormat.valueFormat().format(magnitude)) + " " + unit
            "value" -> BidiText.ltr(sign + PriceFormat.valueWithCurrency(magnitude, unit))
            else -> if (c["zero"] == true) "0" else BidiText.ltr(sign + PriceFormat.price(magnitude))
        }
    }

    private fun check(locale: Locale) {
        val before = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            for (raw in ParityJson.load("signed_amount.json")["cases"].list()) {
                val c = raw.obj()
                val value = c["value"].num()!!
                val text = actual(c["kind"] as String, value, c["unit"] as String)
                assertEquals("${c["kind"]} $value", expected(c), text)
                assertTrue("${c["kind"]} $value", !text.contains('-'))
                if (BidiText.isRtl(locale) && c["zero"] != true) assertTrue(text.startsWith(BidiText.LRI))
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun faelleWieGemeinsameTestfaelle() = check(Locale.US)

    @Test
    fun rechtsNachLinksMitInsel() = check(Locale.forLanguageTag("he-IL"))
}
