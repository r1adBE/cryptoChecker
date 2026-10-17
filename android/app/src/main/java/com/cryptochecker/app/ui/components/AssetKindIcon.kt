package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.AssetKind

/**
 * Symbol der Art eines Paars ([AssetKind]) — dasselbe in den Gruppen-Chips, vor der Glocke in der
 * Merkliste und in den Einstellungen: Token = Raute, TradFi = Bank, Alpha = «α» (alle drei Vektoren mit
 * gleicher Strichstärke, feste Grösse in dp, gleiche Mitte).
 * Dekoration; den Namen liest der Screenreader am Chip bzw. im Zeilensatz. Wie `AssetKindIconView` (iOS).
 */
@Composable
fun AssetKindIcon(kind: AssetKind, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    when (kind) {
        AssetKind.TOKEN -> Icon(painterResource(R.drawable.ic_token), contentDescription = null, tint = tint,
            modifier = modifier.size(size))
        AssetKind.TRADFI -> Icon(painterResource(R.drawable.ic_bank), contentDescription = null, tint = tint,
            modifier = modifier.size(size))
        // Vektor statt Schriftzeichen: gleich gross und auf gleicher Höhe wie Raute und Bank
        AssetKind.ALPHA -> Icon(painterResource(R.drawable.ic_alpha), contentDescription = null, tint = tint,
            modifier = modifier.size(size))
    }
}

/** Zeichen für Alpha-Tokens (griechisches Alpha). */
const val ALPHA_GLYPH = "α"
