package com.cryptochecker.app.ui.components

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über den Haptik-Katalog (seit Build 100): Android spielt Haptik nur über `ui/components/Haptics.kt`
 * (`rememberHaptics().play(HapticEvent…)`), iOS nur über `App/Components/Haptics.swift` (`Haptics.play(.…)` bzw.
 * `.haptic(_:trigger:)`) — so vibrieren beide in denselben Momenten. Direkte Aufrufe der System-APIs fallen hier auf.
 * Hintergrund, Mitteilungen und Widgets bekommen gar keine Haptik. Liest die Quellen von der Platte (wie
 * [com.cryptochecker.app.ui.theme.TypographyUsageTest]); das iOS-Verzeichnis ist optional (reiner Android-Checkout).
 */
class HapticsUsageTest {

    @Test
    fun androidHapticsOnlyThroughTheCatalog() {
        val root = androidSources()
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        val hits = findUses(root!!, "kt", ANDROID_DIRECT, allowed = setOf("ui/components/Haptics.kt"))
        assertTrue("Haptik direkt statt über rememberHaptics()/Haptics.play: $hits", hits.isEmpty())
    }

    @Test
    fun noHapticsInBackgroundNotificationsOrWidgets() {
        val root = SourceGuard.android(androidSources(), "android/app/src/main/java")
        val hits = BACKGROUND_DIRS.flatMap { dir ->
            findUses(File(root, dir), "kt", ANDROID_CATALOG, allowed = emptySet()).map { "$dir/$it" }
        }
        assertTrue("Haptik im Hintergrund, in Mitteilungen oder Widgets: $hits", hits.isEmpty())
    }

    /**
     * Daten-, Domänen- und Hilfscode spielt nie selbst Haptik: er darf ein Ereignis höchstens zuordnen
     * (Typ `HapticEvent` ist erlaubt), abspielen muss es der Bildschirm, der die Handlung ausgelöst hat.
     */
    @Test
    fun noHapticPlaybackInDataDomainOrUtil() {
        val root = SourceGuard.android(androidSources(), "android/app/src/main/java")
        val hits = NON_UI_DIRS.flatMap { dir ->
            val pkg = File(root, dir)
            if (!pkg.isDirectory) emptyList() else findUses(pkg, "kt", ANDROID_PLAYBACK, allowed = emptySet()).map { "$dir/$it" }
        }
        assertTrue("Haptik wird in Daten-/Domänen-/Hilfscode abgespielt: $hits", hits.isEmpty())
    }

    @Test
    fun iosHapticsOnlyThroughTheCatalog() {
        val ios = SourceGuard.ios(iosRoot(), "ios/App")
        val hits = listOf("App", "Shared", "Widgets").flatMap { top ->
            val dir = File(ios, top)
            if (!dir.isDirectory) emptyList()
            else findUses(dir, "swift", IOS_DIRECT, allowed = setOf("Components/Haptics.swift")).map { "$top/$it" }
        }
        assertTrue("Haptik direkt statt über Haptics.play/.haptic(_:trigger:): $hits", hits.isEmpty())
    }

    @Test
    fun noHapticsInIosWidgetsOrShared() {
        val ios = SourceGuard.ios(iosRoot(), "ios/App")
        val hits = listOf("Shared", "Widgets").flatMap { top ->
            val dir = File(ios, top)
            if (!dir.isDirectory) emptyList() else findUses(dir, "swift", IOS_CATALOG, allowed = emptySet()).map { "$top/$it" }
        }
        assertTrue("Haptik in Widgets oder geteiltem Code: $hits", hits.isEmpty())
    }

    /** iOS-Dienste (App/Services) ordnen Ereignisse höchstens zu (z. B. `AddWatchedResult.haptic`), spielen sie aber nie ab. */
    @Test
    fun noHapticPlaybackInIosServices() {
        val ios = SourceGuard.ios(iosRoot(), "ios/App")
        val dir = File(ios, "App/Services")
        assertTrue("ios/App/Services nicht gefunden (umbenannt?)", dir.isDirectory)
        val hits = findUses(dir, "swift", IOS_PLAYBACK, allowed = emptySet()).map { "App/Services/$it" }
        assertTrue("Haptik wird in App/Services abgespielt: $hits", hits.isEmpty())
    }

    /** «Datei:Zeile» aller Treffer von [pattern] in Codezeilen (Kommentare zählen nicht) unter [dir], ohne [allowed]. */
    private fun findUses(dir: File, extension: String, pattern: Regex, allowed: Set<String>): List<String> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == extension }
            .filterNot { file -> file.relativeTo(dir).invariantSeparatorsPath in allowed }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                    val code = line.trimStart()
                    val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    if (!comment && pattern.containsMatchIn(line)) "${file.relativeTo(dir).invariantSeparatorsPath}:${index + 1}" else null
                }
            }
            .toList()

    private fun androidSources(): File? = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })

    private fun iosRoot(): File? = findUp(listOf("ios", "../ios", "../../ios"))?.takeIf { File(it, "App").isDirectory }

    /** Sucht [candidates] vom Arbeitsverzeichnis aus aufwärts. */
    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        val ANDROID_DIRECT = Regex("""performHapticFeedback|LocalHapticFeedback|HapticFeedbackConstants|\bVibrator\b|VibratorManager""")
        val ANDROID_CATALOG = Regex("""rememberHaptics\(|Haptics\.play\(|HapticEvent\.""")
        val IOS_DIRECT = Regex("""UIImpactFeedbackGenerator|UINotificationFeedbackGenerator|UISelectionFeedbackGenerator|\.sensoryFeedback\(""")
        val IOS_CATALOG = Regex("""Haptics\.play\(|\.haptic\(""")

        /** Abspielen (nicht der Typ): in Code ohne Bildschirm verboten, Zuordnungen auf `HapticEvent` bleiben erlaubt. */
        val ANDROID_PLAYBACK = Regex("""rememberHaptics\(|Haptics\.play\(|\bHapticPlayer\b|performHapticFeedback""")
        val IOS_PLAYBACK = Regex("""Haptics\.play\(|\.haptic\(|FeedbackGenerator""")

        /** Pakete ohne Bildschirm (relativ zu `com/cryptochecker/app`). */
        val BACKGROUND_DIRS = listOf("notification", "widget", "work", "tts")

        /** Daten-, Domänen- und Hilfspakete (relativ zu `com/cryptochecker/app`): kein Abspielen, Zuordnen erlaubt. */
        val NON_UI_DIRS = listOf("data", "domain", "lock", "util", "settings", "di")
    }
}
