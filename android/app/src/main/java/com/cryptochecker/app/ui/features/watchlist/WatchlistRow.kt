@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.annotation.SuppressLint
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.format.DenseRowText
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.domain.watch.RowPause
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.LocalCoinNameSource
import com.cryptochecker.app.ui.components.coinName
import com.cryptochecker.app.ui.components.segmentLines
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

/**
 * Kompakte Zeile: Coin-Logo (mit Stern bei Favoriten), Paar, Börse, Kurs und Prozent-Änderung.
 * Tippen öffnet die Aktionen sofort (kein Doppeltippen mehr, das jeden Tipp ~0,3 s
 * verzögert hätte); Favorit per Wischen nach rechts oder Aktionsblatt;
 * lange drücken startet die Mehrfachauswahl mit dieser Zeile ([onLongClick]; wie ⋯ › «Auswählen»),
 * in der Auswahl hakt es an/ab wie Tippen. Sortieren nur über ⋯ › Sortieren.
 */
@Composable
internal fun WatchRow(
    watch: WatchEntity,
    alarmCount: Int,
    now: Long,
    /** Frische-Schwelle: Änderung grau und ohne Fehler «veraltet» statt der normalen Zeitzeile. */
    staleAfter: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    highlighted: Boolean = false,
    sortMode: Boolean = false,
    /** Mehrfachauswahl: Häkchen-Kreis links statt Abstand, Zeile als Ankreuzfeld. */
    selecting: Boolean = false,
    selected: Boolean = false,
    onMove: (WatchMove) -> Unit = {},
    /** Screenreader-Aktion «Löschen» (wie nach links wischen); null = keine. */
    onDeleteAction: (() -> Unit)? = null,
    /** Screenreader-Aktion «Favorit hinzufügen/entfernen» (wie nach rechts wischen); null = keine. */
    onFavoriteAction: (() -> Unit)? = null,
    /** TalkBack-Aktionen «Nach oben»/«Nach unten» anbieten. */
    canReorder: Boolean = false,
    /**
     * Lange drücken (ausserhalb von Sortier- und Auswahlmodus): Mehrfachauswahl mit dieser Zeile
     * starten; auch als Screenreader-Aktion «Auswählen». null = keine.
     */
    onLongClick: (() -> Unit)? = null,
    handleModifier: Modifier = Modifier,
    hasActivity: Boolean = false,
    onActivityClick: () -> Unit = {},
    /** Kurs in der Umrechnungswährung, z. B. «≈ 61’234 CHF»; null = nichts zeigen. */
    converted: String? = null,
    /** Screenreader-Fassung von [converted] (ganzer Vermerk statt der kurzen Zeile); null = [converted]. */
    convertedA11y: String? = null,
    /** 24-Stunden-Verlauf (Schlusskurse) für das Mini-Chart; null = keines zeigen. */
    sparkline: List<Double>? = null,
    /** Börse des Mini-Charts, falls nicht die eigene: im Screenreader-Satz «Chart: Binance»; null = eigene. */
    chartSource: String? = null,
    /**
     * Börse der Kerzen hinter der %-Änderung, falls nicht die eigene ([WatchlistViewModel.foreignCandleSource]):
     * kleiner Marker «*» an der Änderung und «Veränderung aus Binance-Kerzen» im Zeilensatz
     * ([DayChange.rowChangeMarker]); null = eigene Börse bzw. Ticker.
     */
    changeSource: String? = null,
    /** Erst-Moment, zu dem diese Zeile gehört ([AddMoment.id]); null = keiner. */
    celebrateKey: Long? = null,
    /** Platz der Zeile in der Staffelung des Erst-Moments. */
    celebrateIndex: Int? = null,
    /** Animationen im System ausgeschaltet: nur erscheinen, nichts gleitet oder zeichnet. */
    reduceMotion: Boolean = false,
    /** Form je nach Platz in der Liste ([ListSegment.shape]); Liste wirkt wie eine Einheit. */
    shape: Shape = MaterialTheme.shapes.medium,
    /** Letzte Auslösung eines Alarms dieses Paars (Epoch-ms, 0 = nie): Screenreader «Alarm ausgelöst». */
    alarmFiredAt: Long = 0L,
    /** Art des Paars: kleines Symbol vor der Glocke (Token, TradFi, α), im Zeilensatz gesprochen. */
    kind: AssetKind = AssetKind.TOKEN,
    /** Kurzer Hinweis im Banner der Merkliste (Herkunft der Änderung, Tipp auf «*»). */
    onSourceHint: (String) -> Unit = {},
    // Herkunft von Änderung/Mini-Chart steht auf der Paarseite ([PairHeader] rowSource), die Zeile zeigt nur den Marker «*»
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
    // Deaktiviert: ganz grau mit dem letzten Kurs, nie «veraltet» (es wird bewusst nicht abgefragt)
    val off = watch.disabled
    // Veraltet nach der Regel der Alarmsperre: Abruf zu alt oder letzter Handel laut Börse > 2 h ([PriceFreshness.isStale])
    val stale = PriceFreshness.isStale(watch, now, staleAfter)
    // Veraltet: nicht abgeblasst (Kontrast AA), sondern Kurs in onSurfaceVariant ([RowPrice])
    // samt «Börse · vor 13 Min.» in Fehlerfarbe; das Mini-Chart im selben Grau ohne Fläche ([Sparkline]).
    // Deaktiviert: Texte in onSurfaceVariant statt abgeblasst (Kontrast AA), nur Logo und
    // kleine Symbole behalten die reduzierte Deckkraft ([DISABLED_ICON_ALPHA])
    val flash = rememberPriceFlash(watch.id, watch.lastPrice)
    // Börse pausiert: «Binance · pausiert bis 19:45» statt der Zeitzeile ([RowPause])
    val pausedUntil = RowPause.shownUntil(off, LocalExchangePauses.current[watch.exchangeKey], now)
    val status = watchRowStatus(watch, now, stale, staleAfter, pausedUntil)

    // Screenreader: die ganze Zeile als ein Satz (Paar, Kurs, Änderung, ≈, Verlauf, Notiz,
    // Alarme, Zustand). ⚡ und Menü bleiben eigene Knöpfe, Favorit als Screenreader-Aktion; Tippen bleibt.
    val labels = rememberWatchRowLabels(watch.favorite)
    val changeView = LocalChangeView.current
    val name = coinName(watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name)
    // Gezeigte Änderung aus fremden Kerzen: Anbieter für Marker und Zeilensatz (gleiche Regel wie iOS)
    val changeMarker = DayChange.rowChangeMarker(changeSource, watch.shownChange(changeView), changeView.basis.isSinceLast)
    // Tippen auf das «*» (bzw. Screenreader-Aktion der Zeile): kurz erklären, woher die Änderung kommt — ohne das
    // Aktionsblatt zu öffnen. Im Sortier- und Auswahlmodus nicht (dort gehört jeder Tipp der Zeile).
    val sourceHintText = changeMarker?.let { stringResource(R.string.change_candle_source_hint, it) }
    // Im Banner der Merkliste (Snackbar) statt als Toast: gleiche Stelle wie alle anderen Hinweise
    val showSourceHint: (() -> Unit)? = sourceHintText?.takeIf { !sortMode && !selecting }?.let { text -> { onSourceHint(text) } }
    // Kurs ist der Mark-Preis (dünnes Orderbuch ohne Mittelkurs): so benennen, auch im Zeilensatz
    val markPrice = watch.id in LocalMarkFallback.current && watch.lastPrice != null
    // Stundenkurs (abgeschlossene Kerze ohne letzten Abschluss, WOO X Spot) bzw. Referenzkurs (EZB, NBU, NBP): wie im Blatt
    val rowExchange = com.cryptochecker.marketdata.util.ExchangesConfigUtils.getExchangeByKey(watch.exchangeKey)
    val hourlyPrice = !markPrice && watch.lastPrice != null && (rowExchange.priceIsHourlyClose || watch.id in LocalHourlyClose.current)
    val reference = com.cryptochecker.marketdata.model.ReferenceSource.of(watch.exchangeKey)?.takeIf {
        !markPrice && watch.lastPrice != null
    }
    val rowDescription = watchRowDescription(
        watch = watch,
        name = name,
        now = now,
        staleAfter = staleAfter,
        off = off,
        status = status,
        changeView = changeView,
        changeMarker = changeMarker,
        markPrice = markPrice,
        hourlyPrice = hourlyPrice,
        reference = reference,
        converted = convertedA11y ?: converted,
        sparkline = sparkline,
        chartSource = chartSource,
        kind = kind,
        alarmFiredAt = alarmFiredAt,
        alarmCount = alarmCount,
    )

    // Liste als eine Einheit: kein eigener Rand je Zeile (auch nicht für Favoriten), sondern
    // Trennlinie und Aussenrand der Liste ([segmentLines]); gewählt bzw. gezogen = leicht in der
    // Themenfarbe getönt statt eines Rands, der die Einheit aufbräche
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val container = if (highlighted) AppColors.accentTint(0.14f).compositeOver(surface) else surface
    Card(
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = modifier
            .graphicsLayer {
                alpha = motion.enter.value
                translationY = (1f - motion.enter.value) * 16.dp.toPx()
            }
            .segmentLines(shape, divider = ListSegment.dividerColor(), outline = ListSegment.outlineColor())
            .fillMaxWidth()
            .animateContentSize()
            .watchRowActions(
                labels = labels,
                description = rowDescription,
                sortMode = sortMode,
                selecting = selecting,
                selected = selected,
                onClick = onClick,
                onLongClick = onLongClick,
                onFavoriteAction = onFavoriteAction,
                onDeleteAction = onDeleteAction,
                onSourceHint = showSourceHint,
                canReorder = canReorder,
                onMove = onMove,
            )
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
}
