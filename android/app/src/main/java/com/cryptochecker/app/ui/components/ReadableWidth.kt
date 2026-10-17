package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lesbare Höchstbreite des Inhalts auf breiten Bildschirmen (Tablet, Querformat):
 * Karten laufen nicht mehr über 1000 dp, sondern stehen mittig. Hintergründe,
 * Kopfzeilen und die Scroll-Fläche bleiben voll breit. Wie iOS `readableContentWidth()`.
 */
val ReadableMaxWidth: Dp = 640.dp

/**
 * Für eine Spalte innerhalb von `verticalScroll`: nach dem Scroll-Modifier setzen,
 * damit die ganze Breite scrollbar bleibt und nur der Inhalt schmal und mittig steht.
 * Auf schmalen Bildschirmen (Telefon hochkant) ändert sich nichts.
 */
fun Modifier.readableWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = ReadableMaxWidth)
    // Bis zur Höchstbreite voll ausfüllen — sonst schrumpft eine Spalte nur mit Text auf
    // ihren Inhalt und steht auch auf dem Telefon eingerückt
    .fillMaxWidth()

/**
 * Für Listen (`LazyColumn`): liefert den zusätzlichen seitlichen Einzug, um den der
 * Inhalt eingerückt wird (`contentPadding`), damit er höchstens [ReadableMaxWidth]
 * breit ist. Die Liste selbst bleibt voll breit und überall scrollbar.
 */
@Composable
fun ReadableInset(
    modifier: Modifier = Modifier,
    content: @Composable (inset: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val inset = if (maxWidth == Dp.Infinity) 0.dp else ((maxWidth - ReadableMaxWidth) / 2).coerceAtLeast(0.dp)
        content(inset)
    }
}
