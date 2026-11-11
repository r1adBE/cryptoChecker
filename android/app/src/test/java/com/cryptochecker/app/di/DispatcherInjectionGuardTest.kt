package com.cryptochecker.app.di

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über fest verdrahtete Dispatcher: Dateien mit einer per Hilt erzeugten Klasse (`@Inject constructor`) in :app
 * und :data nennen `Dispatchers.IO`/`Dispatchers.Default` nicht selbst, sondern nehmen den Dispatcher im Konstruktor
 * (`@param:IoDispatcher`, `@param:DefaultDispatcher`, geliefert von [DispatcherModule]).
 *
 * Noch nicht umgestellte Dateien stehen mit ihrer Anzahl in [REMAINING] (Konstruktor schon an der Obergrenze der
 * Abhängigkeiten oder Datei an der Zeilengrenze); die Zahl darf nur sinken — wer eine Datei umstellt, senkt sie hier
 * mit, bei null kommt der Eintrag weg. Kommentare und Import-Zeilen zählen nicht.
 */
class DispatcherInjectionGuardTest {

    @Test
    fun hiltKlassenVerdrahtenKeinenDispatcherFest() {
        val counts = hiltFiles().mapValues { (_, file) -> literals(file.readText(Charsets.UTF_8)) }
        assertTrue("keine per Hilt erzeugten Klassen gefunden", counts.size > 50)
        val grown = counts.filter { (name, count) -> count > (REMAINING[name] ?: 0) }.toSortedMap()
            .map { (name, count) -> "$name: $count, erlaubt ${REMAINING[name] ?: 0}" }
        assertTrue(
            "Dispatchers.IO/Default fest verdrahtet — im Konstruktor nehmen (@param:IoDispatcher/@param:DefaultDispatcher):\n" +
                grown.joinToString("\n"),
            grown.isEmpty(),
        )
        val stale = REMAINING.filter { (name, allowed) -> (counts[name] ?: 0) < allowed }.toSortedMap()
            .map { (name, allowed) -> "$name: ${counts[name] ?: 0} statt $allowed" }
        assertTrue("Zahl in REMAINING senken (bei 0 Eintrag entfernen):\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    /** Gegenprobe: Der Zähler sieht Code, aber weder Kommentare noch die Import-Zeile. */
    @Test
    fun zaehlerSchlaegtAn() {
        assertEquals(2, literals("withContext(Dispatchers.IO) { }\nflow.flowOn(kotlinx.coroutines.Dispatchers.Default)"))
        assertEquals(0, literals("import kotlinx.coroutines.Dispatchers\n// Dispatchers.IO\n/** [Dispatchers.Default] */\nval x = 1"))
        assertEquals(0, literals("withContext(Dispatchers.Main) { }\nwithContext(ioDispatcher) { }"))
    }

    /** Anzeigename (`app/…`, `data/…`, relativ zu `src/main/java/com/cryptochecker/`) → Datei mit `@Inject constructor`. */
    private fun hiltFiles(): Map<String, File> {
        val root = SourceGuard.android(androidRoot(), "android/app/src/main")
        return MODULES.flatMap { module ->
            val main = SourceGuard.android(File(root, "$module/src/main").takeIf { it.isDirectory }, "$module/src/main")
            main.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .filter { INJECTED.containsMatchIn(it.readText(Charsets.UTF_8)) }
                .map { "$module/" + it.relativeTo(main).invariantSeparatorsPath.removePrefix("java/com/cryptochecker/") to it }
                .toList()
        }.toMap()
    }

    private fun literals(text: String): Int {
        val code = text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "${'$'}1")
        return LITERAL.findAll(code).count()
    }

    /** Ordner `android/` (mit `app/src/main`). */
    private fun androidRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("app/src/main", "android/app/src/main")) {
                val found = File(dir, rel)
                if (found.isDirectory) return found.parentFile.parentFile.parentFile
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        val MODULES = listOf("app", "data")
        val INJECTED = Regex("""@Inject\s+constructor\b""")
        val LITERAL = Regex("""\bDispatchers\.(?:IO|Default)\b""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""(^|[ \t])//[^\n]*""", RegexOption.MULTILINE)

        /** Datei → höchstens so viele `Dispatchers.IO`/`Dispatchers.Default` im Code; darf nur sinken. */
        val REMAINING: Map<String, Int> = mapOf(
            // Konstruktor an der Obergrenze der Abhängigkeiten (code_metrics.py)
            "app/app/data/BackupManager.kt" to 5,
            "app/app/ui/features/explorer/ExplorerViewModel.kt" to 1,
            "app/app/ui/features/portfolio/PortfolioViewModel.kt" to 3,
            "app/app/ui/features/watchlist/WatchlistSheetData.kt" to 2,
            // Datei an der Zeilengrenze (FileLengthTest)
            "app/app/ui/features/alarms/AlarmsViewModel.kt" to 1,
            "app/app/ui/features/portfolio/PortfolioCsvImportViewModel.kt" to 3,
            "data/app/data/CoinListSync.kt" to 5,
            "data/app/data/LogoImageStore.kt" to 5,
            // 15 Stellen in den Tests bauen die Klasse von Hand; Datei nahe der Zeilengrenze
            "data/app/data/remote/ExchangeRemoteDataSource.kt" to 1,
        )
    }
}
