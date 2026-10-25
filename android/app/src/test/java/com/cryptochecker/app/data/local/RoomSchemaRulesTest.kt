package com.cryptochecker.app.data.local

import androidx.room.migration.Migration
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.parity.ParityJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regel für jede Datenbank-Version: Zur aktuellen Version N gehören das Golden-Schema
 * `app/schemas/<Datenbank>/N.json` (von Room exportiert, nie von Hand geändert), das Schema N-1, die Migration
 * N-1 → N in `ALL_MIGRATIONS` und ein Migrationstest MIT DATEN in `androidTest/…/RoomSchemaMigrationTest.kt`
 * (MigrationTestHelper: alte Datenbank genau nach `N-1.json` anlegen, füllen, migrieren, Werte prüfen).
 *
 * Neue Version: Schema committen, Datentest schreiben und hier in [MAIN_DATA_TESTS] bzw. [PORTFOLIO_DATA_TESTS]
 * eintragen — sonst schlägt dieser Test fehl. Gilt für AppDatabase und PortfolioDatabase (getrennte Dateien).
 */
class RoomSchemaRulesTest {

    private val android: File = ParityJson.dir().parentFile!!.parentFile!!
    private val schemas = File(android, "app/schemas")
    private val migrationTest =
        File(android, "app/src/androidTest/java/com/cryptochecker/app/RoomSchemaMigrationTest.kt").readText()

    @Test
    fun mainDatabaseHasSchemaAndDataMigrationTest() =
        check(AppDatabase::class.java.name, AppDatabase.VERSION, AppDatabase.ALL_MIGRATIONS, MAIN_DATA_TESTS)

    @Test
    fun portfolioDatabaseHasSchemaAndDataMigrationTest() = check(
        PortfolioDatabase::class.java.name, PortfolioDatabase.VERSION, PortfolioDatabase.ALL_MIGRATIONS,
        PORTFOLIO_DATA_TESTS,
    )

    private fun check(database: String, current: Int, migrations: Array<Migration>, dataTests: Map<Int, String>) {
        assertTrue("$database: Version $current fehlt in der Liste der Datentests", current in dataTests)
        assertTrue("$database: Einträge über der aktuellen Version $current", dataTests.keys.all { it <= current })
        for ((version, testName) in dataTests) {
            for (schemaVersion in listOf(version - 1, version)) {
                val file = File(schemas, "$database/$schemaVersion.json")
                assertTrue("$database: Schema $file fehlt (Room exportiert es beim Bauen)", file.isFile)
                assertEquals("$database: Version in $file", schemaVersion, schemaVersionOf(file.readText()))
            }
            assertTrue(
                "$database: Migration ${version - 1} → $version fehlt in ALL_MIGRATIONS",
                migrations.any { it.startVersion == version - 1 && it.endVersion == version },
            )
            val path = "${version - 1}To$version"
            assertTrue("$database: $testName nennt nicht $path", path in testName)
            val declared = Regex("""@Test\s+fun\s+${Regex.escape(testName)}\s*\(""").containsMatchIn(migrationTest)
            assertTrue("$database: RoomSchemaMigrationTest.$testName fehlt", declared)
        }
    }

    /** `"database": {"version": N, …}` aus der Schema-Datei. */
    private fun schemaVersionOf(json: String): Int? =
        Regex(""""database"\s*:\s*\{\s*"version"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toInt()

    private companion object {
        /** Version → Datentest (MigrationTestHelper) des Wegs Version-1 → Version. */
        val MAIN_DATA_TESTS = mapOf(
            16 to "mainVersion15To16KeepsWatchlistAndAlarms",
        )
        val PORTFOLIO_DATA_TESTS = mapOf(
            6 to "portfolioVersion5To6",
        )
    }
}
