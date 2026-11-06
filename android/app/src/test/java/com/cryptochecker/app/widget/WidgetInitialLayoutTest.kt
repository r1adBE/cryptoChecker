package com.cryptochecker.app.widget

import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Erster Auftritt der Widgets (android:initialLayout), bevor die App zeichnet — etwa nach dem Neustart bis zum ersten
 * Entsperren (Owner-Meldung Build 202: weisse leere Fläche, auch im Dunkelmodus): Hintergrund im Thema «System»
 * (hell/dunkel über values-night, Deckkraft und Ecken wie später), graues Skelett mit «Kurse werden geladen» statt
 * einer leeren Fläche; jedes Zeichnen blendet das Skelett aus und setzt die einzufärbende Fläche zurück.
 */
class WidgetInitialLayoutTest {

    private val initialLayouts = mapOf(
        "price_widget_info.xml" to "widget_list.xml",
        "single_widget_info.xml" to "widget_single.xml",
        "portfolio_widget_info.xml" to "widget_portfolio.xml",
        "pulse_widget_info.xml" to "widget_pulse.xml",
    )

    @Test
    fun `every initial layout shows the system-themed background and a skeleton`() {
        for ((info, layout) in initialLayouts) {
            assertTrue(info, res("xml/$info").contains("android:initialLayout=\"@layout/${layout.removeSuffix(".xml")}\""))
            val text = res("layout/$layout")
            assertTrue(layout, text.contains("android:src=\"@drawable/widget_background_initial\""))
            assertFalse(layout, text.contains("android:src=\"@drawable/widget_background\""))
            val skeleton = Regex("""<(ImageView|LinearLayout)\s+[^>]*android:id="@\+id/widget_skeleton"[^>]*>""")
                .find(text)?.value
            assertTrue("$layout: Skelett fehlt", skeleton != null)
            assertTrue(layout, skeleton!!.contains("android:contentDescription=\"@string/widget_loading_prices\""))
            // Statisch: keine Animation, keine Zahlen
            assertFalse(layout, skeleton.contains("ProgressBar") || text.contains("<ProgressBar"))
        }
        // Liste: 3–4 graue Platzhalter-Zeilen; die leere Liste ist bis zum ersten Zeichnen ausgeblendet
        val list = res("layout/widget_list.xml")
        assertTrue(Regex("""<include layout="@layout/widget_skeleton_row" />""").findAll(list).count() in 3..4)
        assertTrue(Regex("""<ListView\s+android:id="@\+id/widget_list"\s+android:visibility="gone"""").containsMatchIn(list))
    }

    @Test
    fun `previews stay unchanged`() {
        for (preview in listOf("widget_preview_list.xml", "widget_preview_single.xml", "widget_preview_portfolio.xml", "widget_preview_pulse.xml")) {
            val text = res("layout/$preview")
            assertFalse(preview, text.contains("widget_skeleton") || text.contains("widget_initial"))
        }
    }

    /** Jedes Zeichnen geht durch [WidgetToolkit.background]: dort verschwindet das Skelett, die Fläche wird zurückgesetzt. */
    @Test
    fun `every renderer hides the skeleton through the shared background`() {
        val toolkit = src("WidgetToolkit.kt")
        val body = toolkit.substringAfter("fun background(").substringBefore("\n    }")
        assertTrue(body.contains("views.setViewVisibility(R.id.widget_skeleton, View.GONE)"))
        assertTrue(body.contains("views.setImageViewResource(layerId, R.drawable.widget_background)"))
        // Jedes volle Zeichnen der vier Start-Layouts ruft background (Portfolio über portfolioFrame)
        val calls = mapOf(
            "ListWidgetRenderer.kt" to "toolkit.background(views, R.id.widget_bg",
            "SingleWidgetRenderer.kt" to "toolkit.background(views, R.id.single_bg",
            "PulseWidgetRenderer.kt" to "toolkit.background(views, R.id.pulse_bg",
            "PortfolioWidgetRenderer.kt" to "toolkit.background(views, R.id.portfolio_bg",
        )
        for ((file, call) in calls) assertTrue(file, src(file).contains(call))
        val frameFiles = listOf("PortfolioWidgetRenderer.kt", "PortfolioWidgetChartView.kt", "PortfolioWidgetList.kt", "PortfolioWidgetOverview.kt")
        for (file in frameFiles) {
            val text = src(file)
            // Ganze Layouts heissen «views» (Zeilen und Kacheln «row»/«item» sind keine Start-Layouts)
            val created = Regex("""val views = RemoteViews\(""").findAll(text).count()
            val framed = Regex("""portfolioFrame\(views, style\)""").findAll(text).count()
            assertTrue(file, created >= 1)
            assertEquals(file, created, framed)
        }
        // Liste: jedes volle Zeichnen setzt Liste bzw. «Noch keine Paare» sichtbar (die Liste startet ausgeblendet)
        val list = src("ListWidgetRenderer.kt")
        assertTrue(list.contains("views.setViewVisibility(R.id.widget_list, if (empty) View.GONE else View.VISIBLE)"))
        assertTrue(list.contains("views.setViewVisibility(R.id.widget_empty, if (empty) View.VISIBLE else View.GONE)"))
        // Gespeicherter Stand nicht lesbar: nicht zeichnen — der Launcher behält Skelett statt Weiss
        assertTrue(list.contains("if (DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME)) continue"))
    }

    /** Farben des ersten Auftritts = Thema «System» der Widgets (WidgetColors, 90 %), Text lesbar auf beiden. */
    @Test
    fun `initial colours match the system widget theme and stay readable`() {
        val light = colors("values/colors_widget_initial.xml")
        val dark = colors("values-night/colors_widget_initial.xml")
        assertEquals(WidgetColors.DEFAULT_OPACITY * 255 / 100, 0xE5)
        assertEquals(0xE5F1EEE9.toInt(), light.getValue("widget_initial_background"))
        assertEquals(0xE51F1F1F.toInt(), dark.getValue("widget_initial_background"))
        val source = src("WidgetColors.kt")
        assertTrue(source.contains("baseColor = 0xFFF1EEE9.toInt()") && source.contains("baseColor = 0xFF1F1F1F.toInt()"))
        for ((isDark, palette) in listOf(false to light, true to dark)) {
            val widget = WidgetColors.of(AccentColor.ORANGE, isDark)
            assertEquals(widget.textColor, palette.getValue("widget_initial_text"))
            val base = palette.getValue("widget_initial_background") or 0xFF000000.toInt()
            assertTrue(WidgetContrast.ratio(palette.getValue("widget_initial_text"), base) >= 4.5)
            // Skelett sichtbar, aber ruhiger als jeder Text
            val bar = WidgetContrast.ratio(palette.getValue("widget_initial_skeleton"), base)
            assertTrue("dark=$isDark: $bar", bar >= 1.3 && bar < 3.0)
        }
    }

    private fun colors(rel: String): Map<String, Int> =
        Regex("""<color name="(\w+)">#([0-9A-Fa-f]{8})</color>""").findAll(res(rel))
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16).toInt() }

    private fun res(rel: String): String = read("src/main/res/$rel")

    private fun src(name: String): String = read("src/main/java/com/cryptochecker/app/widget/$name")

    private fun read(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, prefix + rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        return SourceGuard.android(null, rel)
    }
}
