// Tableiste unten (Merkliste · Markt · Portfolio · Einstellungen): Tabs je nach Portfolio-Schalter, Masse,
// Icon-Skalierung, Beschriftung mit Obergrenze der Schriftgrösse.
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.LocalDarkTheme

internal data class BottomTab(
    val route: String,
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
private val TAB_BAR_HEIGHT = 64.dp

/** Grösse der Tab-Symbole: die üblichen 24 dp (wie iOS). */
private val TAB_ICON_SIZE = 24.dp

/** Kleinste Schrift der Tab-Beschriftung (sp). */
private const val TAB_LABEL_MIN_SP = 11f

/**
 * Die Beschriftung wächst höchstens bis zur 1,3-fachen Schrift (wie iOS bis `.xxxLarge` und wie
 * `NavigationBar`): Sonst würde sie bei Schriftgrösse 200 % unten abgeschnitten bzw. die Leiste fräße
 * auf jedem Tab Inhaltshöhe. Den vollen Namen zeigt der Tooltip beim langen Drücken.
 */
private const val TAB_LABEL_MAX_SCALE = 1.3f

/** Das Symbol des gewählten Tabs ist 10 % grösser (wie iOS). */
private const val TAB_ICON_SELECTED_SCALE = 1.1f

/** Dauer der Vergrösserung beim Wechsel des Tabs (wie iOS). */
private const val TAB_ICON_SCALE_MILLIS = 150

/** Anzeiger (Pille) hinter dem Symbol des gewählten Tabs: Material-3-Mass 64 × 32 dp. */
private val TAB_INDICATOR_WIDTH = 64.dp

private val TAB_INDICATOR_HEIGHT = 32.dp

/** Pille hell: Deckkraft der Akzentfarbe; dunkel: neutrales Grau (Kontrastwerte beim Anzeiger in [AppTabBar]). */
private const val TAB_INDICATOR_LIGHT_ALPHA = 0.30f
private val TAB_INDICATOR_DARK = Color(0xFF404040)

/**
 * Eigene Tableiste statt `NavigationBar`. Fläche surfaceContainer wie zuvor, darunter der Abstand
 * zur Systemleiste. Symbole 24 dp; gewählt: das Symbol in der Themenfarbe auf dem Material-3-Anzeiger
 * (Pille 64 × 32 dp, hell Akzent 30 %, dunkel #404040 — Review D11) und kurz animiert
 * um 10 % vergrössert, sonst grau;
 * die Beschriftung bleibt immer gleich (dunkel: weiss, hell: onSurface). Für den Screenreader je
 * ein Tab mit «ausgewählt». Gleiches Aussehen wie `AppTabBar` (iOS).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTabBar(tabs: List<BottomTab>, currentRoute: String?, onSelect: (String) -> Unit) {
    val labelColor = if (LocalDarkTheme.current) Color.White else MaterialTheme.colorScheme.onSurface
    val reduceMotion = rememberReduceMotion()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
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
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = TAB_BAR_HEIGHT)
                                .testTag(tab.testTag)
                                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.route) })
                                // 2 dp statt 4 dp: lange Beschriftungen (z. B. «Einstellungen») bleiben bei 11 sp ganz
                                .padding(horizontal = 2.dp)
                        ) {
                            // Anzeiger: blendet beim Wechsel ein und wächst aus der Mitte (wie NavigationBarItem)
                            val indicator by animateFloatAsState(
                                targetValue = if (selected) 1f else 0f,
                                animationSpec = tween(if (reduceMotion) 0 else TAB_ICON_SCALE_MILLIS),
                                label = "tabIndicator"
                            )
                            // Pille deutlich sichtbar (Review D11; vorher secondaryContainer: hell 1.11:1, dunkel 1.30:1).
                            // Hell: Akzent zu 30 % auf surfaceContainer #F1EEE9 → Pille/Leiste 1.52–1.59:1, Symbol
                            // (Akzent) auf der Pille 3.18–3.33:1. Dunkel: #404040 auf #1F1F1F → 1.59:1, Symbol 3.88–4.44:1.
                            // Gewählt bleibt zudem an Akzentsymbol und fetter Beschriftung erkennbar. Gleiche Werte wie iOS.
                            val indicatorColor = if (LocalDarkTheme.current) TAB_INDICATOR_DARK
                            else MaterialTheme.colorScheme.primary.copy(alpha = TAB_INDICATOR_LIGHT_ALPHA)
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
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
 * Tab-Beschriftung auf einer Zeile (labelMedium, 12 sp): Lange Wörter (Übersetzungen) werden auf
 * schmalen Geräten verkleinert, aber nie unter 11 sp (vorher 9 sp — kaum lesbar); notfalls «…».
 * Beide Grenzen wachsen nur bis [TAB_LABEL_MAX_SCALE] mit der Schriftgrösse mit.
 * Gewählt fett, damit der Tab nicht nur am Farbton erkennbar ist.
 */
@Composable
private fun TabLabel(text: String, color: Color, selected: Boolean) {
    val style = MaterialTheme.typography.labelMedium
    val baseSize = if (style.fontSize.isSp && style.fontSize.value >= TAB_LABEL_MIN_SP) style.fontSize else 12.sp
    BasicText(
        text = text,
        style = style.copy(color = color, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = cappedTabSize(TAB_LABEL_MIN_SP.sp),
            maxFontSize = cappedTabSize(baseSize),
            stepSize = 0.5.sp
        ),
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * [size] (sp), aber nie grösser gezeichnet als [size] bei 1,3-facher Schrift ([TAB_LABEL_MAX_SCALE]).
 * Über Pixel verglichen, weil Android grosse Schriftstufen nicht linear umrechnet.
 */
@Composable
private fun cappedTabSize(size: TextUnit): TextUnit = with(LocalDensity.current) {
    minOf(size.toPx(), (size.value * TAB_LABEL_MAX_SCALE).dp.toPx()).toSp()
}
