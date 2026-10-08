@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.components.RollingNumberText
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Kompakte Zeile: Stern, Paar, Börse, Kurs und Prozent-Pille.
 * Tippen öffnet die Aktionen sofort (kein Doppeltippen mehr, das jeden Tipp ~0,3 s
 * verzögert hätte); Favorit per Stern, Wischen nach rechts oder Aktionen-Menü;
 * lange drücken startet das Sortieren.
 */
@Composable
internal fun WatchRow(
    watch: WatchEntity,
    alarmCount: Int,
    now: Long,
    staleAfter: Long,
    onClick: () -> Unit,
    /** Ohne Fehler, aber älter als das: «veraltet» statt der normalen Zeitzeile. */
    outdatedAfter: Long = Long.MAX_VALUE,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    highlighted: Boolean = false,
    sortMode: Boolean = false,
    onMove: (WatchMove) -> Unit = {},
    /** Screenreader-Aktion «Löschen» (wie nach links wischen); null = keine. */
    onDeleteAction: (() -> Unit)? = null,
    /** Screenreader-Aktion «Favorit hinzufügen/entfernen» (wie nach rechts wischen); null = keine. */
    onFavoriteAction: (() -> Unit)? = null,
    /** TalkBack-Aktionen «Nach oben»/«Nach unten» anbieten. */
    canReorder: Boolean = false,
    handleModifier: Modifier = Modifier,
    hasActivity: Boolean = false,
    onActivityClick: () -> Unit = {},
    /** Kurs in der Umrechnungswährung, z. B. «≈ 61’234 CHF»; null = nichts zeigen. */
    converted: String? = null,
    /** 24-Stunden-Verlauf (Schlusskurse) für das Mini-Chart; null = keines zeigen. */
    sparkline: List<Double>? = null,
    /** Erst-Moment, zu dem diese Zeile gehört ([AddMoment.id]); null = keiner. */
    celebrateKey: Long? = null,
    /** Platz der Zeile in der Staffelung des Erst-Moments. */
    celebrateIndex: Int? = null,
    /** Animationen im System ausgeschaltet: nur erscheinen, nichts gleitet oder zeichnet. */
    reduceMotion: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val hasSparkline = sparkline != null && sparkline.size >= 2
    val motion = rememberRowMotion(
        watchId = watch.id,
        celebrateKey = celebrateKey,
        celebrateIndex = celebrateIndex,
        reduceMotion = reduceMotion,
        hasPrice = watch.lastPrice != null,
        hasSparkline = hasSparkline,
    )
    val stale = !isNotTraded(watch.lastError) && (watch.lastUpdate <= 0 || now - watch.lastUpdate > staleAfter)
    val flash = rememberPriceFlash(watch.id, watch.lastPrice)
    val status = watchRowStatus(watch, now, stale, outdatedAfter)

    // Screenreader: die ganze Zeile als ein Satz (Paar, Kurs, Änderung, ≈, Verlauf, Notiz,
    // Alarme, Zustand). Favorit, ⚡ und Menü bleiben eigene Knöpfe; Tippen bleibt.
    val context = LocalContext.current
    val chartPeriod = stringResource(R.string.widget_range_24h)
    val moveUpLabel = stringResource(R.string.a11y_move_up)
    val moveDownLabel = stringResource(R.string.a11y_move_down)
    val deleteLabel = stringResource(R.string.action_delete)
    val favoriteLabel = stringResource(if (watch.favorite) R.string.favorite_remove else R.string.favorite_add)
    val changeView = LocalChangeView.current
    val rowDescription = A11yText.row(
        context = context,
        pair = watch.displayName,
        market = watch.marketName,
        price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
        change24h = watch.shownChange(changeView),
        basis = changeView.basis,
        extras = listOf(
            converted?.let { stringResource(R.string.a11y_converted, it.removePrefix("≈ ")) },
            sparkline?.takeIf { it.size >= 2 }?.let { A11yText.chart(context, chartPeriod, it) },
            watch.note?.let { stringResource(R.string.a11y_note, it) },
            if (alarmCount > 0) stringResource(R.string.a11y_alarm_count, alarmCount) else null,
            if (watch.notificationEnabled) stringResource(R.string.watchlist_notification) else null,
            status.warning,
            status.error,
            status.stale,
        ),
    )

    Card(
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = when {
            highlighted -> BorderStroke(1.5.dp, accent)
            watch.favorite -> BorderStroke(1.dp, accent.copy(alpha = 0.45f))
            // Feiner Rand, damit sich die Karten von der Fläche abheben
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        },
        modifier = modifier
            .graphicsLayer {
                alpha = motion.enter.value
                translationY = (1f - motion.enter.value) * 16.dp.toPx()
            }
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = rowDescription
                val actions = buildList {
                    // Wie die Wisch-Gesten; im Sortiermodus nur Verschieben
                    if (!sortMode) {
                        onFavoriteAction?.let { action -> add(CustomAccessibilityAction(favoriteLabel) { action(); true }) }
                        onDeleteAction?.let { action -> add(CustomAccessibilityAction(deleteLabel) { action(); true }) }
                    }
                    if (canReorder) {
                        add(CustomAccessibilityAction(moveUpLabel) { onMove(WatchMove.UP); true })
                        add(CustomAccessibilityAction(moveDownLabel) { onMove(WatchMove.DOWN); true })
                    }
                }
                if (actions.isNotEmpty()) customActions = actions
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = Spacing.md, top = Spacing.md, bottom = Spacing.md)
        ) {
            RowLeading(
                sortMode = sortMode,
                favorite = watch.favorite,
                accent = accent,
                handleModifier = handleModifier,
                onToggleFavorite = onToggleFavorite,
            )
            RowInfo(
                watch = watch,
                alarmCount = alarmCount,
                now = now,
                stale = stale,
                status = status,
                accent = accent,
                check = motion.check,
                hasActivity = hasActivity,
                onActivityClick = onActivityClick,
                modifier = Modifier.weight(1f).padding(start = 2.dp),
            )

            // Mini-Chart zwischen Paar und Kurs; feste Grösse, der Kurs wird nie schmaler
            if (sparkline != null && hasSparkline) {
                Sparkline(
                    values = sparkline,
                    progress = { motion.draw.value },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        // 28 dp (früher 22): passt in die Zeilenhöhe, die der Stern-Knopf (48 dp) vorgibt
                        .size(width = 56.dp, height = 28.dp)
                        .alpha(if (stale) 0.5f else 1f)
                        .clearAndSetSemantics { }
                )
            }

            RowPrice(
                watch = watch,
                converted = converted,
                stale = stale,
                reveal = motion.priceReveal,
                flash = flash,
            )

            // Sortiermodus: «ganz nach oben/unten» im Zeilenmenü statt als Symbole
            if (sortMode) RowSortMenu(onMove = onMove)
        }
    }
}

