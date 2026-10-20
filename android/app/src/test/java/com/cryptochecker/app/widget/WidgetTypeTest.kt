package com.cryptochecker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Widgets nehmen die Schriftstufen der App ([WidgetType] = Label 12, Body 14, Title 16, Headline 22,
 * Display kompakt 28 sp): jedes `android:textSize` in `res/layout/widget_*.xml` ist eine dieser Stufen.
 * Ausgenommen sind TextViews mit `autoSizeTextType` (die Grösse ergibt sich aus Min/Max, `textSize` ist nur
 * die Startgrösse der Messung). Liest die Layouts von der Platte (wie `SpacingUsageTest`).
 */
class WidgetTypeTest {

    @Test
    fun widgetLayoutsUseAppTypeScale() {
        val dir = findUp(listOf("", "app/", "android/app/").map { it + "src/main/res/layout" })
        assertNotNull("res/layout nicht gefunden (Arbeitsverzeichnis?)", dir)
        val allowed = WidgetType.ALL.map { it.toInt() }.toSet()
        val problems = dir!!.listFiles { f -> f.isFile && f.name.startsWith("widget_") && f.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                TEXT_VIEW.findAll(file.readText(Charsets.UTF_8))
                    .filterNot { it.value.contains("autoSizeTextType") }
                    .mapNotNull { SIZE.find(it.value)?.groupValues?.get(1)?.toInt() }
                    .filterNot { it in allowed }
                    .map { "${file.name}: ${it}sp" }
                    .toList()
            }
        assertTrue("Schriftgrössen ausserhalb der App-Stufen 12/14/16/22/28 sp:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** Stufen wie die App (Typography.kt): labelMedium 12, bodyMedium 14, titleMedium 16, titleLarge 22, headlineMedium 28. */
    @Test
    fun tokensMatchAppScale() {
        assertEquals(listOf(12f, 14f, 16f, 22f, 28f), WidgetType.ALL)
    }

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
        val TEXT_VIEW = Regex("""<TextView\b[^>]*>""")
        val SIZE = Regex("""android:textSize="(\d+)sp"""")
    }
}
