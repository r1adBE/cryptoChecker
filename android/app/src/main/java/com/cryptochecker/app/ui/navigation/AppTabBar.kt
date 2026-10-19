// Tableiste unten (Merkliste · Markt · Portfolio · Einstellungen): Tabs je nach Portfolio-Schalter, Masse,
// Icon-Skalierung, Beschriftung (bis zwei Zeilen, Silbentrennung, bei Platzmangel kleiner, nie unter 12 sp) mit
// Obergrenze der Schriftgrösse.
// Eigene Datei, weil die Leiste eine eigenständige Ansicht ist; `AppNavHost` regelt nur die Navigation
// (Aussehen wie `AppTabBar` in iOS `RootView.swift`).
package com.cryptochecker.app.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.LocalDarkTheme

internal data class BottomTab(
    val route: ScreenRoute,
    val labelRes: Int,
    val iconRes: Int,
    /** Kennung für UI-Tests ([UiTestTags]), wie iOS `AppTabBarButton`. */
    val testTag: String,
)

/**
 * Merkliste · Markt · (Portfolio) · Einstellungen (Runde 31: ohne Tab «Suchen»).
 * Der Portfolio-Tab erscheint nur, wenn er in den Optionen eingeschaltet ist. Paare sucht und
 * fügt man auf einer eigenen Seite hinzu ([ScreenRoute.Explorer]), geöffnet mit «+» der Merkliste.
 */
internal fun buildBottomTabs(portfolioEnabled: Boolean) = buildList {
    add(BottomTab(ScreenRoute.Watchlist, R.string.tab_watchlist, R.drawable.ic_tab_watchlist, UiTestTags.TAB_WATCHLIST))
    add(BottomTab(ScreenRoute.MarketPhase, R.string.tab_market_phase, R.drawable.ic_tab_market, UiTestTags.TAB_CYCLE))
    if (portfolioEnabled) add(BottomTab(ScreenRoute.Portfolio, R.string.tab_portfolio, R.drawable.ic_portfolio, UiTestTags.TAB_PORTFOLIO))
    add(BottomTab(ScreenRoute.Settings, R.string.tab_settings, R.drawable.ic_settings, UiTestTags.TAB_SETTINGS))
}

/** Mindesthöhe der Tableiste ohne Systemleiste (wie `AppTabBarMetrics.height` in iOS). */
private val TAB_BAR_HEIGHT = 66.dp

/** Grösse der Tab-Symbole: die üblichen 24 dp (wie iOS). */
private val TAB_ICON_SIZE = 24.dp

/** Kleinste Schrift der Tab-Beschriftung (sp): darunter wird nie verkleinert, lange Namen brechen um. */
private const val TAB_LABEL_MIN_SP = 12f

/** Lange Beschriftungen (Übersetzungen) dürfen auf zwei Zeilen umbrechen statt «…». */
private const val TAB_LABEL_MAX_LINES = 2

/**
 * Die Beschriftung wächst höchstens bis zur 1,5-fachen Schrift (wie iOS bis `.accessibility1`):
 * Sonst frässe die Leiste bei Schriftgrösse 200 % auf jedem Tab Inhaltshöhe. Den vollen Namen zeigt
 * der Tooltip beim langen Drücken.
 */
private const val TAB_LABEL_MAX_SCALE = 1.5f

/** Luft über dem Symbol und unter der Beschriftung (Pille 32 + 2 + Zeile 16 + 2 × 8 = 66 dp, wie iOS). */
private val TAB_VERTICAL_PADDING = 8.dp

/** Seitlicher Rand je Tab (wie iOS). */
private val TAB_HORIZONTAL_PADDING = 4.dp

/** Das Symbol des gewählten Tabs ist 10 % grösser (wie iOS). */
private const val TAB_ICON_SELECTED_SCALE = 1.1f

/** Dauer der Vergrösserung beim Wechsel des Tabs (wie iOS). */
private const val TAB_ICON_SCALE_MILLIS = 150

/** Anzeiger (Pille) hinter dem Symbol des gewählten Tabs: Material-3-Mass 64 × 32 dp. */
private val TAB_INDICATOR_WIDTH = 64.dp

private val TAB_INDICATOR_HEIGHT = 32.dp

