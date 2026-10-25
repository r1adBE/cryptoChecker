package com.cryptochecker.app.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale

/**
 * Barrierefreiheits-Prüfung über den Compose-Semantikbaum — die Prüfungen des Accessibility Test Framework,
 * die für Compose zählen, ohne zusätzliche Abhängigkeit:
 *  - [Check.LABEL]: Jedes antippbare Element hat einen Screenreader-Text (Beschreibung oder Text, auch von Kindern,
 *    die mit ihm verschmelzen) — wie ATF `SpeakableTextPresentCheck`.
 *  - [Check.TOUCH_TARGET]: Antippbare Elemente sind mindestens 48 × 48 dp gross (ganz sichtbare; angeschnittene am
 *    Rand einer Liste zählen nicht) — wie ATF `TouchTargetSizeCheck`.
 * Bekannte, begründete Ausnahmen kommen als [Suppression] dazu.
 */
object ComposeA11yAudit {

    enum class Check { LABEL, TOUCH_TARGET }

    /** Ausnahme: Prüfung [check] für Elemente, deren Text, Beschreibung oder testTag [match] enthält. */
    data class Suppression(val check: Check, val match: String, val reason: String)

    private const val MIN_TARGET_DP = 48f

    /**
     * Alle Befunde aller Fenster (Hauptfenster, Dialoge, Menüs) als lesbare Zeilen, `screen` vorangestellt;
     * leer = bestanden.
     */
    fun findings(rule: ComposeTestRule, screen: String, suppressions: List<Suppression> = emptyList()): List<String> {
        rule.waitForIdle()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = true)
        val result = mutableListOf<String>()
        for (root in roots) visit(root) { node -> result += check(node, density, suppressions).map { "$screen: $it" } }
        return result
    }

    private fun visit(node: SemanticsNode, action: (SemanticsNode) -> Unit) {
        action(node)
        node.children.forEach { visit(it, action) }
    }

    private fun check(node: SemanticsNode, density: Float, suppressions: List<Suppression>): List<String> {
        val config = node.config
        if (!config.contains(SemanticsActions.OnClick) || hidden(config)) return emptyList()
        val name = describe(config)
        fun suppressed(check: Check) = suppressions.any { it.check == check && name.contains(it.match) }
        val found = mutableListOf<String>()
        if (label(config).isBlank() && !suppressed(Check.LABEL)) {
            found += "${Check.LABEL}: antippbares Element ohne Screenreader-Text ($name, ${node.boundsInRoot})"
        }
        val bounds = node.boundsInRoot
        val fullyVisible = bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1
        val minSide = minOf(node.size.width, node.size.height) / density
        if (fullyVisible && minSide < MIN_TARGET_DP - 0.5f && !suppressed(Check.TOUCH_TARGET)) {
            found += "${Check.TOUCH_TARGET}: ${String.format(Locale.ROOT, "%.1f", minSide)} dp < 48 dp ($name, $bounds)"
        }
        return found
    }

    private fun hidden(config: SemanticsConfiguration): Boolean =
        config.contains(SemanticsProperties.HideFromAccessibility)

    private fun label(config: SemanticsConfiguration): String = listOfNotNull(
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        config.getOrNull(SemanticsProperties.EditableText)?.text,
        config.getOrNull(SemanticsProperties.StateDescription),
    ).joinToString(" ").trim()

    private fun describe(config: SemanticsConfiguration): String {
        val tag = config.getOrNull(SemanticsProperties.TestTag)
        val text = label(config).take(60)
        return listOfNotNull(tag?.let { "tag=$it" }, text.takeIf { it.isNotEmpty() }?.let { "«$it»" })
            .joinToString(" ").ifEmpty { "ohne Text" }
    }
}
