package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.model.AlarmRow
import com.cryptochecker.app.data.local.model.AlarmWithWatchRow
import com.cryptochecker.app.data.local.model.WatchRow
import com.cryptochecker.app.data.local.model.toRow
import com.cryptochecker.app.domain.model.AlarmCondition
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.WatchEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.reflect.Proxy

/**
 * [WatchDao] bildet an der Grenze zu Room ab: Die Funktionen für die Fachmodelle rufen die gleichnamigen
 * «…Row(s)»-Funktionen (Room) und geben bzw. nehmen dieselben Werte. Room selbst läuft nur auf dem Gerät; hier
 * antwortet ein Proxy für die Room-Seite, die abbildenden Funktionen laufen wie geschrieben.
 */
class WatchDaoMappingTest {

    private val watch = WatchEntity(
        id = 7, exchangeKey = "kraken", exchangeName = "Kraken", baseAsset = "BTC", quoteAsset = "CHF",
        lastPrice = 99_800.0, favorite = true, note = "Notiz", priceChangedAt = 5,
    )
    private val alarm = AlarmEntity(
        id = 9, watchId = 7, condition = AlarmCondition.PRICE_ABOVE, threshold = 100_000.0, windowHours = 4, currency = "CHF",
    )

    /** Aufrufe der Room-Seite: Name → Argumente (ohne die Fortsetzung der suspend-Funktionen). */
    private val calls = ArrayList<Pair<String, List<Any?>>>()

    private fun dao(rows: Map<String, Any?>): WatchDao {
        val lookup = MethodHandles.privateLookupIn(WatchDao::class.java, MethodHandles.lookup())
        val proxy = Proxy.newProxyInstance(WatchDao::class.java.classLoader, arrayOf(WatchDao::class.java)) { self, method, args ->
            val given = args ?: emptyArray()
            when {
                method.name in rows -> {
                    calls += method.name to given.filterNot { it is kotlin.coroutines.Continuation<*> }
                    rows[method.name]
                }
                method.isDefault -> lookup.unreflectSpecial(method, WatchDao::class.java).bindTo(self).invokeWithArguments(*given)
                else -> throw UnsupportedOperationException("WatchDao.${method.name} im Test nicht vorgesehen")
            }
        }
        return WatchDao::class.java.cast(proxy)
    }

    @Test
    fun readsMapRowsToModels() = runBlocking {
        val dao = dao(
            mapOf(
                "getWatchRows" to listOf(watch.toRow()), "getWatchRow" to watch.toRow(),
                "observeWatchRows" to flowOf(listOf(watch.toRow())), "observeWatchRow" to flowOf<WatchRow?>(watch.toRow()),
                "getAlarmRows" to listOf(alarm.toRow()), "getEnabledAlarmRows" to listOf(alarm.toRow()),
                "getAllEnabledAlarmRows" to listOf(alarm.toRow()), "getAllAlarmRows" to listOf(alarm.toRow()),
                "getAlarmRow" to alarm.toRow(), "observeAlarmRows" to flowOf(listOf(alarm.toRow())),
                "observeAllAlarmRows" to flowOf(listOf(AlarmWithWatchRow(alarm.toRow(), watch.toRow()))),
            ),
        )
        assertEquals(listOf(watch), dao.getWatches())
        assertEquals(watch, dao.getWatch(7))
        assertEquals(listOf(watch), dao.observeWatches().first())
        assertEquals(watch, dao.observeWatch(7).first())
        assertEquals(listOf(alarm), dao.getAlarms(7))
        assertEquals(listOf(alarm), dao.getEnabledAlarms(7))
        assertEquals(listOf(alarm), dao.getAllEnabledAlarms())
        assertEquals(listOf(alarm), dao.getAllAlarms())
        assertEquals(alarm, dao.getAlarm(9))
        assertEquals(listOf(alarm), dao.observeAlarms(7).first())
        val joined = dao.observeAllAlarms().first().single()
        assertEquals(alarm, joined.alarm)
        assertEquals(watch, joined.watch)
        // Die Kennung geht unverändert an Room
        assertEquals(listOf<Any?>(7L), calls.first { it.first == "getWatchRow" }.second)
        assertEquals(listOf<Any?>(9L), calls.first { it.first == "getAlarmRow" }.second)
    }

    @Test
    fun missingRowsStayNull() = runBlocking {
        val dao = dao(mapOf("getWatchRow" to null, "getAlarmRow" to null, "observeWatchRow" to flowOf<WatchRow?>(null)))
        assertNull(dao.getWatch(1))
        assertNull(dao.getAlarm(1))
        assertNull(dao.observeWatch(1).first())
    }

    @Test
    fun writesHandRowsToRoom() = runBlocking {
        val dao = dao(mapOf("insertWatchRow" to 7L, "updateWatchRow" to Unit, "insertAlarmRow" to 9L, "updateAlarmRow" to Unit))
        assertEquals(7L, dao.insertWatch(watch))
        dao.updateWatch(watch)
        assertEquals(9L, dao.insertAlarm(alarm))
        dao.updateAlarm(alarm)
        assertEquals(
            listOf<Pair<String, List<Any?>>>(
                "insertWatchRow" to listOf(watch.toRow()), "updateWatchRow" to listOf(watch.toRow()),
                "insertAlarmRow" to listOf(alarm.toRow()), "updateAlarmRow" to listOf(alarm.toRow()),
            ),
            calls,
        )
        val row: AlarmRow = alarm.toRow()
        assertEquals(alarm.windowHours, row.windowHours)
    }
}
