package com.cryptochecker.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.logos.CoinLogoPlaceholder
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Woher CoinBadge die Logos nimmt (in der App: CoinLogoRepository). */
interface CoinLogoSource {
    /** Steigt, sobald neue Logos auf dem Gerät liegen. */
    val revision: StateFlow<Int>

    /** TradFi-Paare der Merkliste ([CoinLogos.pairKey]); ihr Logo nie aus CoinGecko. */
    val tradFiPairs: StateFlow<Set<String>>

    /** Namen («BTC» → «Bitcoin», «TRADFI:NVDA» → «NVIDIA»), Schlüssel [CoinLogos.nameKey]. */
    val names: StateFlow<Map<String, String>>

    /** Sofort, ohne Datei und Netz; null = noch nicht im Speicher. */
    fun cached(symbol: String): Bitmap?

    /** Aus Speicher oder Datei (nie einzeln aus dem Netz); null = kein Logo, Initialen bleiben. */
    suspend fun load(symbol: String): Bitmap?

    /** Gespeicherte [names] laden, nur aus der Datei (für die Suche über den Namen). */
    suspend fun loadSavedNames() {}

    /** Logos laden gerade nach der Installation bzw. dem Leeren des Zwischenspeichers (Hinweis über der Merkliste). */
    val loading: StateFlow<Boolean> get() = NOT_LOADING

    /** Fortschritt dieses Ladens in Prozent (gedrosselt); null = ohne Prozent (Gesamtzahl noch offen, Server bremst). */
    val loadingPercent: StateFlow<Int?> get() = NO_PERCENT

    /** Das erste Laden der Logos ist erledigt (vorher kein Hinweis «Coins ohne Logo»); liest eine Datei. */
    suspend fun firstLoadDone(): Boolean = true

    /** Liegt für [symbol] ein Logo auf dem Gerät (geladen oder mitgeliefert)? Nie Netz. */
    suspend fun hasLogo(symbol: String): Boolean = load(symbol) != null

    /** Beim ✕ des Hinweises «Coins ohne Logo» gemerkte Coins; null = noch nicht gelesen ([loadMissingDismissed]). */
    val missingDismissed: StateFlow<Set<String>?> get() = NO_DISMISSED

    suspend fun loadMissingDismissed() {}

    /** ✕ am Hinweis «Coins ohne Logo»: diese Coins merken. */
    fun dismissMissing(keys: Set<String>) {}
}

/** [CoinLogoSource.loading] ohne Abgleich (Vorschau, Tests). */
private val NOT_LOADING: StateFlow<Boolean> = MutableStateFlow(false)
private val NO_PERCENT: StateFlow<Int?> = MutableStateFlow(null)
private val NO_DISMISSED: StateFlow<Set<String>?> = MutableStateFlow(emptySet())

/**
 * Logo-Quelle der App. null (Schalter «In der App» aus, Vorschau, Tests): keine Plakette.
 */
val LocalCoinLogoSource = staticCompositionLocalOf<CoinLogoSource?> { null }

/** Logo-Quelle im Portfolio. null (Schalter «Im Portfolio» aus): keine Plakette. */
val LocalPortfolioCoinLogoSource = staticCompositionLocalOf<CoinLogoSource?> { null }

/** Namen für die Merkliste. null (Schalter «Namen anzeigen» aus): kein Name. */
val LocalCoinNameSource = staticCompositionLocalOf<CoinLogoSource?> { null }

/** Namen für die Suche über den Namen — unabhängig vom Schalter «Namen anzeigen»; null in Vorschau/Tests. */
val LocalCoinSearchNameSource = staticCompositionLocalOf<CoinLogoSource?> { null }

/**
 * Name des Coins bzw. der Firma eines Paars («Bitcoin», «NVIDIA»); null bei ausgeschaltetem
 * Schalter, DEX-Pools ([CoinLogos.allowedFor]) und unbekannten Coins. TradFi-Paare nie mit dem
 * Namen eines gleichnamigen Krypto-Tokens.
 */
@Composable
fun coinName(exchangeKey: String, base: String, quote: String, contractType: String): String? {
    val source = LocalCoinNameSource.current ?: return null
    if (!CoinLogos.allowedFor(exchangeKey)) return null
    val names = source.names.collectAsState().value
    val tradFi = CoinLogos.pairKey(exchangeKey, base, quote, contractType) in source.tradFiPairs.collectAsState().value
    return names[CoinLogos.nameKey(base, tradFi)]
}