/** Links: Griff im Sortiermodus, sonst Stern bei Favoriten bzw. etwas Abstand. */
@Composable
private fun RowLeading(
    sortMode: Boolean,
    favorite: Boolean,
    accent: Color,
    handleModifier: Modifier,
    onToggleFavorite: () -> Unit,
) {
    if (sortMode) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = handleModifier
                .padding(start = Spacing.xs, end = Spacing.xs)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.12f))
        ) {
            Icon(
                painterResource(R.drawable.ic_drag_handle),
                contentDescription = stringResource(R.string.sort_drag_handle),
                tint = accent
            )
        }
    } else if (favorite) {
        // Stern nur bei Favoriten — kein leerer Umriss, der dem Kurs Breite nimmt
        IconButton(onClick = onToggleFavorite) {
            Icon(
                painterResource(R.drawable.ic_star),
                contentDescription = stringResource(R.string.favorite_remove),
                tint = accent
            )
        }
    } else {
        Spacer(Modifier.width(Spacing.sm))
    }
}

/** Rechts: Kurs (rollende Ziffern, kurzes Aufblitzen), Pille der %-Änderung und ≈ Umrechnung. */
@Composable
private fun RowPrice(
    watch: WatchEntity,
    converted: String?,
    stale: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    flash: PriceFlash,
) {
    val flashColor = if (flash.up) PriceColors.up else PriceColors.down
    // Kurs bleibt auch im Sortiermodus sichtbar
    Column(
        horizontalAlignment = Alignment.End,
        // Veraltete Kurse abblassen
        modifier = Modifier.padding(start = 8.dp).alpha(if (stale) 0.5f else 1f).clearAndSetSemantics { }
    ) {
        // Geänderte Ziffern rollen (nach oben bei steigendem Kurs)
        RollingNumberText(
            text = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
            value = watch.lastPrice,
            style = MaterialTheme.typography.titleMedium.amountNumbers(),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .graphicsLayer {
                    alpha = reveal.value
                    translationY = (1f - reveal.value) * 6.dp.toPx()
                }
                .clip(RoundedCornerShape(6.dp))
                .background(flashColor.copy(alpha = 0.28f * flash.strength.value))
                .padding(horizontal = 4.dp)
        )
        Box(Modifier.graphicsLayer { alpha = reveal.value }) {
            if (watch.lastPrice != null) DayChangePill(change = watch.shownChange(LocalChangeView.current))
        }
        if (converted != null) {
            Text(
                text = converted,
                style = MaterialTheme.typography.labelSmall.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

/** Sortiermodus: «ganz nach oben/unten» im Zeilenmenü. */
@Composable
private fun RowSortMenu(onMove: (WatchMove) -> Unit) {
    Box {
        var menu by remember { mutableStateOf(false) }
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more)
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_move_top)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_move_top), null) },
                onClick = { menu = false; onMove(WatchMove.TOP) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_move_bottom)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_move_bottom), null) },
                onClick = { menu = false; onMove(WatchMove.BOTTOM) }
            )
        }
    }
}

