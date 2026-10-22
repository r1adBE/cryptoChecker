package com.cryptochecker.app

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrationen gegen die exportierten Schemas (`app/schemas`, seit Build 103, Review TST-01): [MigrationTestHelper] legt
 * die alte Datenbank genau nach `<Version>.json` an — nicht nachgebaut wie in [DatabaseMigrationTest] —, füllt sie, führt
 * die App-Migrationen aus und prüft das Ergebnis gegen das Schema der Zielversion. So fällt auf, wenn eine Migration
 * oder ein nachgebautes Test-Schema vom wirklich ausgelieferten Stand abweicht.
 *
 * AppDatabase: die realen Update-Wege früherer Builds der aktuellen Linie (Datenbank v13/v14/v15 → v16) mit Daten, dazu
 * v1–v3 (die übrigen alten Schemas 4–12 wurden nie exportiert) nur als Schemaprüfung. Korrigiert seit Build 104: 16.2.x
 * hatte Datenbank v12 (nicht v13–v15); dessen Weg 12 → 13 prüft mangels Schema nur [DatabaseMigrationTest]. PortfolioDatabase: alle Versionen 1–5 → 6.
 * Unverschlüsselt (FrameworkSQLiteOpenHelperFactory): Geprüft wird das Schema; SQLCipher ändert daran nichts. Die
 * Schemas legt das Room-Gradle-Plugin selbst als Assets des Test-APK bei.
 */
