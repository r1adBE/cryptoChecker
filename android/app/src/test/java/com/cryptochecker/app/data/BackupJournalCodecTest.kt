package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Stand vor dem Wiederherstellen im Journal ([BackupJournalCodec]): Hin und zurück über den Text, Kennung und Zeitpunkt
 * im Kopf, fremde Dateien werden abgelehnt, fehlende Teile ergeben leere Listen.
 */
class BackupJournalCodecTest {

    private val watch = WatchEntity(
        id = 7,
        marketKey = "Kraken",
        marketName = "Kraken",
        baseAsset = "BTC",
        quoteAsset = "EUR",
        sortOrder = 2,
        notificationEnabled = false,
        favorite = true,
        holdings = 0.5,
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
        settings = JSONObject().put("portfolio_currency", "CHF"),
    )

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
            mapOf(FavoriteKind.MARKET to emptySet<String>(), FavoriteKind.COIN to setOf("BTC", "ETH"), FavoriteKind.QUOTE to emptySet<String>()),
            back.favorites,
        )
        assertEquals("CHF", back.settings.getString("portfolio_currency"))
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
    fun journalWithoutPartsGivesEmptyLists() {
        val back = BackupJournalCodec.previousFromJournal(JSONObject().put("format", RestoreJournal.FORMAT))
        assertTrue(back.watches.isEmpty())
        assertTrue(back.alarms.isEmpty())
        assertTrue(back.portfolio.isEmpty())
        assertTrue(back.portfolioAlarms.isEmpty())
        assertTrue(back.watchedAddresses.isEmpty())
        assertTrue(back.favorites.values.all { it.isEmpty() })
        assertEquals(0, back.settings.length())
    }

    @Test
    fun foreignFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupJournalCodec.previousFromJournal(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupJournalCodec.previousFromJournal(JSONObject().put("format", "cryptochecker-backup"))
        }
    }

    @Test
    fun nonTextFavoritesAreSkipped() {
        val root = JSONObject()
            .put("format", RestoreJournal.FORMAT)
            .put("favorites", JSONObject().put("MARKET", org.json.JSONArray().put("Binance").put(5).put(JSONObject.NULL)))
        val back = BackupJournalCodec.previousFromJournal(root)
        assertEquals(setOf("Binance"), back.favorites[FavoriteKind.MARKET])
        assertFalse(back.favorites.getValue(FavoriteKind.COIN).contains("Binance"))
    }
}
