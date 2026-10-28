package com.cryptochecker.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrationen gegen die exportierten Schemas (`app/schemas`, Review TST-01): [MigrationTestHelper] legt
 * die alte Datenbank genau nach `<Version>.json` an — nicht nachgebaut wie in [DatabaseMigrationTest] —, füllt sie, führt
 * die App-Migrationen aus und prüft das Ergebnis gegen das Schema der Zielversion. So fällt auf, wenn eine Migration
 * oder ein nachgebautes Test-Schema vom wirklich ausgelieferten Stand abweicht.
 *
 * PortfolioDatabase: alle Versionen 1–5 → 6. AppDatabase steht bei Version 1 (noch keine Migration); jede neue Version
 * bekommt hier einen Migrationstest mit Daten (RoomSchemaRulesTest).
 * Unverschlüsselt (FrameworkSQLiteOpenHelperFactory): Geprüft wird das Schema; SQLCipher ändert daran nichts. Die
 * Schemas legt das Room-Gradle-Plugin selbst als Assets des Test-APK bei.
 */
@RunWith(AndroidJUnit4::class)
class RoomSchemaMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val portfolioHelper =
        MigrationTestHelper(instrumentation, PortfolioDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Before
    @After
    fun clean() {
        instrumentation.targetContext.deleteDatabase(PORTFOLIO_DB)
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
        const val PORTFOLIO_DB = "schema-migration-test-portfolio.db"
    }
}
