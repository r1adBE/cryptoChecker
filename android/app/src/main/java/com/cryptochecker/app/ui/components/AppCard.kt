package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Ebene einer Karte: [Top] für Karten direkt auf dem Bildschirm (20 dp), [Nested] für Karten
 * innerhalb einer Karte (16 dp). Wie iOS `CardLevel`.
 */
enum class CardLevel { Top, Nested }

/**
 * Innenabstand einer Karte: [Regular] rundum `Spacing.lg`; [List] für Zeilenlisten, deren Zeilen
 * den senkrechten Abstand selbst mitbringen; [None], wenn der Inhalt randlos antippbar ist oder
 * eine eigene Fläche zeichnet (Verlauf) und den Abstand darum selbst setzt. Wie iOS `CardPadding`.
 */
enum class CardPadding(internal val values: PaddingValues) {
    Regular(PaddingValues(Spacing.lg)),
    List(PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)),
    None(PaddingValues(0.dp)),
}

/**
 * Die eine Karte der App: Fläche `surfaceContainer`, kein Rand, Ecken nach [level]. Alle Bereiche
 * (Markt, Portfolio, Einstellungen) nehmen diese Karte, damit Radius, Fläche und Abstand nicht von
 * Bildschirm zu Bildschirm auseinanderlaufen; hervorgehoben wird nur über den Inhalt (z. B. den
 * Verlauf der Portfolio-Summe), nie über einen Rand. Wie iOS `.appCard(_:padding:)`.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    level: CardLevel = CardLevel.Top,
    padding: CardPadding = CardPadding.Regular,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = level.shape(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(padding.values), content = content)
    }
}

/** Ecken je Ebene aus der Formstufe ([com.cryptochecker.app.ui.theme.appShapes]). */
@Composable
private fun CardLevel.shape(): Shape = when (this) {
    CardLevel.Top -> MaterialTheme.shapes.large
    CardLevel.Nested -> MaterialTheme.shapes.medium
}
