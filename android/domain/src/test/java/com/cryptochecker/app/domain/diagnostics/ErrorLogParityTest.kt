package com.cryptochecker.app.domain.diagnostics

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fehlerprotokoll als gemeinsame Testfälle (`testdata/parity/error_log.json`, Kopie in iOS `Tests/Parity/`, gelesen von
 * `ErrorLogParityTests.swift`): gefilterte Felder ohne freien Text, Zeilenformat, Wiederholungen, Obergrenze, Bericht.
 */
class ErrorLogParityTest {

    private val data = ParityJson.load("error_log.json")

    private fun strings(value: Any?): List<String> = value.list().map { it as String }

    @Test
    fun limitAsShared() {
        assertEquals(data["maxEntries"].num()!!.toInt(), ErrorLog.MAX_ENTRIES)
    }

    @Test
    fun fieldKeepsNoFreeText() {
        data["field"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            assertEquals(c["name"] as String, c["field"], ErrorLog.field(c["text"] as String?))
        }
    }

    @Test
    fun storedLine() {
        data["line"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val line = ErrorLog.line(
                c["millis"].num()!!.toLong(),
                (c["level"] as String).single(),
                c["area"] as String,
                c["type"] as String?
            )
            assertEquals(c["name"] as String, c["line"], line)
        }
    }

    @Test
    fun parse() {
        data["parse"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val want = (c["entry"] as List<*>?)?.let {
                ErrorLog.Entry((it[0] as Number).toLong(), it[1] as String, it[2] as String, it[3] as String)
            }
            assertEquals(c["name"] as String, want, ErrorLog.parse(c["line"] as String))
        }
    }

    @Test
    fun appended() {
        data["appended"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            assertEquals(c["name"] as String, strings(c["result"]), ErrorLog.appended(strings(c["lines"]), c["new"] as String))
        }
    }

    @Test
    fun shown() {
        data["shown"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val shown = ErrorLog.shown(strings(c["lines"]), ZoneId.of(c["zone"] as String))
            assertEquals(c["name"] as String, strings(c["shown"]), shown)
        }
    }
}
