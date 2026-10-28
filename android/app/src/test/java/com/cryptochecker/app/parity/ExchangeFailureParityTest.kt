package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.watch.NotTraded
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Art des Fehlers neben dem Text (`testdata/parity/market_failure.json`): Die Art entscheidet, wo sie kann; sonst
 * und für gespeicherte ältere Texte die Stichwörter. Gegenstück: `Tests/ExchangeFailureParityTests.swift`.
 */
class ExchangeFailureParityTest {

    private val data = ParityJson.load("market_failure.json")

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
