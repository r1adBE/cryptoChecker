package com.cryptochecker.app.domain.refresh
import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.watch.NotTraded
import org.junit.Assert.assertEquals
import org.junit.Test
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj

/**
 * Art des Fehlers neben dem Text (`testdata/parity/exchange_failure.json`): Die Art entscheidet, wo sie kann; sonst
 * und für gespeicherte Texte (ohne Art) die Stichwörter. Gegenstück: `Tests/ExchangeFailureParityTests.swift`.
 */
class ExchangeFailureParityTest {

    private val data = ParityJson.load("exchange_failure.json")

    private fun failure(raw: Any?): ExchangeFailure? {
        val f = raw as? Map<*, *> ?: return null
        return when (val kind = f["kind"] as String) {
            "timeout" -> ExchangeFailure.Timeout
            "offline" -> ExchangeFailure.Offline
            "ssl" -> ExchangeFailure.Ssl
            "httpStatus" -> ExchangeFailure.HttpStatus(f["code"].num()!!.toInt())
            "rateLimited" -> ExchangeFailure.RateLimited(f["retryAfter"].num()?.toLong(), f["code"].num()!!.toInt())
            "parse" -> ExchangeFailure.Parse
            "unknownInstrument" -> ExchangeFailure.UnknownInstrument
            "message" -> ExchangeFailure.Message(f["detail"] as String)
            "other" -> ExchangeFailure.Other
            else -> error("unknown kind $kind")
        }
    }

    @Test
    fun classify() {
        val cases = data["classify"].list()
        check(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val text = c["text"] as String?
            assertEquals(
                "classify: ${c["failure"]} / $text",
                RefreshFailure.valueOf(c["expected"] as String),
                RefreshReportLogic.classify(failure(c["failure"]), text),
            )
        }
    }

    @Test
    fun httpStatus() {
        data["httpStatus"].list().forEach { raw ->
            val c = raw.obj()
            val text = c["text"] as String?
            assertEquals(
                "httpStatus: ${c["failure"]} / $text",
                c["expected"].num()?.toInt(),
                RefreshReportLogic.httpStatus(failure(c["failure"]), text),
            )
        }
    }

    /** HTTP 451 im gespeicherten Text = Standort-Sperre (eigene Meldung statt «nicht erreichbar»). */
    @Test
    fun regionBlocked() {
        data["regionBlocked"].list().forEach { raw ->
            val c = raw.obj()
            val text = c["text"] as String?
            assertEquals("regionBlocked: $text", c["expected"], RefreshReportLogic.isRegionBlocked(text))
        }
    }

    /** Paarliste in mehreren Anfragen: vorübergehende Störung einer Teilanfrage bricht ab (N3-08). */
    @Test
    fun pairListAborts() {
        val cases = data["pairListAborts"].list()
        check(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            assertEquals(
                "pairListAborts: $c",
                c["aborts"],
                pairListCallAborts(c["call"].num()!!.toInt(), c["allRequired"] as Boolean, failure(c["failure"])!!),
            )
        }
    }

    /** Paarliste im Portfolio: Versuch nur mit Ergebnis gemerkt, Netzfehler nicht (N3-05). */
    @Test
    fun pairListAttempt() {
        val cases = data["pairListAttempt"].list()
        check(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            assertEquals("pairListAttempt: $c", c["remembers"], remembersPairListAttempt(failure(c["failure"])))
        }
    }

    /** HTTP 403 ohne Bremsen-Text = Zugriff verweigert (eigene Meldung, kein «erneut versuchen»; N3-07). */
    @Test
    fun accessDenied() {
        val cases = data["accessDenied"].list()
        check(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val text = c["text"] as String?
            assertEquals("accessDenied: $text", c["expected"], RefreshReportLogic.isAccessDenied(text))
        }
    }

    @Test
    fun confirmedBySingle() {
        data["confirmedBySingle"].list().forEach { raw ->
            val c = raw.obj()
            val text = c["text"] as String?
            assertEquals(
                "confirmed: ${c["failure"]} / $text",
                c["confirmed"],
                NotTraded.confirmedBySingle(c["gotPrice"] as Boolean, text, failure(c["failure"])),
            )
        }
    }
}
