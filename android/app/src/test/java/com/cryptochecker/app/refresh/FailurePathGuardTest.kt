package com.cryptochecker.app.refresh

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wächter für Fehlerwege, deren Regel im Ablauf steckt (Netz, Speicher, Systemdienste ohne Gerät nicht nachzustellen):
 * liest die Quellen. Gegenstück iOS: `FailurePathGuardTests.swift`.
 */
class FailurePathGuardTest {

    /** Rangliste der Logos: Netzfehler auf einer Folgeseite = bald erneut, nicht Stunden «unvollständig». */
    @Test
    fun rankingNetworkCutRetriesSoon() {
        // Android als Verhalten: LogoRankingNetworkCutTest (Gerät)
        val ios = ios("Shared/Storage/CoinLogoStore.swift")
        assertTrue(ios.contains("if networkError { mapFetchFailed(networkError: true, previousAttempt: attempt) } else {"))
    }

    /** Logos: Speicher voll = eigene Art (keine Tagessperre, kein Netz, Durchgang lädt nichts mehr); Start-Logos parallel. */
    @Test
    fun logoStorageFullAndStarterLogos() {
        // Android als Verhalten: LogoImageStoreFailureTest (Gerät: Speicher voll, Start-Logos gleichzeitig)
        val repo = android("data/src/main/java/com/cryptochecker/app/data/logos/CoinLogoRepository.kt")
        assertTrue(repo.contains("images.ensureStarterLogos(symbols, listSync.starterMap())"))
        val ios = ios("Shared/Storage/CoinLogoStore+Download.swift")
        assertTrue(ios.contains("if await streaks.unsaved { return .unsaved }"))
        assertTrue(ios.contains("transient: transientLeft.count)"))
        // Hinweis «Coins ohne Logo» unterscheidet «noch nicht geladen» von «kein Logo verfügbar»
        val line = android("app/src/main/java/com/cryptochecker/app/ui/features/watchlist/WatchlistMissingLogos.kt")
        assertTrue(line.contains("R.string.watchlist_missing_logos_explain_pending"))
        assertTrue(line.contains("value = missing?.let { source.notYetLoaded(it) }.orEmpty()"))
        val sheet = ios("App/Features/Watchlist/WatchlistMissingLogos.swift")
        assertTrue(sheet.contains("if await CoinLogoStore.shared.notYetLoaded(entry.key) { notYet.insert(entry.key) }"))
    }

    /** Hintergrund: pausiert/offline ehrlich vermerkt, Budget-Lücken keine volle Prüfung, Netzbudget behält Kurse (iOS). */
    @Test
    fun backgroundRunsAreLoggedHonestly() {
        val check = android("app/src/main/java/com/cryptochecker/app/work/BackgroundCheck.kt")
        assertTrue(check.contains("CheckRunLog.result(summary.checked, summary.failed, paused = summary.paused)"))
        val exact = android("app/src/main/java/com/cryptochecker/app/work/ExactCheckReceiver.kt")
        assertTrue(exact.contains("backgroundCheck.log(CheckTrigger.EXACT, CheckResult.OFFLINE, startedAt)"))
        val refresher = android("domain/src/main/java/com/cryptochecker/app/domain/refresh/PriceRefresher.kt")
        assertTrue(refresher.contains("processed.notTraded, cutByBudget)"))
        val fetcher = android("domain/src/main/java/com/cryptochecker/app/domain/refresh/PriceFetcher.kt")
        assertTrue(fetcher.contains("FetchOrder.alarmsFirst(group, alarmed, rotation) { it.id }"))
        val steps = ios("Shared/Services/PriceRefresher+Steps.swift")
        assertTrue(steps.contains("for (id, value) in partial.all where fetched[id] == nil { fetched[id] = value }"))
        assertTrue(steps.contains("if result?.budgetCut == true { outcome.cutByBudget += 1 }"))
        val bg = ios("App/Services/BackgroundRefresh.swift")
        assertTrue(bg.contains("fullCheck = !expired && outcome.isFullCheck"))
        val outcome = steps
        assertTrue(outcome.contains("skippedOffline ? .skipped : CheckRunLog.result(checked: checked, failed: failed, paused: pausedIds.count,"))
        assertTrue(outcome.contains("!alarmsUnchecked && BackgroundSchedule.isFullCheck(attempted: checked, failed: failed, notTraded: notTraded,"))
        val app = ios("App/Services/AppData+Refresh.swift")
        assertTrue(app.contains("let result: CheckResult = outcome?.checkResult ?? .skipped"))
    }

    /** Android 8–12: Sprachwechsel stellt den App-Kontext sofort um (Widgets, Mitteilungen); Alarm-Kanäle umbenannt (B3-07). */
    @Test
    fun languageChangeReachesWidgetsAndChannels() {
        val languages = android("app/src/main/java/com/cryptochecker/app/setup/AppLanguages.kt")
        assertTrue(languages.contains("        applyToProcess(context.applicationContext, tag)\n"))
        assertTrue(languages.contains("resources.updateConfiguration(config, resources.displayMetrics)"))
        assertTrue(languages.contains("com.cryptochecker.app.notification.NotificationChannels.createAll(context.applicationContext)"))
        val channels = android("app/src/main/java/com/cryptochecker/app/notification/NotificationChannels.kt")
        assertTrue(channels.contains("val mode = AlarmSignal.entries.firstOrNull { AlarmSignal.isChannelOf(it, existing.id) } ?: continue"))
    }

    /** Einzel-Widget merkt das Paar, nicht nur die Id: anderes Paar unter derselben Id → «Paar wählen» (B3-08). */
    @Test
    fun singleWidgetChecksThePair() {
        val renderer = android("widget/src/main/java/com/cryptochecker/app/widget/SingleWidgetRenderer.kt")
        assertTrue(renderer.contains("?.takeIf { widgetPrefs.showsSamePair(appWidgetId, widgetPairText(it)) }"))
        val provider = ios("Widgets/SingleWidgetProvider.swift")
        assertTrue(provider.contains("return watches.first { ${'$'}0.id == id }.flatMap { chosen.showsSamePair(${'$'}0) ? ${'$'}0 : nil }"))
        val intents = ios("Widgets/WidgetIntents.swift")
        assertTrue(intents.contains("id = \"\\(watch.id)|\" + WatchIdentity.text(watch.identity)"))
    }

    private fun android(rel: String): String =
        SourceGuard.android(findUp(listOf(rel, "android/$rel")), rel).readText(Charsets.UTF_8)

    private fun ios(rel: String): String =
        SourceGuard.ios(findUp(listOf("ios/$rel")), "ios/$rel").readText(Charsets.UTF_8)

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
