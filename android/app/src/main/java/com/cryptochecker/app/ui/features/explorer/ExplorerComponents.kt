package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType

/** Fläche einer Karte (Börse, Paar, DEX-Suche). */
@Composable
internal fun StepCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun StepHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp)
    )
}

/**
 * Register der Paar-Auswahl einer Börse («Paar wählen» | «Mehrere Paare auf einmal»). Die Navigation hält es für die
 * ganze Sitzung (jedes Öffnen der Seite, auch nach einem Tab-Wechsel) und speichert es mit [rememberExplorerSections] —
 * es übersteht Drehen und Prozess-Neustart; jede Komposition (z. B. ein UI-Test) hat ihren eigenen Stand.
 * Standard: «Paar wählen».
 */
@Stable
class ExplorerSectionsState(bulk: Boolean = false) {
    /** Register «Mehrere Paare auf einmal» gewählt (sonst «Paar wählen»). */
    var bulk by mutableStateOf(bulk)
}

/** Speichert [ExplorerSectionsState] als Wahrheitswert (bulk). */
private val explorerSectionsSaver: Saver<ExplorerSectionsState, Boolean> = Saver(
    save = { it.bulk },
    restore = { ExplorerSectionsState(bulk = it) },
)

/** Gespeicherter Stand der Register ([ExplorerSectionsState]). */
@Composable
fun rememberExplorerSections(): ExplorerSectionsState =
    rememberSaveable(saver = explorerSectionsSaver) { ExplorerSectionsState() }

/**
 * Zwei Register über der Paar-Auswahl: «Paar wählen» | «Mehrere Paare auf einmal». Beide gleich
 * breit und gleich hoch (der längere Text darf umbrechen, das andere Register wächst mit).
 * Gewählt: graue Fläche mit Schrift in der Themenfarbe, wie die Gruppen-Chips — gemeinsam mit dem
 * Markt-Tab ([com.cryptochecker.app.ui.components.RegisterTabs]). Wie `ExplorerModeTabs` (iOS).
 */
@Composable
internal fun ExplorerModeTabs(bulk: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    com.cryptochecker.app.ui.components.RegisterTabs(
        labels = listOf(stringResource(R.string.explorer_step_pair), stringResource(R.string.explorer_bulk_title)),
        selected = if (bulk) 1 else 0,
        onSelect = { onSelect(it == 1) },
        modifier = modifier
    )
}

/** Anzeigename einer Laufzeit; ohne Kontrakt «Spot». */
@Composable
internal fun contractName(type: FuturesContractType): String =
    if (type == FuturesContractType.NONE) stringResource(R.string.explorer_contract_spot) else type.toString()

/** Favoriten der drei Auswahllisten. */
class FavoritesUi(
    val exchanges: Set<String> = emptySet(),
    val coins: Set<String> = emptySet(),
    val quotes: Set<String> = emptySet(),
    val onToggle: (FavoriteKind, String) -> Unit = { _, _ -> },
)

/** Rückrufe für Börse, Paar und «Zur Merkliste». */
internal class PairActions(
    val onCoinChanged: (String?) -> Unit,
    val onQuoteChanged: (String?) -> Unit,
    val onContractChanged: (FuturesContractType?) -> Unit,
    /** Kurs des gewählten Paars neu laden. */
    val onRetryPrice: () -> Unit,
    val onReloadPairs: () -> Unit,
    val onAdd: () -> Unit,
    /** Rückmeldung zu «Zur Merkliste» wurde gezeigt. */
    val onAddShown: () -> Unit,
)

/** Rückrufe für «Mehrere Paare auf einmal». */
internal class BulkActions(
    val onApply: () -> Unit,
    val onSelectPair: (ExchangePair) -> Unit,
    val onAddAll: () -> Unit,
    val onAddAllShown: () -> Unit,
    /** Ergebnis wird gezeigt: im Runner als gemeldet markieren. */
    val onAddAllClaimed: () -> Unit,
    /** «Rückgängig» nach dem Hinzufügen: diese neu angelegten Paare wieder entfernen. */
    val onUndo: (List<Long>) -> Unit,
    val onQuoteChanged: (String) -> Unit,
    /** Liste offen: Kennzahlen für die Schnellfilter laden (eine Sammelabfrage). */
    val onNeedStats: () -> Unit,
)
