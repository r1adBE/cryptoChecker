package com.cryptochecker.app.testing

import com.cryptochecker.app.ui.TestDisplayOverride
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Schriftgrösse ([fontScale], z. B. 2.0; 0 = unverändert) und Sprache ([language], z. B. «ar»; leer = unverändert) für
 * eine Testmethode. Gelesen von [DeviceOverridesRule].
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class DeviceOverride(val fontScale: Float = 0f, val language: String = "")

/**
 * Setzt die [DeviceOverride] der Testmethode allein für die Oberfläche der App ([TestDisplayOverride]: Schrift, Sprache,
 * Leserichtung in Compose) — nicht systemweit, ohne `settings put` und ohne Wechsel der App-Sprache. Darum bleibt nichts
 * für die übrigen Tests zurück (der Versuch in Build 130 über die Gerätekonfiguration stellte nicht sauber zurück und
 * brachte danach `UiFlowsTest` zum Absturz). Muss vor der Compose-Regel stehen (`order` kleiner), damit die Activity
 * gleich mit der Einstellung zeichnet; danach immer zurückgesetzt. Ohne Annotation tut die Regel nichts.
 */
class DeviceOverridesRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        val config = description.getAnnotation(DeviceOverride::class.java) ?: return base
        return object : Statement() {
            override fun evaluate() {
                TestDisplayOverride.fontScale = config.fontScale
                TestDisplayOverride.languageTag = config.language
                try {
                    base.evaluate()
                } finally {
                    TestDisplayOverride.reset()
                }
            }
        }
    }
}
