package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.cryptochecker.app.domain.starter.AddMoment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Bewegung einer Zeile im Erst-Moment: [enter] blendet die Zeile ein, [priceReveal] zeigt den
 * Kurs sanft, [draw] zeichnet das Mini-Chart von links nach rechts, [check] ist das kurze
 * Häkchen. Ausserhalb des Moments stehen alle am Endwert.
 */
@Stable
internal class RowMotion(
    val enter: Animatable<Float, AnimationVector1D>,
    val priceReveal: Animatable<Float, AnimationVector1D>,
    val draw: Animatable<Float, AnimationVector1D>,
    val check: Animatable<Float, AnimationVector1D>,
)

/**
 * Erst-Moment: Zeile blendet ein, Kurs erscheint sanft, Mini-Chart zeichnet sich, kurz ein
 * Häkchen. Startwerte gelten schon beim ersten Zeichnen, damit nichts aufblitzt.
 */
@Composable
internal fun rememberRowMotion(
    watchId: Long,
    celebrateKey: Long?,
    celebrateIndex: Int?,
    reduceMotion: Boolean,
    hasPrice: Boolean,
    hasSparkline: Boolean,
): RowMotion {
    val animate = celebrateKey != null && !reduceMotion
    val enter = remember(watchId) { Animatable(if (animate) 0f else 1f) }
    val priceReveal = remember(watchId) { Animatable(if (animate) 0f else 1f) }
    val draw = remember(watchId) { Animatable(if (animate) 0f else 1f) }
    val check = remember(watchId) { Animatable(0f) }
    LaunchedEffect(celebrateKey) {
        if (celebrateKey == null) {
            // Moment vorbei (oder abgebrochen): Endzustand, nichts bleibt halb stehen
            enter.snapTo(1f)
            priceReveal.snapTo(1f)
            check.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduceMotion) {
            // Ohne Bewegung: Häkchen erscheint und verschwindet ohne Übergang
            check.snapTo(1f)
            delay(AddMoment.CHECK_HOLD_MILLIS)
            check.snapTo(0f)
            return@LaunchedEffect
        }
        // War die Zeile schon sichtbar (Startwert 1), bleibt sie stehen — nur das Häkchen kommt
        delay(AddMoment.staggerDelay(celebrateIndex ?: 0))
        enter.animateTo(1f, tween(AddMoment.ENTER_MILLIS))
        check.animateTo(1f, tween(200))
        delay(AddMoment.CHECK_HOLD_MILLIS)
        check.animateTo(0f, tween(AddMoment.CHECK_FADE_MILLIS))
    }
    // Kurs: kurzer Übergang, sobald der erste Kurs da ist (oder gleich nach dem Einblenden)
    LaunchedEffect(celebrateKey, hasPrice) {
        if (priceReveal.value >= 1f) return@LaunchedEffect
        if (celebrateKey == null || reduceMotion) {
            priceReveal.snapTo(1f)
            return@LaunchedEffect
        }
        if (!hasPrice) return@LaunchedEffect
        snapshotFlow { enter.value }.first { it >= 1f }
        priceReveal.animateTo(1f, tween(350))
    }
    // Mini-Chart: zeichnet sich von links nach rechts, sobald der Verlauf da ist
    LaunchedEffect(celebrateKey, hasSparkline) {
        if (!hasSparkline || draw.value >= 1f) return@LaunchedEffect
        if (celebrateKey == null || reduceMotion) {
            draw.snapTo(1f)
            return@LaunchedEffect
        }
        snapshotFlow { enter.value }.first { it >= 1f }
        draw.animateTo(1f, tween(AddMoment.DRAW_MILLIS))
    }
    return remember(enter, priceReveal, draw, check) { RowMotion(enter, priceReveal, draw, check) }
}

/** Kurssprung: [strength] blitzt kurz in der Kursfarbe auf ([up] = gestiegen). */
@Stable
internal class PriceFlash(val strength: Animatable<Float, AnimationVector1D>) {
    var up by mutableStateOf(true)
        internal set
}

/** Kurssprung kurz in der Kursfarbe aufblitzen lassen. */
@Composable
internal fun rememberPriceFlash(watchId: Long, price: Double?): PriceFlash {
    val flash = remember { PriceFlash(Animatable(0f)) }
    var shownPrice by remember(watchId) { mutableStateOf(price) }
    LaunchedEffect(price) {
        val previous = shownPrice
        val current = price
        shownPrice = current
        if (previous != null && current != null && current != previous) {
            flash.up = current > previous
            flash.strength.snapTo(1f)
            // Kurz (früher 1 s): die rollenden Ziffern zeigen die Änderung schon
            flash.strength.animateTo(0f, tween(durationMillis = 500))
        }
    }
    return flash
}
