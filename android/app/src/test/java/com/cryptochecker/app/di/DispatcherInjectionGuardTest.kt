package com.cryptochecker.app.di

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über fest verdrahtete Dispatcher: KEINE Kotlin-Datei von :app, :data und :domain (`src/main`) nennt
 * `Dispatchers.IO`/`Dispatchers.Default` selbst — ausser [DispatcherModule], das sie liefert. Per Hilt erzeugte Klassen
 * nehmen den Dispatcher im Konstruktor (`@param:IoDispatcher`, `@param:DefaultDispatcher`), Klassen aus :domain über
 * ihre `@Provides`-Funktion (`RefreshModule`), Erweiterungsdateien aus ihrer Klasse bzw. ihrem ViewModel, reine
 * Funktionen als Parameter vom Aufrufer.
 *
 * Was noch nicht umgestellt ist, steht mit seiner Anzahl in [REMAINING], nach Grund geordnet — die ganze Wahrheit, nicht
 * nur die Hilt-Klassen: [REMAINING_PLACES] Stellen in [REMAINING_FILES] Dateien. Die Zahlen dürfen nur sinken — wer
 * eine Datei umstellt, senkt sie hier mit, bei null kommt der Eintrag weg. Kommentare und Import-Zeilen zählen nicht.
 */
class DispatcherInjectionGuardTest {

