package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.io.File

/** Meta-Test der Pfadwächter: ein fehlender Pfad lässt den Wächtertest scheitern bzw. überspringen. */
class SourceGuardTest {

    @Test
    fun fehlendeAndroidQuelleScheitert() {
        assertThrows(AssertionError::class.java) { SourceGuard.android(null as String?, "Gibt/Es/Nicht.kt") }
        assertEquals("da", SourceGuard.android("da", "x"))
    }

    @Test
    fun fehlendeIosQuelleIstSichtbar() {
        // Ohne Pflicht: übersprungen (nicht grün); mit Pflicht (CI): Fehler
        assertThrows(AssumptionViolatedException::class.java) { SourceGuard.ios(null as String?, "ios/Gibt.swift", requireIos = false) }
        assertThrows(AssertionError::class.java) { SourceGuard.ios(null as String?, "ios/Gibt.swift", requireIos = true) }
        assertEquals("da", SourceGuard.ios("da", "x", requireIos = true))
    }

    /**
     * SEC4-R1/TST4-R1: Jede Testdatei, die einen iOS-Pfad liest (Zeichenkette mit `ios/` oder `.swift`),
     * nutzt [SourceGuard] — kein stilles `if (exists())`/`return` mehr. Kommentare zählen nicht.
     */
    @Test
    fun jederIosLeserNutztSourceGuard() {
        val root = SourceGuard.android(
            listOf("src/test/java", "app/src/test/java", "android/app/src/test/java").map(::File).firstOrNull { it.isDirectory },
            "src/test/java",
        )
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { readsIosWithoutGuard(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertTrue("iOS-Pfad ohne SourceGuard:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Gegenprobe: Der Meta-Scan schlägt bei einem eingebauten Beispiel ohne SourceGuard an, nicht bei Kommentaren. */
    @Test
    fun metaScanSchlaegtAn() {
        assertTrue(readsIosWithoutGuard("val f = File(\"ios/App/X.swift\")\nif (f.exists()) check(f)"))
        assertTrue(readsIosWithoutGuard("val roots = listOf(\"App\").map { File(\"../ios/${'$'}it\") }"))
        assertTrue(!readsIosWithoutGuard("// wie ios/App/X.swift\nval x = 1"))
        assertTrue(!readsIosWithoutGuard("val t = SourceGuard.ios(File(\"ios/App/X.swift\").takeIf { it.isFile }, \"x\")"))
    }

    private fun readsIosWithoutGuard(text: String): Boolean {
        val code = text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "${'$'}1")
        return IOS_PATH.containsMatchIn(code) && !code.contains("SourceGuard.")
    }

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""(^|[ \t])//[^\n]*""", RegexOption.MULTILINE)
        /** Zeichenkette mit `ios/` (nicht Teil eines Worts) oder `.swift`. */
        val IOS_PATH = Regex(""""[^"\n]*(?:(?<![\w])ios/|\.swift\b)[^"\n]*"""")
    }
}
