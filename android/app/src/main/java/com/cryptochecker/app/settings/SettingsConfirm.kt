package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.portfolio.CostMethod

/**
 * Rückfragen in Einstellungen › Portfolio (reine Logik, wie iOS `SettingsConfirm`; `testdata/parity/settings_confirm.json`).
 */
object SettingsConfirm {
    /** Andere Einstandsmethode gewählt: erst «Rechnet alle Jahre neu» bestätigen (Ändern/Abbrechen); gleiche Wahl: nichts. */
    fun costMethod(current: CostMethod, chosen: CostMethod): Boolean = current != chosen

    /** «Portfolio in Systemsicherung» einschalten: erst bestätigen (Daten gehen in die Gerätesicherung); aus geht sofort. */
    fun systemBackup(enabling: Boolean): Boolean = enabling
}
