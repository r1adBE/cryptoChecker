package com.cryptochecker.app.ui.features.info

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Thema eines Info-Blatts im Markt-Tab: Titel der Karte, «Was ist das?», «So liest du es»,
 * Quelle mit Aktualisierungs-Rhythmus und ob der Hinweis «Keine Anlageberatung» dazugehört
 * ([advice], bei allem, was den Markt deutet). [readingRes] (Build 55): Zeilen «hoch heisst …,
 * tief heisst …» zu Werten der Karte, die «So liest du es» noch nicht erklärt (Hebel, Funding,
 * Altcoins). Wie `MarketInfoTopic` (iOS, MarketRow.swift).
 */
internal enum class MarketInfoTopic(
    @param:StringRes val titleRes: Int,
    @param:StringRes val whatRes: Int,
    @param:StringRes val howRes: Int,
    @param:StringRes val sourceRes: Int,
    val advice: Boolean,
    val readingRes: List<Int> = emptyList(),
) {
    PULSE(R.string.pulse_now_title, R.string.market_info_pulse_what, R.string.market_info_pulse_how, R.string.market_info_pulse_source, true,
        listOf(R.string.ind_read_leverage)),
    UNUSUAL(R.string.unusual_title, R.string.market_info_unusual_what, R.string.market_info_unusual_how, R.string.market_info_unusual_source, true,
        listOf(R.string.ind_read_funding)),
    MACRO(R.string.macro_title, R.string.market_info_macro_what, R.string.market_info_macro_how, R.string.market_info_macro_source, true),
    FEAR_GREED(R.string.insights_fng_title, R.string.market_info_fear_greed_what, R.string.market_info_fear_greed_how, R.string.market_info_fear_greed_source, true),
    PHASE(R.string.neutral_btc_indicators_title, R.string.neutral_info_btc_what, R.string.neutral_info_btc_how, R.string.market_info_phase_source, true),
    DOMINANCE(R.string.insights_dominance_title, R.string.market_info_dominance_what, R.string.market_info_dominance_how, R.string.market_info_dominance_source, true),
    ALT_SEASON(R.string.neutral_alt_title, R.string.market_info_alt_season_what, R.string.neutral_info_alt_season_how, R.string.market_info_alt_season_source, true,
        listOf(R.string.ind_read_alt_season)),
    HALVING(R.string.insights_halving_title, R.string.market_info_halving_what, R.string.neutral_info_halving_how, R.string.market_info_halving_source, true),
    TOTALS(R.string.market_cap_title, R.string.market_info_totals_what, R.string.market_info_totals_how, R.string.market_info_totals_source, false),
    GAS(R.string.gas_title, R.string.market_info_gas_what, R.string.market_info_gas_how, R.string.market_info_gas_source, false),
    COIN(R.string.coin_title, R.string.neutral_info_coin_what, R.string.neutral_info_coin_how, R.string.market_info_coin_source, true),
    ;

    /** Zeigt CoinGecko-Daten → «Powered by CoinGecko» im Blatt ([CoinGeckoAttribution]). Wie iOS. */
    val usesCoinGecko: Boolean get() = this == PULSE || this == UNUSUAL || this == DOMINANCE || this == TOTALS
}

/** Platz, den das Info-Zeichen in der Titelzeile belegt (die Tippfläche ragt darüber hinaus). */
private val INFO_SLOT_WIDTH = 28.dp
private val INFO_SLOT_HEIGHT = 20.dp

/** Mindest-Tippfläche des Info-Zeichens. */
private val INFO_TOUCH = 48.dp

/**
 * Kleines «ⓘ» direkt hinter dem Titel einer Karte (18 dp, Nebenfarbe). Belegt in der Zeile nur
 * [INFO_SLOT_WIDTH] × [INFO_SLOT_HEIGHT] — die Titelzeile wird nicht höher —, die Tippfläche ist
 * trotzdem 48 dp gross und ragt nach oben und unten in den Innenabstand der Karte.
 * Tippen öffnet das Info-Blatt ([MarketInfoSheet]) und klappt die Karte nicht auf (der Knopf
 * verbraucht den Tipp). Screenreader: eigener Knopf «Info: <Titel>».
 */
@Composable
internal fun MarketInfoButton(topic: MarketInfoTopic, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.market_info_a11y, stringResource(topic.titleRes))
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .infoSlot()
            .size(INFO_TOUCH)
            .clip(CircleShape)
            .clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = label }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
    if (open) MarketInfoSheet(topic, onDismiss = { open = false })
}

/** Meldet nach aussen nur die kleine Grösse des Platzes; der 48-dp-Knopf liegt mittig darüber. */
private fun Modifier.infoSlot(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = INFO_SLOT_WIDTH.roundToPx()
    val height = maxOf(INFO_SLOT_HEIGHT.roundToPx(), constraints.minHeight)
    layout(width, height) {
        placeable.placeRelative((width - placeable.width) / 2, (height - placeable.height) / 2)
    }
}

/**
 * Kleines Blatt zu einer Karte: Titel (Überschrift), «Was ist das?», «So liest du es» (mit den
 * Zeilen «hoch heisst …» des Themas), eine graue Zeile mit Quelle und Rhythmus, bei Deutungen «Keine Anlageberatung», unten «Schliessen».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketInfoSheet(topic: MarketInfoTopic, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = Spacing.lg)
        ) {
            Text(
                stringResource(topic.titleRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }
            )
            MarketInfoSection(stringResource(R.string.market_info_what), stringResource(topic.whatRes))
            MarketInfoSection(stringResource(R.string.market_info_how), stringResource(topic.howRes))
            // «hoch heisst …, tief heisst …» direkt unter «So liest du es»
            topic.readingRes.forEach { res ->
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            Text(
                stringResource(topic.sourceRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.lg)
            )
            if (topic.usesCoinGecko) CoinGeckoAttribution()
            if (topic.advice) {
                Text(
                    stringResource(R.string.market_info_no_advice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            TextButton(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
                modifier = Modifier.align(Alignment.End).padding(top = Spacing.sm)
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/** Kleine Zwischenzeile (halbfett) und zwei bis drei kurze Sätze darunter. */
@Composable
private fun MarketInfoSection(label: String, text: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = Spacing.lg)
    )
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = Spacing.xs)
    )
}
