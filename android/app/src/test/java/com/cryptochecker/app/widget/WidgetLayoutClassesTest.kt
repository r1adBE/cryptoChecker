package com.cryptochecker.app.widget

import com.cryptochecker.app.util.SourceGuard
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget-Layouts dürfen nur Klassen enthalten, die `RemoteViews` ab Android 8 (minSdk 26) aufbläht — sonst bleibt das
 * Widget beim Zeichnen leer («Class not allowed to be inflated», z. B. `Space` oder `View`). Prüft alle
 * `layout/widget*.xml` ohne Gerät; der Gerätetest `WidgetRenderDeviceTest` bläht sie zusätzlich echt auf.
 */
class WidgetLayoutClassesTest {

    @Test
    fun `widget layouts use only classes RemoteViews can inflate`() {
        val dir = SourceGuard.android(findLayouts(), "resources/src/main/res/layout")
        val layouts = dir.listFiles { f -> f.name.startsWith("widget") && f.name.endsWith(".xml") }.orEmpty()
        assertTrue("keine Widget-Layouts gefunden", layouts.isNotEmpty())
        val bad = layouts.flatMap { file ->
            TAG.findAll(COMMENT.replace(file.readText(), "")).map {
                it.groupValues[1]
            }.filter { it !in ALLOWED }.map { "${file.name}: <$it>" }
        }
        assertTrue("Nicht erlaubt in RemoteViews: $bad", bad.isEmpty())
    }

    private fun findLayouts(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("resources/src/main/res/layout", "android/resources/src/main/res/layout")) {
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        val COMMENT = Regex("""<!--[\s\S]*?-->""")
        val TAG = Regex("""<([A-Za-z][\w.]*)[\s/>]""")

        /** `RemoteViews`-taugliche Klassen bis API 26 (ohne die erst ab 31) plus die Layout-Bausteine `include`/`merge`. */
        val ALLOWED = setOf(
            "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
            "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView", "ProgressBar", "TextClock", "TextView",
            "AdapterViewFlipper", "GridView", "ListView", "StackView", "ViewFlipper", "ViewStub",
            "include", "merge",
        )
    }
}
