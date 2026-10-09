package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.cryptochecker.app.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
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
    LANGUAGE, CURRENCY, UPDATES, WATCHLIST,
    DISPLAY_MODE, THEME, PRICE_COLORS, CHANGE_BASIS, COIN_LOGOS,
    ALARMS,
    PORTFOLIO,
    BACKUP,
    ABOUT, LICENSES, WIDGETS, DEVELOPER;

    companion object {
        fun fromName(name: String?): SettingsPage? = entries.firstOrNull { it.name == name }
    }
}

/** Zeigt eine Unterseite; ein unbekannter Name (alte Route) führt gleich zurück. */
@Composable
fun SettingsPageScreen(page: SettingsPage?, onBack: () -> Unit) {
    when (page) {
        SettingsPage.LANGUAGE -> LanguagePage(onBack)
        SettingsPage.CURRENCY -> CurrencyPage(onBack)
        SettingsPage.UPDATES -> UpdatesPage(onBack)
        SettingsPage.WATCHLIST -> WatchlistPage(onBack)
        SettingsPage.DISPLAY_MODE -> DisplayModePage(onBack)
        SettingsPage.THEME -> ThemePage(onBack)
        SettingsPage.PRICE_COLORS -> PriceColorsPage(onBack)
        SettingsPage.CHANGE_BASIS -> ChangeBasisPage(onBack)
        SettingsPage.COIN_LOGOS -> CoinLogosPage(onBack)
        SettingsPage.ALARMS -> AlarmsPage(onBack)
        SettingsPage.PORTFOLIO -> PortfolioPage(onBack)
        SettingsPage.BACKUP -> BackupPage(onBack)
        SettingsPage.ABOUT -> AboutPage(onBack)
        SettingsPage.LICENSES -> LicensesPage(onBack)
        SettingsPage.WIDGETS -> WidgetsPage(onBack)
        SettingsPage.DEVELOPER -> DeveloperPage(onBack)
        null -> LaunchedEffect(Unit) { onBack() }
    }
}

/**
 * Auswahlzeile (Einfach-Auswahl), auf allen Auswahlseiten gleich: ganze Zeile tippbar, die
 * gewählte dezent in der Akzentfarbe hinterlegt (wie der gewählte Gruppen-Chip), Text kräftiger,
 * Häkchen rechts. Für den Screenreader ein Radioknopf («ausgewählt»). [leading]: z. B. Farbpunkt,
 * Kursfarben-Pfeile oder Währungskürzel. Wie `ChoiceRow` (iOS).
 */
@Composable
internal fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .bleed(8.dp)
            .padding(vertical = 1.dp)
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_ALPHA) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = Spacing.sm)
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).size(20.dp)
            )
        }
    }
}

/** Hintergrund der gewählten Zeile: Akzentfarbe mit 16 % Deckkraft. */
private const val SELECTED_ALPHA = 0.16f

/** Ragt links und rechts um [amount] über den Innenabstand der Karte hinaus (Hinterlegung bis nahe an den Rand). */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx() * 2
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = (constraints.minWidth + extra).coerceAtMost(maxWidth), maxWidth = maxWidth))
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-amount.roundToPx(), 0) }
}

/** Suchfeld über langen Auswahllisten (Sprache, Währung). */
@Composable
internal fun ChoiceSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    )
}