/**
 * «≈ 61’234 CHF» für die Merkliste: nur mit Zielwährung, bekanntem Faktor,
 * gültigem Kurs und wenn die Quote nicht schon die Zielwährung ist.
 */
internal fun convertedPrice(watch: WatchEntity, target: String?, rates: Map<String, Double>): String? {
    if (target == null || CurrencyConversion.sameCurrency(watch.quoteAsset, target)) return null
    val price = watch.lastPrice?.takeIf { it > 0.0 } ?: return null
    val rate = rates[CurrencyConversion.normalize(watch.quoteAsset)] ?: return null
    val value = CurrencyConversion.convert(price, rate) ?: return null
    return "≈ " + PriceFormat.priceWithCurrency(value, target)
}

/**
 * Prozent-Änderung als Pille in der Kursfarbe (Grün/Rot bzw. Blau/Orange),
 * immer mit Vorzeichen — die Bedeutung hängt nie allein an der Farbe. Praktisch keine
 * Änderung: graues «0.00%» statt einer Lücke.
 */
@Composable
internal fun ChangePill(change: Double?) {
    if (change == null) return
    val formatted = PriceFormat.changePercent(change)
    // Pfeil folgt dem Vorzeichen, nie dem Farbtausch; bei 0.00% keiner
    val text = formatted?.let { "${PriceFormat.changeArrow(change)} $it" } ?: PriceFormat.zeroPercent()
    val color = if (formatted == null) MaterialTheme.colorScheme.onSurfaceVariant
    else PriceColors.forChange(change)
    // Screenreader: «gestiegen um 2.35%» statt «+2.35%» (das «−» wird uneinheitlich gelesen)
    val spoken = A11yText.change(LocalContext.current, change)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
