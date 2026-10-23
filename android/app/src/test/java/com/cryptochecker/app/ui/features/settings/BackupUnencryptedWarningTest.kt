package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.util.StringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Review UX9-02: Die Warnung vor dem Wiederherstellen einer unverschlüsselten Sicherung nennt die
 * geschützten Einstellungen mit ihren exakten Namen aus den Einstellungen (Gross/Klein egal),
 * damit man sie dort wiederfindet. Gilt in allen 31 Sprachen.
 */
class BackupUnencryptedWarningTest {

    private val languages = StringResources.byLanguage()

    private fun String.norm() = lowercase(Locale.ROOT)

    @Test
    fun alleSprachenVorhanden() {
        assertEquals(31, languages.count { it.value.containsKey("backup_restore_unencrypted_warning") })
    }

    @Test
    fun warnungNenntEinstellungsnamenWoertlich() {
        for ((folder, strings) in languages) {
            val warning = strings["backup_restore_unencrypted_warning"] ?: continue
            for (key in LABEL_KEYS) {
                val label = requireNotNull(strings[key]) { "$folder: $key fehlt" }
                assertTrue("$folder: Warnung nennt «$label» ($key) nicht wörtlich", warning.norm().contains(label.norm()))
            }
        }
    }

    @Test
    fun appSperreBleibtNenntEinstellungsnamen() {
        // Sprachen, deren Text bisher einen anderen Begriff als die Einstellung nutzte
        for (folder in listOf("values-fa", "values-pt", "values-iw", "values-th")) {
            val strings = languages.getValue(folder)
            val label = strings.getValue("settings_portfolio_lock")
            assertTrue("$folder: backup_restore_app_lock_kept", strings.getValue("backup_restore_app_lock_kept").norm().contains(label.norm()))
        }
    }

    private companion object {
        val LABEL_KEYS = listOf(
            "settings_portfolio_lock",
            "portfolio_hide_amounts",
            "settings_address_server_title",
            "settings_portfolio_system_backup",
        )
    }
}
