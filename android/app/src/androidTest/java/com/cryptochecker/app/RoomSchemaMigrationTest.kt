package com.cryptochecker.app

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exportierte Schemas (`app/schemas`) gegen die Entities: [MigrationTestHelper] legt die Datenbank genau
 * nach `<Version>.json` an, danach öffnet Room sie. Room vergleicht dabei den `identityHash` der Datei mit dem der
 * Entities — weicht das Golden-Schema ab, schlägt das Öffnen fehl.
 *
 * AppDatabase steht bei Version 1, PortfolioDatabase bei 2 (Index `time, id`); jede neue Version bekommt hier einen Migrationstest mit
 * Daten (RoomSchemaRulesTest). Unverschlüsselt (FrameworkSQLiteOpenHelperFactory): Geprüft wird das Schema; SQLCipher
 * ändert daran nichts. Die Schemas liegen als Assets im Test-APK (sourceSets in app/build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class RoomSchemaMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val mainHelper =
        MigrationTestHelper(instrumentation, AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @get:Rule
    val portfolioHelper =
        MigrationTestHelper(instrumentation, PortfolioDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Before
    @After
    fun clean() {
        context.deleteDatabase(MAIN_DB)
        context.deleteDatabase(PORTFOLIO_DB)
    }

    @Test
    fun mainSchemaMatchesEntities() {
        mainHelper.createDatabase(MAIN_DB, AppDatabase.VERSION).close()
        assertOpens(Room.databaseBuilder(context, AppDatabase::class.java, MAIN_DB).build(), AppDatabase.VERSION)
    }

    @Test
    fun portfolioSchemaMatchesEntities() {
        portfolioHelper.createDatabase(PORTFOLIO_DB, PortfolioDatabase.VERSION).close()
        assertOpens(
            Room.databaseBuilder(context, PortfolioDatabase::class.java, PORTFOLIO_DB).build(),
            PortfolioDatabase.VERSION,
        )
    }

    /** 1 → 2: Buchungen bleiben Feld für Feld, der neue Index ist da, und Room öffnet die migrierte Datei. */
    @Test
    fun portfolio1To2KeepsTransactions() {
        portfolioHelper.createDatabase(PORTFOLIO_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO portfolio_tx (id, coin, type, amount, priceUsdt, time, note, giftToSpouse, feeRow, interest, " +
                    "stakingMove) VALUES (7, 'BTC', 'BUY', '0.5', '60000', 1700000000000, 'n', 0, 0, 0, 0)"
            )
        }
        portfolioHelper.runMigrationsAndValidate(PORTFOLIO_DB, 2, true, *PortfolioDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT coin, type, amount, priceUsdt, time, note FROM portfolio_tx WHERE id = 7").use { c ->
                assertEquals(true, c.moveToFirst())
                assertEquals("BTC", c.getString(0))
                assertEquals("BUY", c.getString(1))
                assertEquals("0.5", c.getString(2))
                assertEquals("60000", c.getString(3))
                assertEquals(1700000000000L, c.getLong(4))
                assertEquals("n", c.getString(5))
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_portfolio_tx_time_id'").use { c ->
                assertEquals(1, c.count)
            }
        }
        val room = Room.databaseBuilder(context, PortfolioDatabase::class.java, PORTFOLIO_DB)
            .addMigrations(*PortfolioDatabase.ALL_MIGRATIONS)
            .build()
        assertOpens(room, PortfolioDatabase.VERSION)
    }

    /** Öffnen prüft den `identityHash` (sonst «Room cannot verify the data integrity»). */
    private fun assertOpens(room: RoomDatabase, version: Int) {
        try {
            assertEquals(version, room.openHelper.writableDatabase.version)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val MAIN_DB = "schema-test-main.db"
        const val PORTFOLIO_DB = "schema-test-portfolio.db"
    }
}
