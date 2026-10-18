package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Nicht mehr gehandelt» nur bei klarer Aussage der Börse: ohne Kurs, unbekanntes Instrument, HTTP 400/404.
 * Weitere Fälle in `not_traded.json` (geteilt mit iOS).
 */
class NotTradedConfirmTest {

    @Test
    fun unknownExceptionsNoLongerConfirm() {
        assertFalse(NotTraded.confirmedBySingle(false, "java.lang.IllegalStateException: Expected BEGIN_OBJECT"))
        assertFalse(NotTraded.confirmedBySingle(false, "Unknown error (empty)"))
        assertFalse(NotTraded.confirmedBySingle(false, "Something went wrong"))
    }

    @Test
    fun explicitUnknownInstrumentConfirms() {
        assertTrue(NotTraded.confirmedBySingle(false, "Invalid symbol."))
        assertTrue(NotTraded.confirmedBySingle(false, "Parameter symbol does not exist"))
        assertTrue(NotTraded.confirmedBySingle(false, "INVALID_CURRENCY_PAIR"))
        assertTrue(NotTraded.isUnknownInstrument("EQuery:Unknown asset pair"))
        assertFalse(NotTraded.isUnknownInstrument(null))
    }

    @Test
    fun onlyHttp400And404Confirm() {
        assertTrue(NotTraded.confirmedBySingle(false, "HttpCode: 400"))
        assertTrue(NotTraded.confirmedBySingle(false, "HTTP 404"))
        assertFalse(NotTraded.confirmedBySingle(false, "HttpCode: 410"))
        assertFalse(NotTraded.confirmedBySingle(false, "HttpCode: 422"))
        assertFalse(NotTraded.confirmedBySingle(false, "HttpCode: 401"))
    }

    @Test
    fun answerWithoutPriceConfirms() {
        assertTrue(NotTraded.confirmedBySingle(false, null))
        assertTrue(NotTraded.confirmedBySingle(false, "Parsed ticker has no data"))
        assertFalse(NotTraded.confirmedBySingle(true, null))
    }
}