/**
 * Eigene Tableiste statt `NavigationBar`. Fläche surfaceContainer wie zuvor, darunter der Abstand
 * zur Systemleiste. Symbole 24 dp; gewählt: das Symbol in der Themenfarbe auf dem Material-3-Anzeiger
 * (Pille 64 × 32 dp in der vollen Akzentfarbe, Symbol darauf onAccent) und kurz animiert
 * um 10 % vergrössert, sonst grau; die Beschriftung bleibt immer gleich (dunkel: weiss,
 * hell: onSurface), gewählt fett. Lange Namen brechen auf zwei Zeilen um. Für den Screenreader je
 * ein Tab mit «ausgewählt». Gleiches Aussehen wie `AppTabBar` (iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTabBar(tabs: List<BottomTab>, currentRoute: ScreenRoute?, onSelect: (ScreenRoute) -> Unit) {
    val labelColor = if (LocalDarkTheme.current) Color.White else MaterialTheme.colorScheme.onSurface
    val reduceMotion = rememberReduceMotion()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            // Oben ausgerichtet: bricht eine Beschriftung auf zwei Zeilen um, bleiben alle Symbole auf einer Höhe
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                // Mindesthöhe statt fester Höhe: nichts wird abgeschnitten, falls die Zeile doch höher wird
                .heightIn(min = TAB_BAR_HEIGHT)
                .selectableGroup()
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) TAB_ICON_SELECTED_SCALE else 1f,
                    animationSpec = tween(if (reduceMotion) 0 else TAB_ICON_SCALE_MILLIS),
                    label = "tabIconScale"
                )
                val label = stringResource(tab.labelRes)
                // Eigene Box je Tab trägt die gleiche Breite (weight): TooltipBox gibt einen Gewichts-Modifier nicht an die
                // Zeile weiter – sonst nimmt der erste Tab die ganze Breite und die übrigen werden 0 breit (Build 58)
                Box(modifier = Modifier.weight(1f)) {
                    // Langes Drücken zeigt den Namen gross (Gegenstück zum Large Content Viewer in iOS)
                    TooltipBox(
                        // Bewusst noch die ältere Positionierung: der Nachfolger rememberTooltipPositionProvider ändert
                        // je nach Material3-Version seine Parameter; ein Wechsel erst mit Gerätetest der Tableiste
                        positionProvider = @Suppress("DEPRECATION") TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(label) } },
                        state = rememberTooltipState(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = TAB_BAR_HEIGHT)
                                .testTag(tab.testTag)
                                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.route) })
                                .padding(horizontal = TAB_HORIZONTAL_PADDING, vertical = TAB_VERTICAL_PADDING)
                        ) {
                            // Anzeiger: blendet beim Wechsel ein und wächst aus der Mitte (wie NavigationBarItem)
                            val indicator by animateFloatAsState(
                                targetValue = if (selected) 1f else 0f,
                                animationSpec = tween(if (reduceMotion) 0 else TAB_ICON_SCALE_MILLIS),
                                label = "tabIndicator"
                            )
                            // Pille in der vollen Akzentfarbe, damit sie sich mit ≥ 3:1 von der Leiste abhebt
                            // (vorher Akzent 30 % bzw. #404040: nur 1.5–1.6:1). Hell primary auf surfaceContainer
                            // #F1EEE9 → 5.04–5.09:1, Symbol Weiss darauf 5.84–5.89:1; dunkel primary auf #1F1F1F →
                            // 6.17–7.05:1, Symbol onPrimary darauf 6.15–6.70:1 (TabIndicatorContrastTest). Gewählt
                            // zudem an fetter Beschriftung erkennbar. Gleiche Werte wie iOS.
                            val indicatorColor = MaterialTheme.colorScheme.primary
                            Box(
                                contentAlignment = Alignment.Center,
                                // Schmale Tabs (viele Tabs, grosse Schrift): Pille höchstens so breit wie der Tab
                                modifier = Modifier.widthIn(max = TAB_INDICATOR_WIDTH).fillMaxWidth().height(TAB_INDICATOR_HEIGHT)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .graphicsLayer {
                                            alpha = indicator
                                            scaleX = 0.5f + 0.5f * indicator
                                        }
                                        .background(indicatorColor, CircleShape)
                                )
                                Icon(
                                    painterResource(tab.iconRes),
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(TAB_ICON_SIZE)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        }
                                )
                            }
                            TabLabel(label, labelColor, selected)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab-Beschriftung (labelMedium, 12 sp): lange Wörter (Übersetzungen) brechen auf schmalen Geräten
 * auf zwei Zeilen um, ein zu langes Einzelwort mit Silbentrennung («Einstel-lungen») statt mitten im
 * Wort. Schrift und Zeilenhöhe wachsen bis [TAB_LABEL_MAX_SCALE] mit der Schriftgrösse mit; reichen
 * bei grosser Schrift auch zwei Zeilen nicht, wird die Schrift schrittweise wieder kleiner (nie kleiner
 * als bei normaler Schriftgrösse) statt «…» zu zeigen — «…» nur, wenn selbst das nicht reicht.
 * Gewählt fett, damit der Tab nicht nur am Farbton erkennbar ist.
 */
@Composable
private fun TabLabel(text: String, color: Color, selected: Boolean) {
    val style = MaterialTheme.typography.labelMedium
    val baseSize = if (style.fontSize.isSp && style.fontSize.value >= TAB_LABEL_MIN_SP) style.fontSize else TAB_LABEL_MIN_SP.sp
    val baseLine = if (style.lineHeight.isSp) style.lineHeight else 16.sp
    val maxSize = cappedTabSize(baseSize)
    // Untergrenze beim Verkleinern: 12 sp bei normaler Schriftgrösse (über dp, sonst wüchse sie mit der
    // Schriftgrösse mit) — kleiner als ohne grosse Schrift wird die Beschriftung nie
    val minSize = with(LocalDensity.current) { TAB_LABEL_MIN_SP.dp.toSp() }
    BasicText(
        text = text,
        style = style.copy(
            color = color,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = maxSize,
            // Zeilenhöhe relativ zur Schrift (em), damit sie beim Verkleinern mitgeht
            lineHeight = (baseLine.value / baseSize.value).em,
            textAlign = TextAlign.Center,
            hyphens = Hyphens.Auto,
        ),
        maxLines = TAB_LABEL_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        autoSize = if (maxSize.value > minSize.value) {
            TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 0.5.sp)
        } else null,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * [size] (sp), aber nie grösser gezeichnet als [size] bei 1,5-facher Schrift ([TAB_LABEL_MAX_SCALE]).
 * Über Pixel verglichen, weil Android grosse Schriftstufen nicht linear umrechnet.
 */
@Composable
internal fun cappedTabSize(size: TextUnit): TextUnit = with(LocalDensity.current) {
    minOf(size.toPx(), (size.value * TAB_LABEL_MAX_SCALE).dp.toPx()).toSp()
}
