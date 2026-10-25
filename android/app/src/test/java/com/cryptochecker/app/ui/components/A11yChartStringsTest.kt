package com.cryptochecker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Texte der Diagramme für Screenreader (`strings_a11y_charts.xml`): in allen 31 Sprachen alle Schlüssel; die Ansage
 * beider Linien des Vergleichs (`a11y_chart_two_series`) mit den vier Platzhaltern in der Reihenfolge
 * Währung, Veränderung, USDT, Veränderung.
 */
class A11yChartStringsTest {

    private val keys = listOf(
        "a11y_chart_next_point", "a11y_chart_previous_point", "a11y_chart_two_series",
        "a11y_chart_axis_time", "a11y_chart_axis_price", "a11y_chart_axis_value", "a11y_chart_axis_change",
    )

    @Test
    fun allLanguagesHaveAllKeys() {
        val files = resDir().listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings_a11y_charts.xml") }
            .filter { it.isFile }
        assertEquals("strings_a11y_charts.xml in allen 31 Sprachen", 31, files.size)
        for (file in files) {
            val text = file.readText(Charsets.UTF_8)
            for (key in keys) {
                val value = Regex("""<string name="$key"[^>]*>([^<]+)</string>""").find(text)?.groupValues?.get(1)
                assertNotNull("$key fehlt in ${file.parentFile.name}", value)
            }
            val twoSeries = Regex("""<string name="a11y_chart_two_series">([^<]+)</string>""")
                .find(text)!!.groupValues[1]
            val order = Regex("%(\\d)\\\$s").findAll(twoSeries).map { it.groupValues[1] }.toList()
            assertEquals("a11y_chart_two_series in ${file.parentFile.name}", listOf("1", "2", "3", "4"), order)
        }
    }

    private fun resDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("src/main/res", "app/src/main/res", "android/app/src/main/res")) {
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        error("res-Verzeichnis nicht gefunden (Arbeitsverzeichnis?)")
    }
}
