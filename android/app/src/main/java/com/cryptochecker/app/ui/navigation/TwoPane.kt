// Zwei Spalten im breiten Fenster (Tablet, Querformat): links die Startseite des Tabs, rechts die geöffnete Seite —
// wie iOS `SplitTab` (iPad). Teil von `AppNavHost`.
package com.cryptochecker.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.cryptochecker.resources.R

/** Wann zwei Spalten und wie breit die linke ist — reine Regeln (Test `TwoPanePolicyTest`). */
internal object TwoPanePolicy {
    /** Ab dieser Breite (dp) immer zwei Spalten (Tablet, aufgeklapptes Faltgerät). */
    const val EXPANDED_DP = 840

    /** Ab dieser Breite (dp) im Querformat zwei Spalten (Telefon quer, kleines Tablet). */
    const val MEDIUM_DP = 600

    /** Linke Spalte (dp): 40 % der Breite, zwischen [LIST_MIN_DP] und [LIST_MAX_DP] — wie iOS `SplitTabMetrics`. */
    const val LIST_MIN_DP = 340
    const val LIST_MAX_DP = 520

    fun wide(widthDp: Int, heightDp: Int): Boolean = widthDp >= EXPANDED_DP || (widthDp >= MEDIUM_DP && widthDp > heightDp)

    fun listWidthDp(widthDp: Int): Int = (widthDp * 2 / 5).coerceIn(LIST_MIN_DP, LIST_MAX_DP)
}

/** Diese Seite steht im breiten Fenster rechts neben einer Startseite (die Startseite selbst zeigt dann den Platzhalter). */
internal val LocalTwoPane = staticCompositionLocalOf { false }

/** Diese Startseite steht in der linken Spalte: Seiten, die sie öffnet, ersetzen die rechte Spalte. */
internal val LocalListPane = staticCompositionLocalOf { false }

/** Seite [route] rechts zeigen: was über der Startseite [root] lag, schliesst sich zuerst (keine Stapel daneben). */
internal fun NavHostController.replaceDetail(root: NavBackStackEntry, route: ScreenRoute) {
    navigate(route) {
        popUpTo(root.destination.id) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Startseite für die linke Spalte, oder null (eine Spalte): schmal; «Paar hinzufügen» (eigene breite Seite); das
 * Portfolio nur, solange es selbst bzw. eine seiner Seiten offen ist (dort gelten Sperre und Bildschirmschutz).
 * Unterseiten gehören zum Tab, über dem sie liegen: Markt, Portfolio oder Einstellungen, sonst die Merkliste (sie ist
 * als Startziel immer im Stapel).
 */
internal fun listPaneRoot(navigation: NavHostController, current: NavDestination?, wide: Boolean): NavBackStackEntry? {
    if (!wide || current == null || current.hasRoute<ScreenRoute.Explorer>()) return null
    val onRoot = isTabRoot(current)
    val root = if (onRoot) {
        navigation.currentBackStackEntry
    } else {
        tabRootEntry<ScreenRoute.MarketPhase>(navigation)
            ?: tabRootEntry<ScreenRoute.Portfolio>(navigation)
            ?: tabRootEntry<ScreenRoute.Settings>(navigation)
            ?: tabRootEntry<ScreenRoute.Watchlist>(navigation)
    } ?: return null
    val portfolioOpen = current.hasRoute<ScreenRoute.Portfolio>() || current.hasRoute<ScreenRoute.PortfolioCoin>() ||
        current.hasRoute<ScreenRoute.PortfolioTaxWorksheet>()
    if (root.destination.hasRoute<ScreenRoute.Portfolio>() && !portfolioOpen) return null
    return root
}

internal fun isTabRoot(destination: NavDestination): Boolean = destination.hasRoute<ScreenRoute.Watchlist>() ||
    destination.hasRoute<ScreenRoute.MarketPhase>() || destination.hasRoute<ScreenRoute.Portfolio>() ||
    destination.hasRoute<ScreenRoute.Settings>()

private inline fun <reified T : ScreenRoute> tabRootEntry(navigation: NavHostController): NavBackStackEntry? =
    runCatching { navigation.getBackStackEntry<T>() }.getOrNull()

/** Ist das Fenster gerade breit genug für zwei Spalten? */
@Composable
internal fun rememberTwoPane(): Boolean {
    val c = LocalConfiguration.current
    return TwoPanePolicy.wide(c.screenWidthDp, c.screenHeightDp)
}

/** Breite der linken Spalte für dieses Fenster. */
@Composable
internal fun listPaneWidth() = TwoPanePolicy.listWidthDp(LocalConfiguration.current.screenWidthDp).dp

/**
 * Linke Spalte: die Startseite [root] mit dem ViewModel-Besitzer ihres Eintrags (dieselben Instanzen wie im Graphen) und
 * dem Lebenszyklus der Activity (sie bleibt sichtbar und aktuell, auch während rechts eine Seite offen ist). Eigener
 * Schlüssel je Eintrag: Scroll-Stand bleibt beim Wechsel der rechten Seite.
 */
@Composable
internal fun ListPane(root: NavBackStackEntry, args: TabRootArgs) {
    Box(Modifier.width(listPaneWidth()).fillMaxHeight()) {
        key(root.id) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides root, LocalListPane provides true) {
                TabRoot(root, args)
            }
        }
    }
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Rechte Spalte ohne geöffnete Seite: App-Hintergrund mit dezentem Zeichen, ohne Text. */
@Composable
internal fun TwoPanePlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_two_pane),
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
