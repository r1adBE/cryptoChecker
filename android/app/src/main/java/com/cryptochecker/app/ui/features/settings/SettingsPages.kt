package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.Spacing

/*
 * Runde 23f: Unterseiten hinter den Zeilen der Hauptseite. Inhalt und Schlüssel wie
 * zuvor auf der langen Hauptseite, nur neu verteilt. Markt-Meldungen und Sprachausgabe
 * stehen weiter in SettingsSubPages.kt (eigene Routen, auch aus der Merkliste erreichbar).
 * Die Seiten selbst liegen je Gruppe in Settings…Pages.kt (Allgemein, Darstellung, Alarme,
 * Portfolio, Daten, Über) — gleiche Aufteilung wie in iOS.
 */

/** Unterseiten der Einstellungen; der Name steht in der Route `settings/page/{page}`. */
enum class SettingsPage {
    CURRENCY, UPDATES, WATCHLIST,
    DISPLAY_MODE, THEME, PRICE_COLORS, CHANGE_BASIS,
    ALARMS,
    PORTFOLIO,
    BACKUP,
    ABOUT, DEVELOPER;

    companion object {
        fun fromName(name: String?): SettingsPage? = entries.firstOrNull { it.name == name }
    }
}

/** Zeigt eine Unterseite; ein unbekannter Name (alte Route) führt gleich zurück. */
@Composable
fun SettingsPageScreen(page: SettingsPage?, onBack: () -> Unit) {
    when (page) {
        SettingsPage.CURRENCY -> CurrencyPage(onBack)
        SettingsPage.UPDATES -> UpdatesPage(onBack)
        SettingsPage.WATCHLIST -> WatchlistPage(onBack)
        SettingsPage.DISPLAY_MODE -> DisplayModePage(onBack)
        SettingsPage.THEME -> ThemePage(onBack)
        SettingsPage.PRICE_COLORS -> PriceColorsPage(onBack)
        SettingsPage.CHANGE_BASIS -> ChangeBasisPage(onBack)
        SettingsPage.ALARMS -> AlarmsPage(onBack)
        SettingsPage.PORTFOLIO -> PortfolioPage(onBack)
        SettingsPage.BACKUP -> BackupPage(onBack)
        SettingsPage.ABOUT -> AboutPage(onBack)
        SettingsPage.DEVELOPER -> DeveloperPage(onBack)
        null -> LaunchedEffect(Unit) { onBack() }
    }
}

/** Auswahlzeile mit Radioknopf (ganze Zeile tippbar, für den Screenreader ein Radioknopf). */
@Composable
internal fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.sm)
    ) {
        RadioButton(selected = selected, onClick = null)
        if (leading != null) {
            Spacer(Modifier.width(12.dp))
            leading()
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
