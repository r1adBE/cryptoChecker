@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.annotation.SuppressLint
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.cryptochecker.app.R
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.ui.components.LocalCoinNameSource
import com.cryptochecker.app.ui.components.LocalRowHighlight
import com.cryptochecker.app.ui.components.coinName
import com.cryptochecker.app.ui.components.segmentLines
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.domain.watch.RowPause
import com.cryptochecker.app.util.A11ySpeech
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.DenseRowText
import com.cryptochecker.app.util.PriceFormat

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
    staleAfter: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Ohne Fehler, aber älter als das: «veraltet» statt der normalen Zeitzeile. */
    outdatedAfter: Long = Long.MAX_VALUE,
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
    // L-14: Herkunft von Änderung/Mini-Chart steht im Aktionsblatt ([WatchActionsSheet] rowSource), die Zeile
    // zeigt nur den Marker «*» an der Änderung ([changeSource])
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
    // Veraltet: nicht mehr abgeblasst (Kontrast AA), sondern Kurs in onSurfaceVariant ([RowPrice])
    // samt «Börse · vor 13 Min.» in Fehlerfarbe; das Mini-Chart im selben Grau ohne Fläche ([Sparkline]).
    // Deaktiviert (L-13): Texte in onSurfaceVariant statt abgeblasst (Kontrast AA), nur Logo und
    // kleine Symbole behalten die reduzierte Deckkraft ([DISABLED_ICON_ALPHA])
    val flash = rememberPriceFlash(watch.id, watch.lastPrice)
    // Börse pausiert: «Binance · pausiert bis 19:45» statt der Zeitzeile ([RowPause])
    val pausedUntil = RowPause.shownUntil(off, LocalExchangePauses.current[watch.marketKey], now)
    val status = watchRowStatus(watch, now, stale, outdatedAfter, pausedUntil)

    // Screenreader: die ganze Zeile als ein Satz (Paar, Kurs, Änderung, ≈, Verlauf, Notiz,
    // Alarme, Zustand). ⚡ und Menü bleiben eigene Knöpfe, Favorit als Screenreader-Aktion; Tippen bleibt.
    val context = LocalContext.current
    val chartPeriod = stringResource(R.string.widget_range_24h)
    val moveUpLabel = stringResource(R.string.a11y_move_up)
    val moveDownLabel = stringResource(R.string.a11y_move_down)
    val deleteLabel = stringResource(R.string.action_delete)
    val favoriteLabel = stringResource(if (watch.favorite) R.string.favorite_remove else R.string.favorite_add)
    val selectLabel = stringResource(R.string.watchlist_select)
    // Tipp öffnet das Aktionsblatt: TalkBack sagt «zum Kurs und Aktionen zeigen doppeltippen» statt «zum Aktivieren»
    val openLabel = stringResource(R.string.unusual_action_open)
    val changeView = LocalChangeView.current
    val name = coinName(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name)
    // Gezeigte Änderung aus fremden Kerzen: Anbieter für Marker und Zeilensatz (gleiche Regel wie iOS)
    val changeMarker = DayChange.rowChangeMarker(changeSource, watch.shownChange(changeView), changeView.basis.isSinceLast)
    // Tippen auf das «*» (bzw. Screenreader-Aktion der Zeile): kurz erklären, woher die Änderung kommt — ohne das
    // Aktionsblatt zu öffnen. Im Sortier- und Auswahlmodus nicht (dort gehört jeder Tipp der Zeile).
    val sourceHintLabel = stringResource(R.string.change_candle_source_action)
    val sourceHintText = changeMarker?.let { stringResource(R.string.change_candle_source_hint, it) }
    // Im Banner der Merkliste (Snackbar) statt als Toast: gleiche Stelle wie alle anderen Hinweise
    val showSourceHint: (() -> Unit)? = sourceHintText?.takeIf { !sortMode && !selecting }?.let { text -> { onSourceHint(text) } }
    // Kurs ist der Mark-Preis (dünnes Orderbuch ohne Mittelkurs): so benennen, auch im Zeilensatz
    val markPrice = watch.id in LocalMarkFallback.current && watch.lastPrice != null
    // Kurs ist der Schluss der laufenden Stundenkerze (WOO X Spot): wie im Aktionsblatt «Stundenkurs»
    val hourlyPrice = !markPrice && watch.lastPrice != null &&
        com.cryptochecker.marketdata.util.MarketsConfigUtils.getMarketByKey(watch.marketKey).priceIsHourlyClose
    // Frische ausgeschrieben statt der Zeitzeile mit «·» und «Min.»: «Kurs veraltet, zuletzt
    // aktualisiert vor 45 Minuten» bzw. «Deaktiviert» / «Wird … nicht mehr gehandelt»
    val notTraded = isNotTraded(watch.lastError)
    val freshness = A11ySpeech.freshness(
        disabled = off,
        notTraded = notTraded,
        failed = watch.lastError != null && !notTraded,
        lastUpdate = watch.lastUpdate,
        now = now,
        outdatedAfter = minOf(staleAfter, outdatedAfter),
    )
    val priceNotificationOn = A11ySpeech.priceNotificationOn(
        pairOn = watch.notificationEnabled,
        masterOn = com.cryptochecker.app.ui.components.LocalPriceNotificationsOn.current,
    )
    val rowDescription = A11yText.row(
        context = context,
        pair = if (name == null) watch.displayName else "${watch.displayName}, $name",
        market = watch.marketName,
        price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
        quote = watch.quoteAsset,
        change24h = watch.shownChange(changeView),
        basis = changeView.basis,
        state = listOf(status.paused, A11yText.freshness(context, freshness, watch.lastUpdate, now, watch.marketName), status.trade, status.unchanged),
        extras = listOf(
            changeMarker?.let { stringResource(R.string.change_candle_source, it) },
            if (markPrice) stringResource(R.string.watch_price_mark_fallback) else null,
            if (hourlyPrice) stringResource(R.string.watch_price_hourly) else null,
            (convertedA11y ?: converted)?.let { stringResource(R.string.a11y_converted, it.removePrefix("≈ ")) },
            sparkline?.takeIf { it.size >= 2 }?.let { A11yText.chart(context, chartPeriod, it) },
            chartSource?.takeIf { sparkline != null && sparkline.size >= 2 }?.let { stringResource(R.string.watch_row_source_chart, it) },
            watch.note?.let { stringResource(R.string.a11y_note, it) },
            if (watch.favorite) stringResource(R.string.a11y_favorite) else null,
        ),
        // Art vor der Glocke nur bei «TradFi» bzw. «Alpha-Token» — «Token» ist der Normalfall und
        // stünde sonst in jeder Zeile
        kind = if (kind == AssetKind.TOKEN) null else stringResource(kind.spokenRes),
        alarms = listOf(
            // Kursmitteilung nur, wenn sie aus ist — «an» ist der Normalfall und stünde sonst in jeder Zeile (wie iOS)
            if (!priceNotificationOn) stringResource(R.string.a11y_price_notification_off) else null,
            // Alarm eben ausgelöst (Mitteilung kam bei offener App): bis 15 Min. danach
            if (A11ySpeech.firedRecently(alarmFiredAt, now)) stringResource(R.string.a11y_alarm_fired) else null,
            // «Alarme (2)» wie die Glocke der Zeile und das Aktionsblatt (Mehrzahl je Sprache)
            if (alarmCount > 0) pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount) else null,
        ),
        rest = listOf(
            // Verständlicher Fehler (z. B. Paar unbekannt); «nicht mehr gehandelt» steht schon oben
            status.error.takeIf { freshness != A11ySpeech.Freshness.NOT_TRADED },
        ),
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
            .then(
                // Auswahl: langes Drücken löst beim Loslassen wie ein Tipp aus (hakt an/ab)
                if (selecting) Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
                // Lange drücken startet die Mehrfachauswahl; im Sortiermodus nicht (dort zieht man)
                else if (!sortMode && onLongClick != null) Modifier.combinedClickable(
                    onClickLabel = openLabel,
                    onLongClickLabel = selectLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                else Modifier.clickable(onClickLabel = openLabel.takeIf { !sortMode }, onClick = onClick)
            )
            .semantics {
                contentDescription = rowDescription
                val actions = buildList {
                    // Wie die Wisch-Gesten; im Sortier- und Auswahlmodus nicht
                    if (!sortMode && !selecting) {
                        onFavoriteAction?.let { action -> add(CustomAccessibilityAction(favoriteLabel) { action(); true }) }
                        onDeleteAction?.let { action -> add(CustomAccessibilityAction(deleteLabel) { action(); true }) }
                        // «Auswählen» wie langes Drücken (startet die Mehrfachauswahl mit dieser Zeile)
                        onLongClick?.let { action -> add(CustomAccessibilityAction(selectLabel) { action(); true }) }
                    }
                    showSourceHint?.let { action -> add(CustomAccessibilityAction(sourceHintLabel) { action(); true }) }
                    if (canReorder) {
                        add(CustomAccessibilityAction(moveUpLabel) { onMove(WatchMove.UP); true })
                        add(CustomAccessibilityAction(moveDownLabel) { onMove(WatchMove.DOWN); true })
                    }
                }
                if (actions.isNotEmpty()) customActions = actions
            }
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
                    // Coin-Logo (bzw. Initialen) vor dem Paar, Favorit als kleiner Stern daran; feste Grösse,
                    // nimmt nur dem Paar-Text Platz. Logos aus: keine Plakette, Favorit als eigener kleiner Stern.
                    if (LocalCoinLogoSource.current == null && watch.favorite && !sortMode) {
                        Icon(
                            painterResource(R.drawable.ic_star),
                            contentDescription = null,
                            // Wie der Stern am Logo: weiss, in der Themenfarbe nur mit gewähltem ★-Chip
                            tint = if (LocalRowHighlight.current.favorites) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(start = 4.dp, end = Spacing.sm)
                                .size(16.dp)
                        )
                    }
                    CoinBadge(
                        watch.baseAsset,
                        size = ROW_BADGE_SIZE,
                        logo = CoinLogos.allowedFor(watch.marketKey),
                        pair = CoinLogos.pairKey(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name),
                        favorite = watch.favorite && !sortMode,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(end = 8.dp)
                            .alpha(if (off) DISABLED_ICON_ALPHA else 1f)
                    )
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

                    // Mini-Chart zwischen Paar und Kurs; feste Grösse, der Kurs wird nie schmaler
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
                                // 28 dp (früher 22): passt in die Zeilenhöhe
                                .size(width = sparkWidth, height = 28.dp)
                                .clearAndSetSemantics { }
                        )
                    }

                    if (!stacked) price(Modifier.padding(start = 8.dp))

                    // Sortiermodus: «ganz nach oben/unten» im Zeilenmenü statt als Symbole
                    if (sortMode) Box(Modifier.align(Alignment.CenterVertically)) { RowSortMenu(onMove = onMove) }
                }
                if (stacked) price(Modifier.align(Alignment.End).padding(top = 4.dp))
            }
        }
    }
}
