@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers

/** Kleines ⚡ in der Zeile eines Paars mit aktiven Signalen; Tipp öffnet «Warum». */
@Composable
internal fun ActivityBolt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Icon(
            painterResource(R.drawable.ic_bolt),
            contentDescription = stringResource(R.string.activity_indicator),
            tint = AppColors.warning,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Schlanke Karte über der Liste: «⚡ Hier passiert gerade etwas» mit bis zu
 * vier Coins; Tipp auf einen Coin öffnet dessen «Warum»-Blatt.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ActivityCard(
    hot: List<WatchEntity>,
    onOpen: (WatchEntity) -> Unit,
    /** Höchstens so viele Coins (Empfindlichkeit «Weniger»: die 3 stärksten); null = alle. */
    limit: Int? = null,
    /** «Anpassen»: öffnet die Einstellung «Empfindlichkeit»; null = ohne Link. */
    onAdjust: (() -> Unit)? = null,
) {
    if (hot.isEmpty()) return
    // Gleicher Coin an mehreren Börsen: einmal zeigen; [hot] ist nach Stärke sortiert
    val coins = remember(hot, limit) {
        hot.distinctBy { it.baseAsset.uppercase() }.let { if (limit != null) it.take(limit) else it }
    }
    val shown = coins.take(MAX_CARD_COINS)
    val more = coins.size - shown.size
    val amber = AppColors.warning

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(amber.copy(alpha = 0.16f))
                ) {
                    Icon(painterResource(R.drawable.ic_bolt), null, tint = amber, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = stringResource(R.string.activity_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = Spacing.sm).weight(1f)
                )
                // Kleiner Link zur Empfindlichkeit (zu viele/zu wenige Coins markiert?)
                if (onAdjust != null) {
                    val adjustA11y = stringResource(R.string.activity_adjust_a11y)
                    TextButton(
                        onClick = onAdjust,
                        modifier = Modifier.semantics { contentDescription = adjustA11y }
                    ) {
                        Text(
                            text = stringResource(R.string.activity_adjust),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }
            // Alle Coins umbrechend; «+n» klappt den Rest auf, «−» wieder zu.
            var expanded by rememberSaveable { mutableStateOf(false) }
            val visible = if (expanded) coins else shown
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.padding(top = 4.dp).animateContentSize()
            ) {
                visible.forEach { watch ->
                    AssistChip(
                        onClick = { onOpen(watch) },
                        label = { Text(watch.baseAsset, fontWeight = FontWeight.Medium, maxLines = 1) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = amber.copy(alpha = 0.10f)
                        )
                    )
                }
                if (more > 0) {
                    AssistChip(
                        onClick = { expanded = !expanded },
                        label = {
                            Text(
                                text = if (expanded) "−" else stringResource(R.string.activity_more, more),
                                style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

private const val MAX_CARD_COINS = 4

/** Platzhalter → Inhalt und «Details»: Dauer der Überblendung bzw. Grössenänderung. */
internal const val SWAP_MILLIS = 220
