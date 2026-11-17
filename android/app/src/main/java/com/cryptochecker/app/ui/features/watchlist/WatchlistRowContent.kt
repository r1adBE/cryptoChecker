@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.format.DenseRowText
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.app.ui.components.LocalCoinNameSource
import com.cryptochecker.app.ui.theme.Spacing

// Inhalt der Merklisten-Zeile ([WatchRow]): Griff bzw. Häkchen, Logo, Paar, Mini-Chart, Kurs und Notiz. Eigene
// Datei, weil WatchlistRow.kt sonst zu lang würde.

/** Inhalt der Karte von [WatchRow]: Griff bzw. Häkchen, Logo, Paar, Mini-Chart, Kurs und Notiz. */
@Composable
internal fun WatchRowContent(
    watch: WatchEntity,
    alarmCount: Int,
    now: Long,
    converted: String?,
    off: Boolean,
    stale: Boolean,
    status: WatchRowStatus,
    motion: RowMotion,
    flash: PriceFlash,
    changeMarker: String?,
    showSourceHint: (() -> Unit)?,
    markPrice: Boolean,
    hourlyPrice: Boolean,
    reference: com.cryptochecker.marketdata.model.ReferenceSource?,
    sortMode: Boolean,
    selecting: Boolean,
    selected: Boolean,
    accent: Color,
    handleModifier: Modifier,
    hasActivity: Boolean,
    onActivityClick: () -> Unit,
    kind: AssetKind,
    sparkline: List<Double>?,
    hasSparkline: Boolean,
    onMove: (WatchMove) -> Unit,
) {
    // Mit «Namen anzeigen» oben bündig: links Paar / Name / Börse, rechts Kurs / Änderung /
    // ≈ Umrechnung stehen je auf einer Linie; Logo, Mini-Chart und Griff bleiben mittig
    val namesOn = LocalCoinNameSource.current != null
    // Grösste Schrift (ab 1.5): Kurs unter das Paar statt daneben, links bleibt Breite ([DenseRowText])
    val stacked = DenseRowText.stacksTrailing(LocalDensity.current.fontScale)

    // Schmale Zeile (< 360 dp): Mini-Chart 48 statt 56 dp, das Paar behält mehr Platz ([DenseRowText]).
    // Breite einmal von der Liste ([LocalWatchRowWidth]) statt je Zeile gemessen
    // Bewusst die Bildschirmbreite aus der Configuration; ein Umbau auf containerSize wäre eine eigene Änderung
    @SuppressLint("ConfigurationScreenWidthHeight")
    val rowWidth = LocalWatchRowWidth.current.takeIf { it.isSpecified }?.value
        ?: LocalConfiguration.current.screenWidthDp.toFloat()
    Box {
        val sparkWidth = DenseRowText.sparklineWidth(rowWidth).dp
        val largePrice = DenseRowText.largePrice(LocalDensity.current.fontScale, rowWidth)
        // Kurs neben dem Paar bzw. (grösste Schrift) darunter – derselbe Inhalt an zwei möglichen Stellen
        val price: @Composable (Modifier) -> Unit = { priceModifier ->
            RowPrice(
                watch = watch,
                converted = converted,
                convertedMaxWidth = if (stacked) Dp.Unspecified else DenseRowText.convertedMaxWidth(rowWidth).dp,
                off = off,
                stale = stale,
                reveal = motion.priceReveal,
                flash = flash,
                changeMarker = changeMarker != null,
                changeMarkerLabel = changeMarker?.let(DayChange::rowMarkerLabel),
                onChangeMarkerClick = showSourceHint,
                markPrice = markPrice,
                hourlyPrice = hourlyPrice,
                reference = reference,
                largePrice = largePrice,
                modifier = priceModifier,
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp, end = Spacing.md, top = Spacing.md, bottom = Spacing.md)) {
            Row(verticalAlignment = if (namesOn) Alignment.Top else Alignment.CenterVertically) {
                Box(Modifier.align(Alignment.CenterVertically)) {
                    RowLeading(
                        sortMode = sortMode,
                        selecting = selecting,
                        selected = selected,
                        accent = accent,
                        handleModifier = handleModifier,
                    )
                }
                RowLogo(watch, sortMode, off)
                RowInfo(
                    watch = watch,
                    alarmCount = alarmCount,
                    now = now,
                    stale = stale,
                    status = status,
                    accent = accent,
                    check = motion.check,
                    hasActivity = hasActivity && !off,
                    onActivityClick = onActivityClick,
                    kind = kind,
                    modifier = Modifier.weight(1f),
                )

                // Mini-Chart zwischen Paar und Kurs; feste Grösse, immer sichtbar, wenn eingeschaltet — wird es
                // eng, bricht die Börsenzeile um ([RowMetaLine]), wie iOS. Der Kurs wird nie schmaler
                // (deaktiviert: keines — es wird nichts geladen)
                if (sparkline != null && hasSparkline && !off) {
                    Sparkline(
                        values = sparkline,
                        // Veraltet: grau wie der Kurs, ohne Fläche (statt halber Deckkraft in Kursfarbe)
                        stale = stale,
                        progress = { motion.draw.value },
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(start = 8.dp)
                            // 28 dp: passt in die Zeilenhöhe
                            .size(width = sparkWidth, height = 28.dp)
                            .clearAndSetSemantics { }
                    )
                }

                if (!stacked) price(Modifier.padding(start = 8.dp))

                // Sortiermodus: «ganz nach oben/unten» im Zeilenmenü statt als Symbole
                if (sortMode) Box(Modifier.align(Alignment.CenterVertically)) { RowSortMenu(onMove = onMove) }
            }
            watch.note?.let { note -> WatchRowNote(note, accent, off) }
            if (stacked) price(Modifier.align(Alignment.End).padding(top = 4.dp))
        }
    }
}
