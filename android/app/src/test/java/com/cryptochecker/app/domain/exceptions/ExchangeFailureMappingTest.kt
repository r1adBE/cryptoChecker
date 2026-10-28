package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
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
 * Art des Fehlers am Ort des Fehlers ([toExchangeFailure]) — und dieselbe Einordnung wie bisher über den Text: Für
 * jeden Fehler, den die HTTP-Schicht heute erzeugt, muss `classify(Art, Text)` gleich `classify(Text)` sein, und
 * «nicht mehr gehandelt» gleich entscheiden.
 */
class ExchangeFailureMappingTest {

    private val cases: List<Pair<Throwable, ExchangeFailure>> = listOf(
        HttpExchangeError(429, "slow down", 30) to ExchangeFailure.RateLimited(30, 429),
        HttpExchangeError(418, null) to ExchangeFailure.RateLimited(null, 418),
        HttpExchangeError(404, "{}") to ExchangeFailure.HttpStatus(404),
        HttpExchangeError(503, null, 12) to ExchangeFailure.HttpStatus(503),
        TimeoutError(InterruptedIOException("timeout")) to ExchangeFailure.Timeout,
        parseExchangeError(SocketTimeoutException("Read timed out")) to ExchangeFailure.Timeout,
        parseExchangeError(UnknownHostException("Unable to resolve host \"api.example.test\"")) to ExchangeFailure.Offline,
        parseExchangeError(ConnectException("Failed to connect to /10.0.0.1:443")) to ExchangeFailure.Offline,
        parseExchangeError(EOFException()) to ExchangeFailure.Offline,
        parseExchangeError(SSLHandshakeException("Handshake failed")) to ExchangeFailure.Ssl,
        // Ein-/Ausgabefehler ohne klaren Typ: bleibt beim Text
        parseExchangeError(IOException("Canceled")) to ExchangeFailure.Other,
        parseExchangeError(IllegalStateException("boom")) to ExchangeFailure.Other,
        ParseError(IllegalArgumentException("bad")) to ExchangeFailure.Parse,
        UserFriendlyExchangeError("Invalid symbol.") to ExchangeFailure.UnknownInstrument,
        UserFriendlyExchangeError("EQuery:Unknown asset pair") to ExchangeFailure.UnknownInstrument,
        UserFriendlyExchangeError("EGeneral:Too many requests") to ExchangeFailure.Message("EGeneral:Too many requests"),
        UserFriendlyExchangeError("Invalid API key") to ExchangeFailure.Message("Invalid API key"),
        UserFriendlyExchangeError(UserFriendlyExchangeError.NO_TICKER_DATA) to ExchangeFailure.Other,
        // So meldet die Einzelabfrage eine Antwort ohne gültigen Kurs (wie iOS JSONError → .parse)
        ExchangeParseException(UserFriendlyExchangeError.NO_TICKER_DATA) to ExchangeFailure.Parse,
        UserFriendlyExchangeError(UserFriendlyExchangeError.EMPTY_RESPONSE) to ExchangeFailure.Other,
        UserFriendlyExchangeError(UserFriendlyExchangeError.UNKNOWN_EMPTY) to ExchangeFailure.Other,
        ResponseTooLargeError() to ExchangeFailure.Other,
    )

    @Test
    fun boundaryProducesTheExpectedKind() {
        for ((error, expected) in cases) assertEquals(error.toString(), expected, error.toExchangeFailure())
    }

    @Test
    fun typedClassificationMatchesTheLegacyText() {
        for ((error, _) in cases) {
            val text = error.message ?: error.javaClass.simpleName
            val failure = error.toExchangeFailure()
            assertEquals(text, RefreshReportLogic.classify(text), RefreshReportLogic.classify(failure, text))
            assertEquals(text, RefreshReportLogic.httpStatus(text), RefreshReportLogic.httpStatus(failure, text))
            // Ausnahme mit Absicht: Other (interner Fehler) bestätigt nie «nicht mehr gehandelt», auch wenn der Text
            // allein es täte (z. B. «Response data is empty»)
            val legacy = NotTraded.confirmedBySingle(false, text)
            assertEquals(text, legacy && failure != ExchangeFailure.Other, NotTraded.confirmedBySingle(false, text, failure))
        }
    }

    @Test
    fun answerWithoutPriceStillConfirmsNotTraded() {
        val noPrice = ExchangeParseException(UserFriendlyExchangeError.NO_TICKER_DATA)
        assertTrue(NotTraded.confirmedBySingle(false, noPrice.message, noPrice.toExchangeFailure()))
        // Interner Fehler ohne Text: kein «nicht mehr gehandelt» (früher 6 h stumm)
        assertFalse(NotTraded.confirmedBySingle(false, null, ExchangeFailure.Other))
        assertFalse(NotTraded.confirmedBySingle(false, "", IllegalStateException().toExchangeFailure()))
        assertFalse(NotTraded.confirmedBySingle(false, " ", ExchangeFailure.Parse))
        // Ohne Art (gespeicherte Ergebnisse) wie bisher
        assertTrue(NotTraded.confirmedBySingle(false, null))
    }
}
