package com.cryptochecker.app.testing

import android.content.res.Resources
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.settings.AppLanguages
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.math.abs

/**
 * Gerätekonfiguration für eine Testmethode: Systemschrift ([fontScale], z. B. 2.0; 0 = unverändert) und App-Sprache
 * ([language], z. B. «ar»; leer = unverändert). Gelesen von [DeviceOverridesRule].
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class DeviceOverride(val fontScale: Float = 0f, val language: String = "")

/**
 * Setzt die [DeviceOverride] der Testmethode, bevor die Activity startet, und setzt danach zurück, wenn die
 * Activity schon beendet ist — darum innerhalb von Hilt und [AppTestEnvironmentRule], aber ausserhalb der
 * Compose-Regel (`RuleChain`). So startet die Activity gleich in der neuen Konfiguration und erlebt keinen
 * Konfigurationswechsel während des Tests.
 *  - Schrift: `settings put system font_scale` über UiAutomation; gewartet wird, bis das System die Grösse meldet.
 *  - Sprache: wie die Sprachwahl der App ([AppLanguages.select], `AppCompatDelegate.setApplicationLocales`, ab
 *    Android 13 über den `LocaleManager`); gewartet wird, bis AppCompat die Sprache meldet.
 * Ohne Annotation tut die Regel nichts.
 */
class DeviceOverridesRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        val config = description.getAnnotation(DeviceOverride::class.java) ?: return base
        return object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                val fontScale = config.fontScale.takeIf { it > 0f }
                val language = config.language.takeIf { it.isNotEmpty() }
                val oldScale = fontScale?.let { AppTestEnvironmentRule.shell("settings get system font_scale").trim() }
                val oldTag = language?.let { AppLanguages.currentTag(context) }
                try {
                    fontScale?.let { setFontScale(it) }
                    language?.let { setLanguage(it) }
                    instrumentation.waitForIdleSync()
                    base.evaluate()
                } finally {
                    oldTag?.let { setLanguage(it) }
                    oldScale?.let { restoreFontScale(it) }
                    instrumentation.waitForIdleSync()
                }
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 5_000L
        const val POLL_MS = 100L

        /** Setzt `font_scale` und wartet, bis das System die neue Grösse meldet (höchstens [SETTLE_MS]). */
        fun setFontScale(scale: Float) {
            AppTestEnvironmentRule.shell("settings put system font_scale $scale")
            waitFor { abs(Resources.getSystem().configuration.fontScale - scale) <= 0.01f }
        }

        /** Alter Wert aus `settings get` («null» = nie gesetzt → Eintrag löschen, Systemstandard). */
        fun restoreFontScale(old: String) {
            if (old.isEmpty() || old == "null") {
                AppTestEnvironmentRule.shell("settings delete system font_scale")
                waitFor { abs(Resources.getSystem().configuration.fontScale - 1f) <= 0.01f }
            } else {
                val scale = old.toFloatOrNull()
                if (scale != null) setFontScale(scale)
                else AppTestEnvironmentRule.shell("settings put system font_scale $old")
            }
        }

        /** App-Sprache wie die Sprachwahl der App; leerer Tag = Systemsprache. Wartet, bis AppCompat sie meldet. */
        fun setLanguage(tag: String) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { AppLanguages.select(instrumentation.targetContext, tag) }
            waitFor {
                var current = ""
                instrumentation.runOnMainSync {
                    current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                }
                if (tag.isEmpty()) current.isEmpty() else current.startsWith(tag)
            }
        }

        fun waitFor(condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + SETTLE_MS
            while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(POLL_MS)
        }
    }
}
