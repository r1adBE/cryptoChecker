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
 * Beide Datenbanken stehen bei Version 1 (keine Migration); jede neue Version bekommt hier einen Migrationstest mit
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
