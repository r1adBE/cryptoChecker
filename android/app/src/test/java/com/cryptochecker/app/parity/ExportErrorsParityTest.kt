package com.cryptochecker.app.parity

import com.cryptochecker.app.R
import com.cryptochecker.app.ui.features.portfolio.ExportFailureKind
import com.cryptochecker.app.ui.features.portfolio.ExportFailures
import com.cryptochecker.app.ui.features.portfolio.ExportWriteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Art eines Export-Fehlers → Text-Schlüssel (`testdata/parity/export_errors.json`).
 * Gegenstück: `Tests/ExportErrorsParityTests.swift`.
 */
class ExportErrorsParityTest {

    private val fixture = ParityJson.load("export_errors.json")

    /** Typischer Android-Fehler je plattformneutralem Namen der Testfälle. */
    private fun failure(name: String): Throwable = when (name) {
        "write_failed" -> ExportWriteException(IOException("write failed"))
        "file_missing" -> FileNotFoundException("gone")
        "no_permission" -> SecurityException("no permission")
        "disk_full" -> ExportWriteException(IOException("ENOSPC (No space left on device)"))
        "wrapped_write" -> RuntimeException(FileNotFoundException("gone"))
        "offline" -> UnknownHostException("api.binance.com")
        "timeout" -> SocketTimeoutException("timeout")
        "host_unreachable" -> ConnectException("refused")
        "exchange_bad_response" -> IOException("HTTP 503")
        "unexpected" -> IllegalStateException("unexpected")
        else -> throw IllegalArgumentException("unbekannter Testfall $name")
    }

    @Test
    fun ordnetZuWieGemeinsameTestfaelle() {
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 10)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["error"] as String
            assertEquals(name, ExportFailureKind.valueOf(c["kind"] as String), ExportFailures.classify(failure(name)))
        }
    }

    @Test
    fun schluesselWieGemeinsameTestfaelle() {
        val keys = fixture["keys"].obj()
        assertEquals(ExportFailureKind.entries.map { it.name }.toSet(), keys.keys)
        for (kind in ExportFailureKind.entries) assertEquals(keys[kind.name], kind.key)
        assertEquals(R.string.portfolio_export_failed_file, ExportFailureKind.FILE.text)
        assertEquals(R.string.portfolio_export_failed_prices, ExportFailureKind.PRICES.text)
        assertEquals(R.string.portfolio_export_failed, ExportFailureKind.OTHER.text)
    }

    /** Jeder Schlüssel (samt «Erneut versuchen») steht in den englischen Standardtexten. */
    @Test
    fun texteVorhanden() {
        val rel = "src/main/res/values"
        val dir = listOf(File(rel), File("app/$rel")).first { it.isDirectory }
        val all = dir.listFiles { f -> f.name.startsWith("strings") }.orEmpty().joinToString("\n") { it.readText() }
        val keys = fixture["keys"].obj().values.map { it as String } + (fixture["retryKey"] as String)
        for (key in keys) assertTrue(key, all.contains("name=\"$key\""))
    }
}