    @Test
    fun keineDateiVerdrahtetEinenDispatcherFest() {
        val files = mainFiles()
        assertTrue("nur ${files.size} Kotlin-Dateien gefunden", files.size > 900)
        val counts = files.filterKeys { it != PROVIDER }
            .mapValues { (_, file) -> literals(file.readText(Charsets.UTF_8)) }.filterValues { it > 0 }
        val grown = counts.filter { (name, count) -> count > (REMAINING[name] ?: 0) }.toSortedMap()
            .map { (name, count) -> "$name: $count, erlaubt ${REMAINING[name] ?: 0}" }
        assertTrue(
            "Dispatchers.IO/Default fest verdrahtet — im Konstruktor nehmen (@param:IoDispatcher/@param:DefaultDispatcher), " +
                "in :domain über RefreshModule, in Erweiterungsdateien aus der Klasse:\n" + grown.joinToString("\n"),
            grown.isEmpty(),
        )
        val stale = REMAINING.filter { (name, allowed) -> (counts[name] ?: 0) < allowed }.toSortedMap()
            .map { (name, allowed) -> "$name: ${counts[name] ?: 0} statt $allowed" }
        assertTrue("Zahl in REMAINING senken (bei 0 Eintrag entfernen):\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    /** Die Summe steht ausgeschrieben da (Doku nennt sie): Wer einen Eintrag ändert, sieht die neue Zahl. */
    @Test
    fun restIstAusgezaehlt() {
        assertEquals(REMAINING_FILES, REMAINING.size)
        assertEquals(REMAINING_PLACES, REMAINING.values.sum())
        assertTrue(REMAINING.values.all { it > 0 })
        // Das liefernde Modul ist die einzige Ausnahme und steht nicht in der Liste
        assertTrue(PROVIDER !in REMAINING)
        assertEquals(2, literals(mainFiles().getValue(PROVIDER).readText(Charsets.UTF_8)))
    }

    /** Gegenprobe: Der Zähler sieht Code, aber weder Kommentare noch die Import-Zeile. */
    @Test
    fun zaehlerSchlaegtAn() {
        assertEquals(2, literals("withContext(Dispatchers.IO) { }\nflow.flowOn(kotlinx.coroutines.Dispatchers.Default)"))
        assertEquals(0, literals("import kotlinx.coroutines.Dispatchers\n// Dispatchers.IO\n/** [Dispatchers.Default] */\nval x = 1"))
        assertEquals(0, literals("withContext(Dispatchers.Main) { }\nwithContext(ioDispatcher) { }"))
    }

    /** Anzeigename (`app/…`, `data/…`, `domain/…`, relativ zu `src/main/java/com/cryptochecker/`) → Kotlin-Datei. */
    private fun mainFiles(): Map<String, File> {
        val root = SourceGuard.android(androidRoot(), "android/app/src/main")
        return MODULES.flatMap { module ->
            val main = SourceGuard.android(File(root, "$module/src/main").takeIf { it.isDirectory }, "$module/src/main")
            main.walkTopDown().filter { it.isFile && it.extension == "kt" }
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
        val MODULES = listOf("app", "data", "domain")

        /** Liefert die Dispatcher und nennt sie darum selbst (zweimal). */
        const val PROVIDER = "app/app/di/DispatcherModule.kt"
        val LITERAL = Regex("""\bDispatchers\.(?:IO|Default)\b""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""(^|[ \t])//[^\n]*""", RegexOption.MULTILINE)

        const val REMAINING_FILES = 35
        const val REMAINING_PLACES = 57

        /** Datei → höchstens so viele `Dispatchers.IO`/`Dispatchers.Default` im Code; darf nur sinken. */
        val REMAINING: Map<String, Int> = mapOf(
            // Von Android erzeugt oder ohne Hilt-Konstruktor (Application vor der Injektion, Receiver, `object`,
            // Hilfsklassen und -funktionen, die von Hand gebaut werden)
            "app/app/CryptoCheckerApp.kt" to 2,
            "app/app/util/ReceiverAsync.kt" to 1,
            "app/app/work/ExactCheckReceiver.kt" to 1,
            "app/app/widget/PulseBackgroundFetch.kt" to 1,
            "app/app/widget/PortfolioWidgetChartView.kt" to 1,
            "app/app/ui/features/portfolio/PortfolioExportRun.kt" to 2,
            "app/app/ui/features/portfolio/PortfolioCsvImportReader.kt" to 3,
            "app/app/ui/features/watchlist/WatchlistContentFlows.kt" to 1,
            "data/app/data/local/StorageRecoveryNotice.kt" to 2,
            "data/app/data/portfolio/PortfolioBackupMirror.kt" to 1,
            "data/app/data/remote/util/InFlightRequests.kt" to 1,
            // Begleitobjekt: «App zurücksetzen» löscht die Einträge der Uhr aus dem Bildschirm heraus, bevor der Prozess
            // endet (kein Hilt-Objekt im Composable greifbar)
            "app/app/wearable/PhoneWatchSync.kt" to 1,
            // Composables (LaunchedEffect/produceState): kein Konstruktor, in den Hilt etwas reichen könnte
            "app/app/ui/features/about/LicensesSheet.kt" to 1,
            "app/app/ui/features/alarms/AlarmPickerRows.kt" to 1,
            "app/app/ui/features/explorer/ExplorerExchangeBadge.kt" to 1,
            "app/app/ui/features/portfolio/PortfolioRestoreNoticeDialog.kt" to 1,
            "app/app/ui/features/settings/SettingsDataPages.kt" to 1,
            "app/app/ui/features/settings/SettingsScreen.kt" to 1,
            "app/app/ui/features/settings/StorageRecoveryNoticeDialog.kt" to 3,
            "app/app/ui/features/watchlist/WatchSheetChartCanvas.kt" to 1,
            // ViewModels mit den meisten Konstruktor-Abhängigkeiten in :app (code_metrics.py, Bündel mitgezählt: Explorer-
            // und PortfolioViewModel 15, SettingsViewModel 14, Alarms- und InfoViewModel 13) samt Erweiterungsdateien;
            // Explorer und Portfolio nehmen den Default-Dispatcher schon, IO bekommen sie erst nach dem Aufteilen
            "app/app/ui/features/alarms/AlarmsViewModel.kt" to 1,
            "app/app/ui/features/explorer/ExplorerBulkActions.kt" to 1,
            "app/app/ui/features/explorer/ExplorerSearchActions.kt" to 2,
            "app/app/ui/features/info/MarketDataLoaders.kt" to 1,
            "app/app/ui/features/info/MarketDataSync.kt" to 1,
            "app/app/ui/features/info/MarketPreviewActions.kt" to 1,
            "app/app/ui/features/settings/SettingsAlarmActions.kt" to 2,
            "app/app/ui/features/settings/SettingsBackupActions.kt" to 1,
            "app/app/ui/features/portfolio/PortfolioLoadActions.kt" to 1,
            // Datei an der Zeilengrenze (FileLengthTest; ein Konstruktor-Parameter mehr sprengt sie) samt Erweiterungsdateien:
            // CoinListSync, LogoImageStore und WatchedAddressSource haben genau 400 Zeilen
            "data/app/data/CoinListSync.kt" to 5,
            "data/app/data/CoinListSyncMap.kt" to 4,
            "data/app/data/LogoImageStore.kt" to 5,
            "data/app/data/portfolio/WatchedAddressSourceXpub.kt" to 2,
            // 397 Zeilen (FileLengthTest) und 16 Stellen in 6 Testdateien, die die Klasse von Hand bauen
            "data/app/data/remote/ExchangeRemoteDataSource.kt" to 1,
            // Gebaut von LogoImageStore (400 Zeilen, verdrahtet IO selbst, siehe oben): bekommt den Dispatcher erst, wenn
            // LogoImageStore einen hat; dazu 7 Stellen in den Paritätstests, die die Klasse von Hand bauen
            "domain/app/domain/logos/LogoLoadingHint.kt" to 2,
        )
    }
}
