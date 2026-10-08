package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.marketdata.model.FuturesContractType

/** Fläche für einen Schritt. */
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
 * Auf-/Zuklappen bleibt für die ganze Sitzung (bis die App beendet wird), auch wenn
 * man den Tab wechselt. Standard: beides eingeklappt — die Suche ist der Hauptweg.
 */
internal object ExplorerSections {
    var precise by mutableStateOf(false)
    var bulk by mutableStateOf(false)
}

/** Kopfzeile eines aufklappbaren Bereichs: Titel und Pfeil, als Ganzes antippbar. */
@Composable
internal fun SectionToggle(title: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .toggleable(value = expanded, role = Role.Button, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = Spacing.lg)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 90f else 0f }
        )
    }
}

@Composable
internal fun getContractTypeName(contractType: FuturesContractType): String {
    if(contractType == FuturesContractType.NONE)
        return stringResource(id = R.string.market_screen_spot)

    return contractType.toString()
}

/**
 * Schritt-Kopf: Nummer im Kreis, aktiv bzw. erledigt in der Akzentfarbe,
 * noch nicht erreichbar blass. Zeigt auf einen Blick, was als Nächstes kommt.
 */
@Composable
internal fun StepHeader(number: Int, title: String, active: Boolean, done: Boolean) {
    val lit = active || done
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (lit) accent else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = if (done) "✓" else LocaleNumbers.integer(number),
                style = MaterialTheme.typography.labelMedium,
                color = if (lit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (lit) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm)
        )
    }
}

/** Favoriten der drei Auswahllisten. */
class FavoritesUi(
    val markets: Set<String> = emptySet(),
    val coins: Set<String> = emptySet(),
    val quotes: Set<String> = emptySet(),
    val onToggle: (FavoriteKind, String) -> Unit = { _, _ -> },
)
