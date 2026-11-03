package com.cryptochecker.app.ui.features.explorer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.logos.CoinLogoPlaceholder
import com.cryptochecker.app.domain.market.ExchangeLogos
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.ui.theme.LocalDarkTheme

/**
 * Zeichen einer Börse in den Listen: echtes Logo ([ExchangeLogos], vom Gerät über [LocalCoinLogoSource] — nur mit
 * Schalter «In der App»), DexScreener und EZB-Referenzkurse ein festes Symbol ([ExchangeLogos.Symbol]), sonst und
 * solange das Logo lädt oder fehlt der farbige Kreis mit den Anfangsbuchstaben ([ExchangeMark]; Farben wie die
 * Ersatz-Kreise der Coins, [CoinLogoPlaceholder], mindestens 4.5:1). Rein dekorativ (der Name steht daneben).
 * Wie iOS `ExplorerExchangeMarkView`.
 */
@Composable
internal fun ExchangeMarkBadge(key: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    val symbol = ExchangeLogos.SYMBOLS[key]
    Box(modifier = modifier.size(size).clip(CircleShape).clearAndSetSemantics { }) {
        if (symbol != null) {
            SymbolMark(symbol, size)
        } else {
            val bitmap = exchangeLogo(key)
            if (bitmap == null) LetterMark(name, size) else LogoMark(bitmap)
        }
    }
}

/** Geladenes Logo der Börse vom Gerät; null = Schalter aus, keins zugeordnet oder (noch) nicht da. */
@Composable
private fun exchangeLogo(key: String): Bitmap? {
    val source = LocalCoinLogoSource.current
    val logoKey = ExchangeLogos.logoKey(key).takeIf { source != null }
    val state = remember(logoKey, source) { mutableStateOf(logoKey?.let { source?.cached(it) }) }
    // Neue Logos auf dem Gerät: fehlendes erneut versuchen
    val revision = source?.revision?.collectAsState()?.value ?: 0
    LaunchedEffect(logoKey, source, revision) {
        if (state.value == null && logoKey != null) state.value = source?.load(logoKey)
    }
    return state.value
}

@Composable
private fun LogoMark(bitmap: Bitmap) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // Heller Grund hinter dem Logo wie bei den Coins (dunkel: onSurface leicht gedämpft)
    val backing = if (LocalDarkTheme.current) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
    else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier = Modifier.fillMaxSize().background(backing)) {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun SymbolMark(symbol: ExchangeLogos.Symbol, size: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Icon(
            painterResource(if (symbol == ExchangeLogos.Symbol.DEX) R.drawable.ic_swap_horiz else R.drawable.ic_bank),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize().padding(size * SYMBOL_INSET),
        )
    }
}

@Composable
private fun LetterMark(name: String, size: Dp) {
    val dark = LocalDarkTheme.current
    val initials = ExchangeMark.initials(name)
    // Schrift wächst mit dem Kreis, nicht mit der Systemschrift (zwei Zeichen passen immer)
    val fontSize = with(LocalDensity.current) { (size * if (initials.length == 1) 0.42f else 0.36f).toSp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color(CoinLogoPlaceholder.fill(ExchangeMark.colorKey(name), dark))),
    ) {
        Text(
            text = initials,
            color = Color(CoinLogoPlaceholder.text(dark)),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Rand um das Symbol (Anteil des Kreises). */
private const val SYMBOL_INSET = 0.24f