/**
 * Runde Coin-Plakette, nur mit eingeschaltetem Schalter «Coin-Logos» (App bzw. [portfolio]): echtes
 * Logo, sonst der farbige Ersatz-Kreis mit den Initialen ([CoinLogoPlaceholder], Farbe fest aus dem Kürzel) — nie ein
 * kaputtes Bild. Kommt das Logo erst später (Abgleich im Hintergrund), blendet es über dem Kreis ein
 * ([CoinLogoPlaceholder.FADE_MILLIS]; ohne Animation bei «Animationen entfernen», [rememberReduceMotion]). Schalter aus:
 * gar nichts (weder Logo noch Initialen, auch kein Platz). Feste Grösse, nimmt also nie Breite von
 * Kurs- oder Zahlenspalten. Dunkel: heller Grund hinter dem Logo. Rein dekorativ.
 *
 * [logo] = false (DEX-Pools, siehe [CoinLogos.allowedFor]): immer Initialen. [pair]: Kennung des
 * Paars ([CoinLogos.pairKey]); ist es TradFi, kommt das Logo aus dem TradFi-Namensraum (nie ein
 * gleichnamiger Krypto-Token). [favorite]: kleiner
 * Stern unten rechts (Ring in [ringColor], der Farbe der Fläche darunter).
 */
@Composable
fun CoinBadge(
    coin: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    logo: Boolean = true,
    pair: String? = null,
    portfolio: Boolean = false,
    favorite: Boolean = false,
    ringColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val enabled = if (portfolio) LocalPortfolioCoinLogoSource.current else LocalCoinLogoSource.current
    // Schalter aus: weder Logo noch Platzhalter
    if (enabled == null) return
    val source = enabled.takeIf { logo }
    val tradFiPairs = source?.tradFiPairs?.collectAsState()?.value
    val tradFi = pair != null && tradFiPairs != null && pair in tradFiPairs
    val key = CoinLogos.logoKey(coin, tradFi)
    // Je Coin ein eigener Zustand: Zeilen werden wiederverwendet, nie das Logo des vorigen Coins
    val state = remember(key, source) { mutableStateOf(source?.cached(key)) }
    // Logo schon beim Zeichnen da: sofort zeigen; kommt es später: einblenden
    val shown = remember(key, source) { Animatable(if (state.value != null) 1f else 0f) }
    val reduceMotion = rememberReduceMotion()
    // Neue Logos auf dem Gerät (Abgleich im Hintergrund): fehlende erneut versuchen
    val revision = source?.revision?.collectAsState()?.value ?: 0
    LaunchedEffect(key, source, revision) {
        if (state.value == null) state.value = source?.load(key)
    }
    val bitmap = state.value
    LaunchedEffect(shown, bitmap != null, reduceMotion) {
        if (bitmap == null) return@LaunchedEffect
        if (reduceMotion) shown.snapTo(1f) else shown.animateTo(1f, tween(CoinLogoPlaceholder.FADE_MILLIS))
    }
    Box(modifier = modifier.size(size).clearAndSetSemantics { }) {
        // Ersatz-Kreis bleibt unter dem einblendenden Logo, bis es ganz da ist
        if (bitmap == null || shown.value < 1f) PlaceholderCircle(coin, size)
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            val backing = if (LocalDarkTheme.current) MaterialTheme.colorScheme.onSurface.copy(alpha = LOGO_BACKING_DARK_ALPHA)
            else MaterialTheme.colorScheme.surfaceContainerHigh
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = shown.value }
                    .clip(CircleShape)
                    .background(backing)
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (favorite) FavoriteStar(size, ringColor, Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * Ersatz-Kreis ([CoinLogoPlaceholder]): Füllung fest aus dem Kürzel, Initialen weiss (hell) bzw. fast schwarz
 * (dunkel), mindestens 4.5:1 auf jeder Füllung — auch für TradFi ohne Logo und DEX-Pools.
 */
@Composable
private fun PlaceholderCircle(coin: String, size: Dp) {
    val dark = LocalDarkTheme.current
    val initials = CoinLogos.initials(coin)
    // Schrift wächst mit der Plakette (40 dp → 13 bzw. 11), unabhängig von der Systemschrift,
    // damit vier Zeichen immer in den Kreis passen
    val fontSize = with(LocalDensity.current) { (size * if (initials.length <= 3) 0.33f else 0.275f).toSp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Color(CoinLogoPlaceholder.fill(coin, dark)))
    ) {
        Text(
            text = initials,
            color = Color(CoinLogoPlaceholder.text(dark)),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

/** Favorit: kleiner Stern, weiss (Textfarbe) bzw. in der Themenfarbe, solange der ★-Chip gewählt ist ([LocalRowHighlight]); Ring in der Farbe der Fläche darunter. */
@Composable
private fun FavoriteStar(badgeSize: Dp, ringColor: Color, modifier: Modifier) {
    val starSize = maxOf(12.dp, badgeSize * 0.45f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset(x = 3.dp, y = 3.dp)
            .size(starSize)
            .clip(CircleShape)
            .background(ringColor)
            .padding(1.5.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_star),
            contentDescription = null,
            tint = if (LocalRowHighlight.current.favorites) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Heller Grund hinter Logos im Dunkelmodus (onSurface, leicht gedämpft). */
private const val LOGO_BACKING_DARK_ALPHA = 0.92f
