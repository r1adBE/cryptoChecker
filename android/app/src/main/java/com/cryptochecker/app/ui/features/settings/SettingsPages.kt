package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.ui.theme.AppColors
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
    ALARMS, PRICE_PAIRS,
    PORTFOLIO,
    BACKUP,
    ABOUT, LICENSES, WIDGETS, DEVELOPER,
    // Build 53: «Erweitert» (Token & TradFi, Portfolio, Markt-Meldungen, Sprachausgabe, %-Änderung,
    // Coin-Logos, Entwickler) und «Token & TradFi» als eigene Seite
    ADVANCED, MARKETS;

    companion object {
        fun fromName(name: String?): SettingsPage? = entries.firstOrNull { it.name == name }
    }
}

/** Ort einer Unterseite: Gruppe der Hauptseite oder die Seite «Erweitert». */
enum class SettingsHome { GENERAL, APPEARANCE, MORE, ADVANCED }

/**
 * Wo die Zeile zu dieser Seite steht (Build 55: Kurs-Aktualisierung unter «Allgemein», Widgets unter
 * «Darstellung»); null = Unterseite einer Unterseite (Preis-Paare unter «Alarme», Lizenzen unter «Über
 * die App»). Die Suche zeigt daraus den Pfad ihrer Treffer, damit er zur Anordnung passt. Wie
 * `SettingsSearchPage.home` (iOS).
 */
val SettingsPage.home: SettingsHome?
    get() = when (this) {
        SettingsPage.LANGUAGE, SettingsPage.CURRENCY, SettingsPage.WATCHLIST, SettingsPage.ALARMS,
        SettingsPage.UPDATES -> SettingsHome.GENERAL
        SettingsPage.DISPLAY_MODE, SettingsPage.THEME, SettingsPage.PRICE_COLORS, SettingsPage.WIDGETS ->
            SettingsHome.APPEARANCE
        SettingsPage.BACKUP, SettingsPage.ADVANCED, SettingsPage.ABOUT -> SettingsHome.MORE
        SettingsPage.MARKETS, SettingsPage.PORTFOLIO, SettingsPage.CHANGE_BASIS, SettingsPage.COIN_LOGOS,
        SettingsPage.DEVELOPER -> SettingsHome.ADVANCED
        SettingsPage.PRICE_PAIRS, SettingsPage.LICENSES -> null
    }

/**
 * Zeigt eine Unterseite; ein unbekannter Name (alte Route) führt gleich zurück. [onOpenPage] brauchen
 * «Alarme» und «Erweitert» (öffnen weitere Unterseiten); «Erweitert» öffnet auch Markt-Meldungen
 * ([onOpenMarketAlerts]) und Sprachausgabe ([onOpenSpeech]), die eigene Routen haben.
 */
@Composable
fun SettingsPageScreen(
    page: SettingsPage?,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit = {},
    onOpenMarketAlerts: () -> Unit = {},
    onOpenSpeech: () -> Unit = {},
) {
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
        SettingsPage.ALARMS -> AlarmsPage(onBack, onOpenPage)
        SettingsPage.PRICE_PAIRS -> PricePairsPage(onBack)
        SettingsPage.PORTFOLIO -> PortfolioPage(onBack)
        SettingsPage.BACKUP -> BackupPage(onBack)
        SettingsPage.ABOUT -> AboutPage(onBack, onOpenPage)
        SettingsPage.LICENSES -> LicensesPage(onBack)
        SettingsPage.WIDGETS -> WidgetsPage(onBack)
        SettingsPage.DEVELOPER -> DeveloperPage(onBack)
        SettingsPage.ADVANCED -> AdvancedPage(onBack, onOpenPage, onOpenMarketAlerts, onOpenSpeech)
        SettingsPage.MARKETS -> MarketsPage(onBack)
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
            .background(if (selected) AppColors.accentTint(SELECTED_ALPHA) else Color.Transparent)
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
