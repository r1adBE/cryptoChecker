@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.ui.components.LocalCoinNameSource
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.ui.theme.label
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalDensity
import com.cryptochecker.app.util.DenseRowText
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.components.AssetKindIcon
import com.cryptochecker.app.ui.components.LocalRowHighlight
import com.cryptochecker.app.ui.components.coinName
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers

/** Mitte: Paar (mit Häkchen des Erst-Moments und ⚡), Börse und Zeit bzw. Warnung, Fehler, Notiz. */
@Composable
internal fun RowInfo(
    watch: WatchEntity,
    alarmCount: Int,
    now: Long,
    stale: Boolean,
    status: WatchRowStatus,
    accent: Color,
    check: Animatable<Float, AnimationVector1D>,
    hasActivity: Boolean,
    onActivityClick: () -> Unit,
    modifier: Modifier,
    /** Art des Paars: Symbol vor der Glocke ([AssetKindIcon]). */
    kind: AssetKind = AssetKind.TOKEN,
) {
    // Deaktiviert (L-13): Texte in onSurfaceVariant, Symbole mit reduzierter Deckkraft
    val off = watch.disabled
    val iconAlpha = if (off) DISABLED_ICON_ALPHA else 1f
    // Grosse Schrift: Paar und Zeitzeile umbrechen (zwei Zeilen) statt abschneiden — wie iOS
    val lines = DenseRowText.maxLines(LocalDensity.current.fontScale)
    // Symbole der Zeile wachsen mit der Schriftgrösse (13 sp ≙ 13 dp bei Standardschrift) — wie iOS @ScaledMetric
    val rowIcon = with(LocalDensity.current) { 13.sp.toDp() }
    val noteIcon = with(LocalDensity.current) { 12.sp.toDp() }
    Column(modifier = modifier) {
        // Das Paar geht vor — es bekommt den Platz bis auf einen ⚡-Chip ohne Text; «Was bewegt sich mit?»
        // steht nur, wenn danach noch Platz ist ([ActivityBolt] klappt sonst auf ⚡ zusammen)
        Layout(
            content = {
                PairName(
                    base = watch.baseAsset,
                    rest = watch.displayName.removePrefix(watch.baseAsset),
                    color = disabledTextColor(off),
                    maxLines = lines,
                    modifier = Modifier.layoutId(PAIR_NAME).clearAndSetSemantics { }
                )
                // Erst-Moment: kurzes Häkchen in der Akzentfarbe (das Banner sagt es dem Screenreader)
                if (check.value > 0f) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .layoutId(PAIR_CHECK)
                            .padding(start = 4.dp)
                            .size(16.dp)
                            .graphicsLayer {
                                alpha = check.value
                                val scale = 0.7f + 0.3f * check.value
                                scaleX = scale
                                scaleY = scale
                            }
                            .clearAndSetSemantics { }
                    )
                }
                // «⚡ Was bewegt sich mit?» bei ungewöhnlicher Aktivität — eigener Chip, Tipp öffnet «Was bewegt sich mit?»
                if (hasActivity) {
                    ActivityBolt(onClick = onActivityClick, modifier = Modifier.layoutId(PAIR_WHY).padding(start = Spacing.sm))
                }
            }
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val checkP = measurables.firstOrNull { it.layoutId == PAIR_CHECK }?.measure(loose)
            val why = measurables.firstOrNull { it.layoutId == PAIR_WHY }
            val checkW = checkP?.width ?: 0
            val whyMin = if (why != null) (WHY_CHIP_ICON_ONLY_WIDTH + Spacing.sm).roundToPx() else 0
            val nameMax = if (constraints.hasBoundedWidth) (constraints.maxWidth - checkW - whyMin).coerceAtLeast(0)
            else Constraints.Infinity
            val nameP = measurables.first { it.layoutId == PAIR_NAME }.measure(loose.copy(maxWidth = nameMax))
            val whyMax = if (constraints.hasBoundedWidth) (constraints.maxWidth - nameP.width - checkW).coerceAtLeast(0)
            else Constraints.Infinity
            val whyP = why?.measure(loose.copy(maxWidth = whyMax))
            val width = nameP.width + checkW + (whyP?.width ?: 0)
            val height = maxOf(nameP.height, checkP?.height ?: 0, whyP?.height ?: 0)
            // Mitte an Mitte wie die frühere Zeile (Alignment.CenterVertically); placeRelative spiegelt bei RTL
            // (Arabisch, Hebräisch, Persisch) wie der Kopf daneben
            layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceAtLeast(constraints.minHeight)) {
                var x = 0
                nameP.placeRelative(x, (height - nameP.height) / 2); x += nameP.width
                checkP?.let { it.placeRelative(x, (height - it.height) / 2); x += it.width }
                whyP?.let { it.placeRelative(x, (height - it.height) / 2) }
            }
        }
        // «Namen anzeigen»: Name unter dem Paar («Bitcoin», «NVIDIA»), ohne bekannten Namen «–»;
        // im Zeilensatz enthalten. Gleiche Höhe wie die %-Änderung rechts (bodySmall = labelMedium
        // 16 sp + 2 × 2 dp), so stehen Paar/Kurs, Name/Änderung und Börse/≈ Umrechnung je auf einer Linie.
        val namesOn = LocalCoinNameSource.current != null
        if (namesOn) {
            Text(
                text = coinName(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name) ?: "–",
                style = MaterialTheme.typography.bodySmall,
                color = disabledTextColor(off, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp).clearAndSetSemantics { }
            )
        }
        // Im Zeilensatz enthalten, hier für den Screenreader ausgeblendet
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Mit Namen: gleicher Abstand wie die ≈ Umrechnung rechts
            modifier = Modifier.padding(top = if (namesOn) 2.dp else 0.dp).clearAndSetSemantics { }
        ) {
            // Warnung (nicht erreichbar / veraltet) ersetzt die normale Zeitzeile. Bernstein mit ⚠ statt Fehlerrot:
            // Rot ist dunkel kaum vom fallenden Kurs zu unterscheiden (wie iOS)
            val warning = status.warning
            val staleTime = stale && watch.lastUpdate > 0
            if ((warning != null || staleTime) && !watch.disabled) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    // Gleiche Bernstein-Stufe wie der Text daneben: AppColors.warning erreicht auf hellen Karten keine 3:1
                    tint = AppColors.warningText,
                    modifier = Modifier.padding(end = 4.dp).size(rowIcon)
                )
            }
            Text(
                text = when {
                    // Deaktiviert: «Binance · deaktiviert» statt Zeit bzw. Warnung
                    watch.disabled -> stringResource(R.string.watchlist_row_disabled, BidiText.isolate(watch.marketName))
                    warning != null -> warning
                    // Zeit nur, wenn das Paar nicht aktuell ist — sonst steht sie oben im Status
                    // («Kurse der gewählten Börsen · vor 2 Min.») und wäre in jeder Zeile doppelt
                    staleTime -> "${BidiText.isolate(watch.marketName)} · ${ago(watch.lastUpdate, now)}"
                    // Börse ohne eigene Zeit, Kurs steht lange: nicht frisch wirken (PriceFreshness)
                    status.unchanged != null -> "${BidiText.isolate(watch.marketName)} · ${status.unchanged}"
                    // Herkunft von Änderung/Mini-Chart («Änderung: Binance») steht seit L-14 nur im Aktionsblatt
                    else -> watch.marketName
                },
                // Lesegrösse (14 sp) statt 12 — bricht bei grosser Schrift um ([DenseRowText]),
                // rechts steht dieselbe Stufe (≈ Umrechnung), die Linien bleiben gleich hoch
                style = MaterialTheme.typography.body,
                color = when {
                    watch.disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    // Bernstein-Schrift (≥ 4.5:1 auch auf hellen Karten), nie die Farbe eines fallenden Kurses
                    warning != null || staleTime -> AppColors.warningText
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            // Art vor der Glocke: Token (Raute), TradFi (Bank), Alpha («α») wie die Chips;
            // neutral wie die Glocke, in der Themenfarbe nur mit gewähltem passendem Chip
            val highlight = LocalRowHighlight.current
            val kindHighlighted = when (kind) {
                AssetKind.TOKEN -> highlight.tokens
                AssetKind.TRADFI -> highlight.tradFi
                AssetKind.ALPHA -> highlight.alpha
            }
            AssetKindIcon(
                kind = kind,
                tint = if (kindHighlighted) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                size = rowIcon,
                modifier = Modifier.padding(start = Spacing.xs).alpha(iconAlpha)
            )
            // Kursmitteilung (Entscheid 16. Oktober 2026, wieder in der Zeile): schlichte Glocke = an, durchgestrichene
            // = aus (Paar aus oder Hauptschalter «Kursmitteilungen» aus). Danach die Alarmglocke (mit Wellen), nur bei
            // mind. einem aktiven Alarm ([alarmCount] zählt nur eingeschaltete).
            val priceOn = com.cryptochecker.app.util.A11ySpeech.priceNotificationOn(
                pairOn = watch.notificationEnabled,
                masterOn = com.cryptochecker.app.ui.components.LocalPriceNotificationsOn.current,
            )
            Icon(
                painterResource(if (priceOn) R.drawable.ic_notifications else R.drawable.ic_notifications_off),
                contentDescription = stringResource(
                    if (priceOn) R.string.a11y_price_notification_on else R.string.a11y_price_notification_off
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs).size(rowIcon).alpha(iconAlpha)
            )
            if (alarmCount > 0) {
                Icon(
                    painterResource(R.drawable.ic_notifications_active),
                    contentDescription = pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount),
                    // Textfarbe (weiss bzw. dunkel im Hellmodus), nicht die Themenfarbe
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = Spacing.xs).size(rowIcon).alpha(iconAlpha)
                )
                Text(
                    text = LocaleNumbers.integer(alarmCount),
                    style = MaterialTheme.typography.label,
                    color = disabledTextColor(off, MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
        watch.lastError?.takeIf { status.showErrorLine }?.let { error ->
            Text(
                text = friendlyError(error),
                style = MaterialTheme.typography.bodySmall,
                color = if (isNotTraded(error)) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
        // Eigene Notiz (#233), dezent unter dem Paar
        watch.note?.let { note ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { }
            ) {
                Icon(
                    painterResource(R.drawable.ic_note),
                    contentDescription = stringResource(R.string.note_title),
                    // Volle Akzentfarbe (Kontrast); abgeblasst nur bei deaktiviertem Paar ([iconAlpha])
                    tint = accent,
                    modifier = Modifier.size(noteIcon).alpha(iconAlpha)
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/**
 * Paar in zwei Teilen: Basis gross und halbfett («BTC»), dahinter klein und grau der Rest
 * («/USDT», bei Futures «/USDT PERP»), auf gemeinsamer Grundlinie. Die Basis — das Wichtigste der
 * Zeile — wird nie zugunsten der Quote gekürzt: Der Rest erscheint nur, wenn mindestens sein erstes
 * Wort («/USDT») ganz Platz hat, sonst entfällt er (die Quote steht ohnehin beim Kurs). Wie iOS
 * `WatchlistRow.pairName`.
 */
@Composable
private fun PairName(base: String, rest: String, color: Color, maxLines: Int, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = base,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = maxLines,
                // Nur als letzter Ausweg (überlange Symbole): der Rest ist dann schon weg
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rest,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val baseP = measurables[0].measure(loose)
        val restM = measurables[1]
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth - baseP.width else Constraints.Infinity
        // Längstes Wort des Rests (z. B. «/USDT»): passt es nicht ganz, entfällt der Rest
        val restP = if (rest.isNotEmpty() && room >= restM.minIntrinsicWidth(Constraints.Infinity)) {
            restM.measure(loose.copy(maxWidth = room))
        } else null
        // Gemeinsame Grundlinie: der kleine Rest sitzt auf der Grundlinie der Basis
        val baseLine = baseP[FirstBaseline]
        val restLine = restP?.get(FirstBaseline) ?: 0
        val restTop = (baseLine - restLine).coerceAtLeast(0)
        val baseTop = (restLine - baseLine).coerceAtLeast(0)
        val width = baseP.width + (restP?.width ?: 0)
        val height = maxOf(baseTop + baseP.height, restP?.let { restTop + it.height } ?: 0)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceAtLeast(constraints.minHeight)) {
            baseP.placeRelative(0, baseTop)
            restP?.placeRelative(baseP.width, restTop)
        }
    }
}

private const val PAIR_NAME = "name"
private const val PAIR_CHECK = "check"
private const val PAIR_WHY = "why"
