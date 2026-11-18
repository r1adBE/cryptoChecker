package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.domain.market.MarketRevealSlot
import com.cryptochecker.app.domain.market.MarketSection
import com.cryptochecker.app.domain.market.PlainSummary
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.UnusualReport
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.readableWidth

// Inhalt von «Jetzt» im Tab «Markt» ([MarketPhaseScreen]). Eigene Datei, weil MarketPhaseScreen.kt sonst zu lang würde.

/** Inhalt von «Jetzt» ([MarketPhaseScreen]): scrollbare Spalte mit Status, Heldenkarte, Fear & Greed und mehr. */
@Composable
internal fun MarketNowContent(
    padding: PaddingValues,
    pullRefreshing: Boolean,
    scrollState: ScrollState,
    viewModel: InfoViewModel,
    dataAsOf: Long?,
    pulse: LoadState<PulseReport>,
    online: Boolean,
    motion: Boolean,
    reveal: MarketReveal.State,
    instant: Set<MarketRevealSlot>,
    plain: PlainSummary?,
    fearGreed: LoadState<FearGreed>,
    stamps: Map<CycleSource, DataStamp>,
    unusual: LoadState<UnusualReport>,
    macroEvents: List<MacroEvent>,
    macroAnchor: MacroAnchor,
    showMore: Boolean,
    onToggleMore: () -> Unit,
    onMacroHintHeight: (Int) -> Unit,
    onOpenCoin: (String) -> Unit,
    onOpenSection: (MarketSection) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = viewModel::refreshAll,
        modifier = Modifier.fillMaxSize().padding(padding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Sichtbereich (vor dem Scrollen): Bezug für den Sprung zum Wirtschaftsdaten-Hinweis
                .onGloballyPositioned { macroAnchor.viewport = it }
                .verticalScroll(scrollState)
                // Tablet/Querformat: Inhalt höchstens 640 dp breit, mittig
                .readableWidth()
                // Oben 4 dp wie Merkliste und Portfolio: Status-Pille und erste Karte auf gleicher Höhe
                .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp)
                // Kennung für UI-Tests (Bildschirmfotos): Puls geladen, alles Sichtbare auf «Jetzt» erschienen
                .then(
                    if (pulse is LoadState.Loaded && !reveal.nowLoading(showMore)) {
                        Modifier.testTag(UiTestTags.MARKET_LOADED)
                    } else {
                        Modifier
                    }
                )
        ) {
            // Statt der Überschrift «Jetzt» der Zustand wie in der Merkliste: «Alles aktuell ·
            // vor 2 Min.» bzw. während des Neuladens «Stand 14:05 · wird aktualisiert…».
            // Der Platz bleibt immer reserviert — darunter springt nichts.
            MarketStatusPill(
                refreshingSince = dataAsOf,
                updatedAt = (pulse as? LoadState.Loaded)?.value?.time,
                online = online,
                animate = motion
            )
            // 1. Eine Heldenkarte: Crypto Pulse mit «Kurz gesagt» als Körper (einzige betonte Karte).
            // Beim Laden ein form-gleicher Platzhalter, bei Fehler die kompakte Zeile — der Rest
            // darunter erscheint trotzdem.
            Reveal(MarketRevealSlot.PULSE, reveal, instant, motion, onNow = true) {
                CryptoPulseCard(pulse, plain = plain, onRetry = { viewModel.loadPulse(force = true) })
            }
            // 2. Darunter ruhig und kompakt: Fear & Greed (dieselbe Quelle wie auf «Stimmung & Zyklus») und
            // «Gewinner und Verlierer 24 h» (je drei, «Alle zeigen»; Tippen wie bei «Heute auffällig»)
            Reveal(MarketRevealSlot.FEAR_GREED, reveal, instant, motion, onNow = true) {
                FearGreedRow(
                    fearGreed,
                    onRetry = viewModel::refreshAll,
                    first = false,
                    stamp = stamps[CycleSource.FEAR_GREED],
                    compact = true
                )
                MarketMoversCard(pulse, onOpen = { coin -> onOpenCoin(coin.symbol) })
            }
            // 3. «Mehr Marktdaten» (Standard zu, gemerkt): «Heute auffällig», der Wirtschaftsdaten-Hinweis
            // (nur bei einem Termin in ±2 h, sonst unter «Daten») und die zwei Unterseiten — deren Daten
            // laden schon hier (Öffnen sofort)
            MarketMoreSection(
                reveal = reveal,
                instant = instant,
                motion = motion,
                showMore = showMore,
                onToggleMore = onToggleMore,
                unusual = unusual,
                fearGreed = fearGreed,
                macroEvents = macroEvents,
                macroAnchor = macroAnchor,
                onMacroHintHeight = onMacroHintHeight,
                onOpenCoin = onOpenCoin,
                onRetryUnusual = { viewModel.loadUnusual(force = true) },
                onOpenSection = onOpenSection,
            )
            // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis alles Sichtbare auf
            // «Jetzt» steht (verborgenes «Heute auffällig» zählt nicht)
            if (reveal.nowLoading(showMore)) RevealLoadingRow()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
