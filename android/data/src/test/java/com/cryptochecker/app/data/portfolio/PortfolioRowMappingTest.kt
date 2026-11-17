package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.alarm.PortfolioAlarmEntity
import com.cryptochecker.app.domain.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import java.lang.reflect.Modifier
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tabellenzeilen (Room, :data) ↔ Fachmodelle (:domain): gleiche Felder in gleicher Reihenfolge, und der Hin- und Rückweg
 * verliert nichts — mit Werten, die alle von den Standardwerten abweichen (ein vergessenes Feld fiele sonst auf den
 * Standardwert zurück, ohne dass es jemand merkt).
 */
class PortfolioRowMappingTest {

    private fun fields(type: Class<*>) =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name to it.type }

    /** Jedes Feld mit einem Wert ≠ Standardwert (Zahl ungleich 0, Text, true, letzter Enum-Wert). */
    private fun <T : Any> nonDefault(type: Class<T>): T {
        val ctor = type.declaredConstructors.filter { !it.isSynthetic }.maxBy { it.parameterCount }
        val args = ctor.parameterTypes.mapIndexed { i, p ->
            when {
                p == java.lang.Long.TYPE || p == java.lang.Long::class.java -> 1000L + i
                p == java.lang.Double.TYPE || p == java.lang.Double::class.java -> 1.5 + i
                p == java.lang.Boolean.TYPE || p == java.lang.Boolean::class.java -> true
                p == String::class.java -> "v$i"
                p == BigDecimal::class.java -> BigDecimal("0.00000000000000000123456789").add(BigDecimal(i))
                p.isEnum -> p.enumConstants.last()
                p == CsvRestoreState::class.java -> RESTORE
                else -> error("Typ ${p.name} im Test ergänzen")
            }
        }
        return type.cast(ctor.newInstance(*args.toTypedArray()))
    }

    @Test
    fun txRowHasTheSameFieldsAsTheEntity() {
        assertEquals(fields(PortfolioTxEntity::class.java), fields(PortfolioTxRow::class.java))
    }

    @Test
    fun txRoundTripKeepsEveryField() {
        val tx = nonDefault(PortfolioTxEntity::class.java)
        assertNotEquals(PortfolioTxEntity(coin = tx.coin, type = tx.type, amount = tx.amount, time = tx.time), tx)
        assertEquals(tx, tx.toRow().toEntity())
        val row = tx.toRow()
        assertEquals(row, row.toEntity().toRow())
    }

    @Test
    fun alarmRowHasTheSameFieldsAsTheEntity() {
        assertEquals(fields(PortfolioAlarmEntity::class.java), fields(PortfolioAlarmRow::class.java))
    }

    @Test
    fun alarmRoundTripKeepsEveryField() {
        val alarm = nonDefault(PortfolioAlarmEntity::class.java)
        assertNotEquals(PortfolioAlarmEntity(kind = alarm.kind, threshold = alarm.threshold), alarm)
        assertEquals(alarm, alarm.toRow().toEntity())
        val row = alarm.toRow()
        assertEquals(row, row.toEntity().toRow())
    }

    private companion object {
        val RESTORE = CsvRestoreState(
            type = PortfolioTxType.WITHDRAWAL, amount = BigDecimal("1"), priceUsdt = null, time = 100L, fee = null,
            feeUnit = null, feeCoin = null, feeCoinAmount = null, feeCoinPrice = null, transferFrom = null,
            transferTo = null, acquiredTime = null, exchange = null, importKey = "k", importBatch = "old", absorbed = null,
        )
    }
}
