package com.cryptochecker.app.domain.exceptions

import com.cryptochecker.app.domain.refresh.ExchangeFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * Verbindung mitten in der Antwort getrennt (N3-02): jede Ein-/Ausgabe-Ausnahme beim Transport ist «keine
 * Verbindung» (wiederholbar), nicht «Etwas ist schiefgelaufen» — wie iOS `.networkConnectionLost`.
 */
class NetworkFailureKindTest {

    @Test
    fun transportFailuresAreOffline() {
        listOf(
            SocketException("Software caused connection abort"),
            SocketException("Socket closed"),
            ProtocolException("unexpected end of stream"),
            IOException("stream was reset: CANCEL"),
            UnknownHostException("api.example.com"),
        ).forEach { cause ->
            assertEquals(cause.toString(), ExchangeFailure.Offline, parseExchangeError(cause).toExchangeFailure())
        }
    }

    @Test
    fun ownErrorsKeepTheirKind() {
        assertEquals(ExchangeFailure.Timeout, parseExchangeError(java.net.SocketTimeoutException("read")).toExchangeFailure())
        assertEquals(ExchangeFailure.Other, ResponseTooLargeError().toExchangeFailure())
        assertEquals(ExchangeFailure.Other, parseExchangeError(IllegalStateException("x")).toExchangeFailure())
    }
}
