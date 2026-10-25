package com.cryptochecker.app.testing

import android.content.res.Resources
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.settings.AppLanguages
import kotlin.math.abs

/**
 * Systemschrift (`font_scale`) und App-Sprache für einen Testlauf setzen und danach zurücksetzen — vor dem Start
 * der Activity aufrufen, damit sie in der neuen Konfiguration startet.
 */
object DeviceOverrides {

    private const val SETTLE_MS = 5_000L

    fun <T> with(fontScale: Float? = null, languageTag: String? = null, block: () -> T): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val oldScale = fontScale?.let { AppTestEnvironmentRule.shell("settings get system font_scale").trim() }
        val oldTag = languageTag?.let { AppLanguages.currentTag(context) }
        try {
            fontScale?.let { setFontScale(it) }
            languageTag?.let { tag -> instrumentation.runOnMainSync { AppLanguages.select(context, tag) } }
            instrumentation.waitForIdleSync()
            return block()
        } finally {
            oldTag?.let { tag -> instrumentation.runOnMainSync { AppLanguages.select(context, tag) } }
            oldScale?.let { old ->
                if (old.isEmpty() || old == "null") AppTestEnvironmentRule.shell("settings delete system font_scale")
                else AppTestEnvironmentRule.shell("settings put system font_scale $old")
            }
            instrumentation.waitForIdleSync()
        }
    }

    /** Setzt `font_scale` und wartet, bis das System die neue Grösse meldet (höchstens [SETTLE_MS]). */
    private fun setFontScale(scale: Float) {
        AppTestEnvironmentRule.shell("settings put system font_scale $scale")
        val until = SystemClock.uptimeMillis() + SETTLE_MS
        while (abs(Resources.getSystem().configuration.fontScale - scale) > 0.01f &&
            SystemClock.uptimeMillis() < until
        ) {
            SystemClock.sleep(100)
        }
    }
}