@RunWith(AndroidJUnit4::class)
class RoomSchemaMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val mainHelper = MigrationTestHelper(instrumentation, AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @get:Rule
    val portfolioHelper =
        MigrationTestHelper(instrumentation, PortfolioDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Before
    @After
    fun clean() {
        instrumentation.targetContext.deleteDatabase(MAIN_DB)
        instrumentation.targetContext.deleteDatabase(PORTFOLIO_DB)
    }

    // ---------------- AppDatabase ----------------

    @Test
    fun mainVersion13To16KeepsWatchlistAndAlarms() = upgradeMain(13)

    @Test
    fun mainVersion14To16KeepsWatchlistAndAlarms() = upgradeMain(14)

    @Test
    fun mainVersion15To16KeepsWatchlistAndAlarms() = upgradeMain(15)

    private fun upgradeMain(version: Int) {
        mainHelper.createDatabase(MAIN_DB, version).use { db -> insertMain(db, version) }

        mainHelper.runMigrationsAndValidate(MAIN_DB, AppDatabase.VERSION, true, *AppDatabase.ALL_MIGRATIONS).use { db ->
            assertEquals(AppDatabase.VERSION, db.version)
            db.query(
                "SELECT marketKey, baseAsset, pairId, favorite, groupName, note, change24h, disabled, priceChangedAt, " +
                    "exchangeTime, notifiedAt FROM watches WHERE id = 1",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Binance", c.getString(0))
                assertEquals("BTC", c.getString(1))
                assertEquals("BTCUSDT", c.getString(2))
                assertEquals(1, c.getInt(3))
                assertEquals("Layer 1", c.getString(4))
                assertEquals("Ø 77 500", c.getString(5))
                assertEquals(2.5, c.getDouble(6), 0.0)
                // v14: deaktiviert nur, wenn es vorher schon so gespeichert war
                assertEquals(if (version >= 14) 1 else 0, c.getInt(7))
                // v15: ohne Wert 0 bzw. leer (die nächste Aktualisierung setzt sie)
                assertEquals(if (version >= 15) 1_700_000_100_000L else 0L, c.getLong(8))
                if (version >= 15) assertEquals(1_700_000_050_000L, c.getLong(9)) else assertTrue(c.isNull(9))
                assertEquals(1_700_000_000_000L, c.getLong(10))
            }
            db.query("SELECT watchId, condition, threshold, windowHours, currency FROM alarms").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals("PRICE_BELOW", c.getString(1))
                assertEquals(90_000.0, c.getDouble(2), 0.0)
                assertEquals(4, c.getInt(3))
                assertEquals("CHF", c.getString(4))
            }
            db.query("SELECT marketPairId, tradFi FROM market_pairs").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ETHUSDT", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
            // v16: zwei Pools mit gleichem Symbol auf derselben Chain sind zwei Einträge (Index mit pairId) …
            db.execSQL(watchSql(id = 2, pairId = "solana:poolA", base = "WIF", market = "DexScreener", version = 16))
            db.execSQL(watchSql(id = 3, pairId = "solana:poolB", base = "WIF", market = "DexScreener", version = 16))
            // … derselbe Pool zweimal bleibt verboten
            try {
                db.execSQL(watchSql(id = 4, pairId = "solana:poolA", base = "WIF", market = "DexScreener", version = 16))
                fail("doppelter Pool erwartet abgelehnt")
            } catch (expected: SQLiteConstraintException) {
                // erwartet
            }
        }
    }

    @Test
    fun mainOldExportedSchemasReachVersion16() {
        // Alte Kette nur gegen das Schema: Daten prüft DatabaseMigrationTest (v1/v2/v3 mit Room). Nicht auf übrige Tabellen
        // prüfen — ab v13 liegen dort `legacy_*` für die einmalige Übernahme ins Portfolio
        for (version in listOf(1, 2, 3)) {
            clean()
            mainHelper.createDatabase(MAIN_DB, version).close()
            mainHelper.runMigrationsAndValidate(MAIN_DB, AppDatabase.VERSION, false, *AppDatabase.ALL_MIGRATIONS).close()
        }
    }

    private fun insertMain(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL("INSERT INTO markets (id, marketKey, updateDate) VALUES (1, 'Binance', 1)")
        db.execSQL(
            "INSERT INTO market_pairs (marketId, baseAsset, quoteAsset, contractType, marketPairId, tradFi) " +
                "VALUES (1, 'ETH', 'USDT', 'NONE', 'ETHUSDT', 0)",
        )
        db.execSQL(watchSql(id = 1, pairId = "BTCUSDT", base = "BTC", market = "Binance", version = version))
        db.execSQL(
            "INSERT INTO alarms (watchId, condition, threshold, enabled, repeating, sound, vibrate, speak, referencePrice, " +
                "lastTriggeredAt, lastTriggeredPrice, windowHours, referenceAt, currency) " +
                "VALUES (1, 'PRICE_BELOW', 90000.0, 1, 0, 1, 1, 0, NULL, 0, NULL, 4, 0, 'CHF')",
        )
    }

    /** Ein Paar mit allen Spalten, die es in [version] gibt (v14: `disabled`, v15: `priceChangedAt`, `exchangeTime`). */
    private fun watchSql(id: Int, pairId: String, base: String, market: String, version: Int): String {
        val columns = StringBuilder(
            "id, marketKey, marketName, baseAsset, quoteAsset, contractType, pairId, sortOrder, notificationEnabled, " +
                "ttsEnabled, lastPrice, previousPrice, lastUpdate, notifiedPrice, notifiedAt, lastError, favorite, holdings, " +
                "groupName, note, change24h",
        )
        val values = StringBuilder(
            "$id, '$market', '$market', '$base', 'USDT', 'NONE', '$pairId', $id, 1, 0, 98000.0, 97000.0, 1700000000000, " +
                "97500.0, 1700000000000, NULL, 1, NULL, 'Layer 1', 'Ø 77 500', 2.5",
        )
        if (version >= 14) {
            columns.append(", disabled")
            values.append(", 1")
        }
        if (version >= 15) {
            columns.append(", priceChangedAt, exchangeTime")
            values.append(", 1700000100000, 1700000050000")
        }
        return "INSERT INTO watches ($columns) VALUES ($values)"
    }

    // ---------------- PortfolioDatabase ----------------

    @Test
    fun portfolioVersion1To6() = upgradePortfolio(1)

    @Test
    fun portfolioVersion2To6() = upgradePortfolio(2)

    @Test
    fun portfolioVersion3To6() = upgradePortfolio(3)

    @Test
    fun portfolioVersion4To6() = upgradePortfolio(4)

    @Test
    fun portfolioVersion5To6() = upgradePortfolio(5)

    private fun upgradePortfolio(version: Int) {
        portfolioHelper.createDatabase(PORTFOLIO_DB, version).use { db -> insertPortfolio(db, version) }

        portfolioHelper.runMigrationsAndValidate(
            PORTFOLIO_DB, PortfolioDatabase.VERSION, true, *PortfolioDatabase.ALL_MIGRATIONS,
        ).use { db ->
            assertEquals(PortfolioDatabase.VERSION, db.version)
            // v2: Menge und Preis als Text (exakt); gleiche Id
            db.query("SELECT coin, type, amount, priceUsdt, note FROM portfolio_tx WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("BTC", c.getString(0))
                assertEquals("BUY", c.getString(1))
                assertEquals("0.05", c.getString(2))
                assertEquals(77_500.0, c.getString(3).toDouble(), 0.0)
                assertEquals("DCA", c.getString(4))
            }
            db.query("SELECT kind, threshold, currency FROM portfolio_alarms WHERE id = 3").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("VALUE_ABOVE", c.getString(0))
                assertEquals(50_000.0, c.getDouble(1), 0.0)
                assertEquals("CHF", c.getString(2))
            }
            if (version >= 3) {
                // v4: Merker «@spouse» wird zur eigenen Spalte (aus v4/v5 schon so gespeichert)
                db.query("SELECT transferTo, giftToSpouse FROM portfolio_tx WHERE id = 8").use { c ->
                    assertTrue(c.moveToFirst())
                    assertTrue(c.isNull(0))
                    assertEquals(1, c.getInt(1))
                }
                // v6: bestehende Adresse ohne Eröffnungsposten
                db.query("SELECT label, lastBalance, costLots FROM watched_addresses WHERE id = 1").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("Cold", c.getString(0))
                    assertEquals("0.5", c.getString(1))
                    assertTrue(c.isNull(2))
                }
            }
            if (version >= 4) {
                // v5: Gebühr-Zeile (Verkauf zum Preis 0 mit Import-Herkunft) und Zins (Notiz) als eigene Merker
                db.query("SELECT id, feeRow, interest FROM portfolio_tx WHERE id IN (10, 11) ORDER BY id").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(1, c.getInt(1))
                    assertEquals(0, c.getInt(2))
                    assertTrue(c.moveToNext())
                    assertEquals(0, c.getInt(1))
                    assertEquals(1, c.getInt(2))
                }
            }
        }
    }

    private fun insertPortfolio(db: SupportSQLiteDatabase, version: Int) {
        if (version == 1) {
            db.execSQL(
                "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note) " +
                    "VALUES (7, 'BTC', 'BUY', 0.05, 77500.0, 1744070400000, 'DCA')",
            )
        } else {
            db.execSQL(
                "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note) " +
                    "VALUES (7, 'BTC', 'BUY', '0.05', '77500', 1744070400000, 'DCA')",
            )
        }
        db.execSQL(
            "INSERT INTO portfolio_alarms (id, kind, threshold, currency, enabled, repeating, referenceAt, lastTriggeredAt, " +
                "lastTriggeredValue) VALUES (3, 'VALUE_ABOVE', 50000.0, 'CHF', 1, 0, 0, 0, NULL)",
        )
        if (version >= 3) {
            // Schenkung an den Partner: v3 mit Merker im Feld «nach», ab v4 eigene Spalte
            if (version == 3) {
                db.execSQL(
                    "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note, transferTo) " +
                        "VALUES (8, 'BTC', 'GIFT', '0.01', NULL, 1744156800000, NULL, '@spouse')",
                )
            } else {
                db.execSQL(
                    "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note, transferTo, giftToSpouse) " +
                        "VALUES (8, 'BTC', 'GIFT', '0.01', NULL, 1744156800000, NULL, NULL, 1)",
                )
            }
            db.execSQL(
                "INSERT INTO watched_addresses (id, chain, address, label, lastBalance, lastUpdated, lastAttempt, addedAt) " +
                    "VALUES (1, 'BTC', 'bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq', 'Cold', '0.5', 10, 10, 5)",
            )
        }
        if (version >= 4) {
            // Gebühr-Zeile und Zins: v4 nach alter Regel abgeleitet, v5 schon mit Merker gespeichert
            val v5 = version >= 5
            db.execSQL(
                "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note, importKey" +
                    (if (v5) ", feeRow" else "") + ") VALUES (10, 'BNB', 'SELL', '0.001', '0', 1744243200000, NULL, " +
                    "'binance:fee:1'" + (if (v5) ", 1" else "") + ")",
            )
            db.execSQL(
                "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note" +
                    (if (v5) ", interest" else "") + ") VALUES (11, 'USDT', 'REWARD', '1.5', '1', 1744329600000, " +
                    "'Lending interest'" + (if (v5) ", 1" else "") + ")",
            )
        }
    }

    private companion object {
        const val MAIN_DB = "schema-migration-test.db"
        const val PORTFOLIO_DB = "schema-migration-test-portfolio.db"
    }
}
