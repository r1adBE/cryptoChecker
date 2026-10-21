package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Formularzustand des Erfassen-Blatts ([TxFormState.saver]): das Dezimalzeichen, mit dem die Texte getippt wurden,
 * übersteht einen Sprachwechsel — «2,125» bleibt 2,125 statt 2125.
 */
class PortfolioTxFormStateSaverTest {

    private val scope = SaverScope { true }
    private val initial = TxDraft(coin = "BTC", time = 1_700_000_000_000L)

    private fun save(state: TxFormState, separator: Char): List<*> =
        with(TxFormState.saver(initial, separator)) { scope.save(state) } as List<*>

    @Test
    fun `Dezimalzeichen der Eingabe uebersteht den Sprachwechsel`() {
        val before = TxFormState(initial, ',')
        before.amountText = "2,125"
        val saved = save(before, ',')
        // Neue Sprache mit Punkt als Dezimalzeichen: das Blatt liest die Texte weiter mit dem Komma
        val after = TxFormState.saver(initial, '.').restore(saved)
        assertNotNull(after)
        assertEquals(',', after!!.decimalSeparator)
        assertEquals("2,125", after.amountText)
        assertEquals(0, BigDecimal("2.125").compareTo(after.derive("USD").amount))
    }

    @Test
    fun `aeltere Liste ohne Dezimalzeichen nimmt das aktuelle`() {
        val state = TxFormState(initial, ',')
        state.amountText = "1.5"
        val old = save(state, ',').dropLast(1)
        val restored = TxFormState.saver(initial, '.').restore(old)
        assertNotNull(restored)
        assertEquals('.', restored!!.decimalSeparator)
        assertEquals(0, BigDecimal("1.5").compareTo(restored.derive("USD").amount))
    }
}
