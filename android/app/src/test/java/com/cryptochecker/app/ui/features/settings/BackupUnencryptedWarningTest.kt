package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.util.StringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Review UX9-02: Die Warnung vor dem Wiederherstellen einer unverschlüsselten Sicherung nennt die
 * geschützten Einstellungen mit ihren exakten Namen aus den Einstellungen (Gross/Klein egal),
 * damit man sie dort wiederfindet; ebenso die Hinweise nach dem Wiederherstellen (Sperre blieb an, eigener
 * Server). Gilt in allen 31 Sprachen.
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
        assertEquals(31, languages.count { it.value.containsKey("backup_restore_app_lock_kept") })
        for ((folder, strings) in languages) {
            val text = strings["backup_restore_app_lock_kept"] ?: continue
            val label = requireNotNull(strings["settings_portfolio_lock"]) { "$folder: settings_portfolio_lock fehlt" }
            assertTrue("$folder: backup_restore_app_lock_kept nennt «$label» nicht", text.norm().contains(label.norm()))
        }
    }

    @Test
    fun serverHinweiseNennenEinstellungsnamen() {
        // Hinweise nach dem Wiederherstellen nennen den eigenen Server so wie die Einstellung
        for (key in SERVER_NOTE_KEYS) {
            assertEquals(key, 31, languages.count { it.value.containsKey(key) })
        }
        for ((folder, strings) in languages) {
            val label = strings["settings_address_server_title"] ?: continue
            for (key in SERVER_NOTE_KEYS) {
                val text = strings[key] ?: continue
                assertTrue("$folder: $key nennt «$label» nicht", text.norm().contains(label.norm()))
            }
        }
    }

    private companion object {
        val LABEL_KEYS = listOf(
            "settings_portfolio_lock",
            "portfolio_hide_amounts",
            "settings_address_server_title",
            "settings_portfolio_system_backup",
        )
        val SERVER_NOTE_KEYS = listOf(
            "backup_restore_server_kept",
            "backup_restore_server_public",
            "backup_restore_server_changed",
        )
    }
}
