package com.cryptochecker.app.ui.components

import android.graphics.Bitmap
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
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import kotlinx.coroutines.flow.StateFlow

/** Woher CoinBadge die Logos nimmt (in der App: CoinLogoRepository). */
interface CoinLogoSource {
    /** Steigt, sobald neue Logos auf dem Gerät liegen. */
    val revision: StateFlow<Int>

    /** Sofort, ohne Datei und Netz; null = noch nicht im Speicher. */
    fun cached(symbol: String): Bitmap?

    /** Aus Speicher oder Datei (nie einzeln aus dem Netz); null = kein Logo, Initialen bleiben. */
    suspend fun load(symbol: String): Bitmap?
}

/**
 * Logo-Quelle der App. null (Schalter «In der App» aus, Vorschau, Tests): keine Plakette.
 */
val LocalCoinLogoSource = staticCompositionLocalOf<CoinLogoSource?> { null }

/** Logo-Quelle im Portfolio. null (Schalter «Im Portfolio» aus): keine Plakette. */
val LocalPortfolioCoinLogoSource = staticCompositionLocalOf<CoinLogoSource?> { null }

/**
 * Runde Coin-Plakette, nur mit eingeschaltetem Schalter «Coin-Logos» (App bzw. [portfolio]): echtes
 * Logo, sonst der Kreis mit den Initialen in der Akzentfarbe — nie ein kaputtes Bild. Schalter aus:
 * gar nichts (weder Logo noch Initialen, auch kein Platz). Feste Grösse, nimmt also nie Breite von
 * Kurs- oder Zahlenspalten. Dunkel: heller Grund hinter dem Logo. Rein dekorativ.
 *
 * [logo] = false (DEX-Pools, siehe [CoinLogos.allowedFor]): immer Initialen. [favorite]: kleiner
 * Stern unten rechts (Ring in [ringColor], der Farbe der Fläche darunter).
 */
@Composable
fun CoinBadge(
    coin: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    logo: Boolean = true,
    portfolio: Boolean = false,
    favorite: Boolean = false,
    ringColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val enabled = if (portfolio) LocalPortfolioCoinLogoSource.current else LocalCoinLogoSource.current
    // Schalter aus: weder Logo noch Platzhalter
    if (enabled == null) return
    val source = enabled.takeIf { logo }
    // Je Coin ein eigener Zustand: Zeilen werden wiederverwendet, nie das Logo des vorigen Coins
    val state = remember(coin, source) { mutableStateOf(source?.cached(coin)) }
    // Neue Logos auf dem Gerät (Abgleich im Hintergrund): fehlende erneut versuchen
    val revision = source?.revision?.collectAsState()?.value ?: 0
    LaunchedEffect(coin, source, revision) {
        if (state.value == null) state.value = source?.load(coin)
    }
    val bitmap = state.value
    Box(modifier = modifier.size(size).clearAndSetSemantics { }) {
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            val backing = if (LocalDarkTheme.current) MaterialTheme.colorScheme.onSurface.copy(alpha = LOGO_BACKING_DARK_ALPHA)
            else MaterialTheme.colorScheme.surfaceContainerHigh
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
        } else {
            InitialsCircle(coin, size)
        }
        if (favorite) FavoriteStar(size, ringColor, Modifier.align(Alignment.BottomEnd))
    }
}

/** Ersatz-Kreis: Akzentfarbe mit 14 % Tönung, Initialen in der Akzentfarbe. */
@Composable
private fun InitialsCircle(coin: String, size: Dp) {
    val accent = MaterialTheme.colorScheme.primary
    val initials = CoinLogos.initials(coin)
    // Schrift wächst mit der Plakette (40 dp → 13 bzw. 11), unabhängig von der Systemschrift,
    // damit vier Zeichen immer in den Kreis passen
    val fontSize = with(LocalDensity.current) { (size * if (initials.length <= 3) 0.33f else 0.275f).toSp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
    ) {
        Text(
            text = initials,
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

/** Favorit: kleiner Stern in der Akzentfarbe, mit Ring in der Farbe der Fläche darunter. */
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
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Heller Grund hinter Logos im Dunkelmodus (onSurface, leicht gedämpft). */
private const val LOGO_BACKING_DARK_ALPHA = 0.92f
