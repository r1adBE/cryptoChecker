package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.refresh.MarketFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.exceptions.MarketParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Art des Fehlers am Ort des Fehlers ([toMarketFailure]) — und dieselbe Einordnung wie bisher über den Text: Für
 * jeden Fehler, den die HTTP-Schicht heute erzeugt, muss `classify(Art, Text)` gleich `classify(Text)` sein, und
 * «nicht mehr gehandelt» gleich entscheiden.
 */
class MarketFailureMappingTest {

    private val cases: List<Pair<Throwable, MarketFailure>> = listOf(
        HttpMarketError(429, "slow down", 30) to MarketFailure.RateLimited(30, 429),
        HttpMarketError(418, null) to MarketFailure.RateLimited(null, 418),
        HttpMarketError(404, "{}") to MarketFailure.HttpStatus(404),
        HttpMarketError(503, null, 12) to MarketFailure.HttpStatus(503),
        TimeoutError(InterruptedIOException("timeout")) to MarketFailure.Timeout,
        parseMarketError(SocketTimeoutException("Read timed out")) to MarketFailure.Timeout,
        parseMarketError(UnknownHostException("Unable to resolve host \"api.example.test\"")) to MarketFailure.Offline,
        parseMarketError(ConnectException("Failed to connect to /10.0.0.1:443")) to MarketFailure.Offline,
        parseMarketError(EOFException()) to MarketFailure.Offline,
        parseMarketError(SSLHandshakeException("Handshake failed")) to MarketFailure.Ssl,
        // Ein-/Ausgabefehler ohne klaren Typ: bleibt beim Text
        parseMarketError(IOException("Canceled")) to MarketFailure.Other,
        parseMarketError(IllegalStateException("boom")) to MarketFailure.Other,
        ParseError(IllegalArgumentException("bad")) to MarketFailure.Parse,
        UserFriendlyMarketError("Invalid symbol.") to MarketFailure.UnknownInstrument,
        UserFriendlyMarketError("EQuery:Unknown asset pair") to MarketFailure.UnknownInstrument,
        UserFriendlyMarketError("EGeneral:Too many requests") to MarketFailure.Exchange("EGeneral:Too many requests"),
        UserFriendlyMarketError("Invalid API key") to MarketFailure.Exchange("Invalid API key"),
        UserFriendlyMarketError(UserFriendlyMarketError.NO_TICKER_DATA) to MarketFailure.Other,
        // So meldet die Einzelabfrage eine Antwort ohne gültigen Kurs (wie iOS JSONError → .parse)
        MarketParseException(UserFriendlyMarketError.NO_TICKER_DATA) to MarketFailure.Parse,
        UserFriendlyMarketError(UserFriendlyMarketError.EMPTY_RESPONSE) to MarketFailure.Other,
        UserFriendlyMarketError(UserFriendlyMarketError.UNKNOWN_EMPTY) to MarketFailure.Other,
        ResponseTooLargeError() to MarketFailure.Other,
    )

    @Test
    fun boundaryProducesTheExpectedKind() {
        for ((error, expected) in cases) assertEquals(error.toString(), expected, error.toMarketFailure())
    }

    @Test
    fun typedClassificationMatchesTheLegacyText() {
        for ((error, _) in cases) {
            val text = error.message ?: error.javaClass.simpleName
            val failure = error.toMarketFailure()
            assertEquals(text, RefreshReportLogic.classify(text), RefreshReportLogic.classify(failure, text))
            assertEquals(text, RefreshReportLogic.httpStatus(text), RefreshReportLogic.httpStatus(failure, text))
            // Ausnahme mit Absicht: Other (interner Fehler) bestätigt nie «nicht mehr gehandelt», auch wenn der Text
            // allein es täte (z. B. «Response data is empty»)
            val legacy = NotTraded.confirmedBySingle(false, text)
            assertEquals(text, legacy && failure != MarketFailure.Other, NotTraded.confirmedBySingle(false, text, failure))
        }
    }

    @Test
    fun answerWithoutPriceStillConfirmsNotTraded() {
        val noPrice = MarketParseException(UserFriendlyMarketError.NO_TICKER_DATA)
        assertTrue(NotTraded.confirmedBySingle(false, noPrice.message, noPrice.toMarketFailure()))
        // Interner Fehler ohne Text: kein «nicht mehr gehandelt» (früher 6 h stumm)
        assertFalse(NotTraded.confirmedBySingle(false, null, MarketFailure.Other))
        assertFalse(NotTraded.confirmedBySingle(false, "", parseMarketError(IllegalStateException())))
        assertFalse(NotTraded.confirmedBySingle(false, " ", MarketFailure.Parse))
        // Ohne Art (gespeicherte Ergebnisse) wie bisher
        assertTrue(NotTraded.confirmedBySingle(false, null))
    }
}
