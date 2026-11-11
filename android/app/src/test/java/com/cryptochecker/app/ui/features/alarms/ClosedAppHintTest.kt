package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hinweis unter dem Alarm-Formular: ein kurzer Satz, «Zuletzt geprüft» und genau ein Verweis «Mehr dazu» zur Seite
 * «Damit Alarme ankommen»; dort bleibt «Kurs-Aktualisierung ändern» immer erreichbar (der Akku steht in ihrer Zeile).
 * Quelltext-Wächter für beide Plattformen.
 */
class ClosedAppHintTest {

    @Test
    fun `android hint is one sentence, last checked and one link`() {
        val source = android("AlarmEditorDialogs.kt")
        val hint = source.substringAfter("internal fun ClosedAppHint(").substringBefore("internal fun AlarmLastCheckedLine(")
        assertTrue(hint.contains("stringResource(R.string.alarm_closed_app_short)"))
        assertTrue(hint.contains("if (showLastChecked) AlarmLastCheckedLine("))
        assertEquals(1, Regex("""\bTextButton\(""").findAll(hint).count())
        assertTrue(hint.contains("LocalOpenAlarmDelivery.current?.let { openDelivery ->"))
        assertTrue(hint.contains("stringResource(R.string.action_learn_more)"))
        assertFalse(hint.contains("alarmTimingHintText()"))
        assertFalse(hint.contains("R.string.alarm_change_updates"))
    }

    @Test
    fun `android delivery page keeps the full sentence and the price update link`() {
        val page = android("AlarmDeliveryScreen.kt")
        assertTrue(page.contains("alarmTimingHintText(),"))
        assertTrue(page.contains("TextButton(onClick = onOpenUpdateSettings) { Text(stringResource(R.string.alarm_change_updates)) }"))
        assertTrue(page.contains("AlarmReadiness.Action.BATTERY_SETTINGS -> BatteryOptimization.openSettings(context)"))
    }

    @Test
    fun `ios hint and delivery page match`() {
        val source = ios("App/Features/Alarms/AlarmClosedAppHint.swift")
        val hint = source.substringAfter("struct ClosedAppHintRow: View {").substringBefore("extension View {")
        assertTrue(hint.contains("Text(L(\"alarm_closed_app_short\"))"))
        assertTrue(hint.contains("AlarmLastCheckedLine()"))
        assertEquals(1, Regex("""\bButton\(L\("action_""").findAll(hint).count())
        assertTrue(hint.contains("Button(L(\"action_learn_more\")) { showDelivery = true }"))
        assertFalse(hint.contains("alarm_change_updates"))
        val page = ios("App/Features/Alarms/AlarmDeliveryScreen.swift")
        assertTrue(page.contains("Text(AlarmTimingHint.text)"))
        assertTrue(page.contains("NavigationLink { UpdatesSettingsPage() } label: { linkLabel(L(\"alarm_change_updates\")) }\n                    .buttonStyle(.plain)\n                    .padding(.horizontal, 4)"))
    }

    private fun android(name: String): String {
        val rel = "src/main/java/com/cryptochecker/app/ui/features/alarms/$name"
        return SourceGuard.android(findUp(listOf("", "app/", "android/app/").map { it + rel }), rel).readText(Charsets.UTF_8)
    }

    private fun ios(rel: String): String =
        SourceGuard.ios(findUp(listOf("ios/$rel", "../ios/$rel", "../../ios/$rel")), "ios/$rel").readText(Charsets.UTF_8)

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
