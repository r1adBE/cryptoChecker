package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.parity.ParityJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Stand vor dem Wiederherstellen im Journal ([BackupJournalCodec]): Hin und zurück über den Text, Kennung und Zeitpunkt
 * im Kopf, fremde Dateien und Journale ohne einen Teil werden abgelehnt.
 */
class BackupJournalCodecTest {

    private val watch = WatchEntity(
        id = 7,
        exchangeKey = "Kraken",
        exchangeName = "Kraken",
        baseAsset = "BTC",
        quoteAsset = "EUR",
        sortOrder = 2,
        notificationEnabled = false,
        favorite = true,
        groupName = "Langfristig",
        note = "Notiz",
        disabled = true,
    )

    private val tx = PortfolioTxEntity(
        id = 4,
        coin = "BTC",
        type = PortfolioTxType.BUY,
        amount = BigDecimal("0.00000001"),
        priceUsdt = BigDecimal("65000.5"),
        time = 1_700_000_000_000L,
        exchange = "Kraken",
    )

    private val alarm = PortfolioAlarmEntity(id = 2, kind = PortfolioAlarmKind.CHANGE_UP, threshold = 5.0, repeating = true)

    private fun previous(
        watches: List<WatchEntity> = listOf(watch),
        favorites: Map<FavoriteKind, Set<String>> = mapOf(FavoriteKind.COIN to setOf("BTC", "ETH")),
    ) = BackupJournalCodec.PreviousState(
        watches = watches,
        alarms = emptyList(),
        portfolio = listOf(tx),
        portfolioAlarms = listOf(alarm),
        watchedAddresses = emptyList(),
        favorites = favorites,
        settings = JSONObject(settingsText),
    )

    /** Vollständige Einstellungen (wie `settingsToJson`), aus `backup_settings.json`. */
    private val settingsText =
        JSONObject(File(ParityJson.dir(), "backup_settings.json").readText()).getJSONObject("settings").toString()

    @Test
    fun journalHeadNamesFormatVersionAndTime() {
        val json = BackupJournalCodec.journalJson(previous(), createdAt = 1_234L)
        assertEquals(RestoreJournal.FORMAT, json.getString("format"))
        assertEquals(RestoreJournal.VERSION, json.getInt("version"))
        assertEquals(1_234L, json.getLong("createdAt"))
        assertTrue(json.has(WatchedAddressRules.BACKUP_KEY))
        assertEquals(0, json.getJSONArray("alarms").length())
    }

    @Test
    fun journalRoundTrip() {
        val text = BackupJournalCodec.journalJson(previous(), createdAt = 1L).toString()
        val back = BackupJournalCodec.previousFromJournal(JSONObject(text))

        assertEquals(listOf(watch), back.watches)
        assertTrue(back.alarms.isEmpty())
        val restored = back.portfolio.single()
        assertEquals(4L, restored.id)
        assertEquals("BTC", restored.coin)
        assertEquals(PortfolioTxType.BUY, restored.type)
        // Exakt, auch ein Satoshi
        assertEquals(0, BigDecimal("0.00000001").compareTo(restored.amount))
        assertEquals(0, BigDecimal("65000.5").compareTo(restored.priceUsdt!!))
        assertEquals(1_700_000_000_000L, restored.time)
        assertEquals("Kraken", restored.exchange)
        val restoredAlarm = back.portfolioAlarms.single()
        assertEquals(PortfolioAlarmKind.CHANGE_UP, restoredAlarm.kind)
        assertEquals(5.0, restoredAlarm.threshold, 0.0)
        assertTrue(restoredAlarm.repeating)
        assertTrue(back.watchedAddresses.isEmpty())
        // Alle Arten von Favoriten, fehlende leer
        assertEquals(
            mapOf(FavoriteKind.EXCHANGE to emptySet<String>(), FavoriteKind.COIN to setOf("BTC", "ETH"), FavoriteKind.QUOTE to emptySet<String>()),
            back.favorites,
        )
        assertEquals("JPY", back.settings.getString("portfolioCurrency"))
    }

    @Test
    fun emptyStateRoundTrip() {
        val text = BackupJournalCodec.journalJson(previous(watches = emptyList(), favorites = emptyMap())).toString()
        val back = BackupJournalCodec.previousFromJournal(JSONObject(text))
        assertTrue(back.watches.isEmpty())
        assertTrue(back.favorites.values.all { it.isEmpty() })
        assertEquals(FavoriteKind.entries.toSet(), back.favorites.keys)
    }

    @Test
    fun journalWithoutAPartIsRejected() {
        val full = BackupJournalCodec.journalJson(previous(), createdAt = 1L)
        for (key in listOf("version", "createdAt", "watches", "alarms", "portfolio", "portfolioAlarms",
            WatchedAddressRules.BACKUP_KEY, "favorites", "settings")) {
            val without = JSONObject(full.toString()).apply { remove(key) }
            assertThrows(key, IllegalArgumentException::class.java) { BackupJournalCodec.previousFromJournal(without) }
        }
    }

    @Test
    fun foreignFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupJournalCodec.previousFromJournal(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupJournalCodec.previousFromJournal(JSONObject().put("format", "cryptochecker-backup"))
        }
    }

    @Test
    fun nonTextFavoritesAreRejected() {
        val root = BackupJournalCodec.journalJson(previous(), createdAt = 1L)
        root.getJSONObject("favorites").put("EXCHANGE", org.json.JSONArray().put("Binance").put(5))
        assertThrows(IllegalArgumentException::class.java) { BackupJournalCodec.previousFromJournal(root) }
    }
}
