package com.cryptochecker.app.contract

import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Synthetische Fehlerantworten der Börsen (`testdata/exchanges/exchange_errors_synthetic.json`): kaputtes JSON,
 * leere Antworten, HTTP 429 und die Ratenbegrenzungen, die [RateLimitSignal] kennt. Den Parser selbst prüft
 * [ExchangeContractTest] (die Datei ist ein gewöhnliches `exchange_*.json`); hier die Regeln darum herum:
 * jeder Fall ist als synthetisch gekennzeichnet, ergibt keinen Kurs (Einzelkurs = Fehler, Sammelabfrage = Fehler
 * oder leer), `httpStatus` + Antwort ergeben genau dann 429, wenn `rateLimit` gilt, und `messageRateLimit`
 * sagt, ob schon der Fehlertext der Börse als «zu viele Anfragen» zählt. Wie `ExchangeErrorContractTests`.
 */
class ExchangeErrorContractTest {

    private fun cases(): List<Map<String, Any?>> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "testdata/exchanges/$FIXTURE")
            if (file.isFile) {
                return ParityJson.parse(file.readText(Charsets.UTF_8)).obj()["cases"].list().map { it.obj() }
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("$FIXTURE not found above ${System.getProperty("user.dir")}")
    }

    private fun pair(spec: Map<String, Any?>) = PairQuery(
        spec["base"] as String,
        spec["quote"] as String,
        spec["id"] as String?,
        FuturesContractType.valueOf(spec["contract"] as? String ?: "NONE"),
    )

    @Test
    fun everyCaseIsSyntheticAndYieldsNoPrice() {
        val all = cases()
        assertTrue("synthetic error cases: ${all.size}", all.size >= 60)
        val problems = all.mapNotNull { c ->
            val expect = c["expect"].obj()
            val noPrice = when (c["kind"]) {
                "single" -> expect["error"] == true
                "bulk" -> expect["error"] == true || expect["count"].num() == 0.0
                else -> false
            }
            when {
                c["synthetic"] != true -> "${c["name"]}: not marked synthetic"
                !noPrice -> "${c["name"]}: an error response must not expect a price"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun rateLimitIsRecognisedExactlyWhereExpected() {
        val problems = ArrayList<String>()
        for (c in cases()) {
            val status = c["httpStatus"].num()!!.toInt()
            val body = c["body"] as String
            val limited = RateLimitSignal.httpCode(status, body) == RateLimitSignal.HTTP_CODE
            if (limited != c["rateLimit"]) {
                problems += "${c["name"]}: rate limit expected ${c["rateLimit"]}, got $limited"
            }

            val wantText = c["messageRateLimit"] as? Boolean ?: continue
            val exchange = ExchangesConfig.EXCHANGES[c["exchangeKey"] as String]!!
            val message = try {
                exchange.readError(c["requestId"].num()?.toInt() ?: 0, body, pair(c["pair"].obj()))
            } catch (_: Exception) {
                null
            }
            if (RateLimitSignal.inText(message) != wantText) {
                problems += "${c["name"]}: error text '$message' as rate limit expected $wantText"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        const val FIXTURE = "exchange_errors_synthetic.json"
    }
}
