package com.cryptochecker.app.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.cryptochecker.app.util.RollingDigits
import com.cryptochecker.app.util.RollingDirection

/** Dauer, in der eine Ziffer heraus- und die neue hineingleitet. */
private const val ROLL_MILLIS = 250

/**
 * Zahl mit «rollenden Ziffern» wie iOS `contentTransition(.numericText)`: Ändert sich
 * der Wert, gleiten nur die geänderten Ziffern senkrecht — nach oben, wenn [value]
 * gestiegen ist, nach unten, wenn er gefallen ist. Alles andere (Trenner, Währung,
 * Wörter) wechselt ohne Bewegung. Gleich breite Ziffern ([style] mit `tnum`) halten
 * die Breite stabil.
 *
 * Ohne Animationen (Animator-Dauer 0) oder bei Text mit Schrift von rechts nach links
 * ein gewöhnlicher Text — ebenso, bis sich der Wert hier zum ersten Mal ändert (erstes
 * Zeichnen, Scrollen). Der Screenreader liest immer den ganzen Text.
 *
 * @param value Zahl hinter [text], nur für die Richtung; null = Richtung bleibt
 * @param contentDescription Text für den Screenreader; null = [text]
 */
@Composable
fun RollingNumberText(
    text: String,
    value: Double?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    contentDescription: String? = null,
) {
    val spoken = contentDescription ?: text
    val reduceMotion = rememberReduceMotion()
    val direction = remember { RollingDirection(value) }
    val up = direction.update(value)

    // Bis sich der Text hier zum ersten Mal ändert, ein gewöhnlicher Text: Beim ersten
    // Zeichnen und beim Scrollen (Zeilen neu zusammengesetzt) entstehen keine
    // Animationsknoten je Ziffer, und es rollt nichts. Erst bei einer echten Änderung
    // wechselt die Anzeige — noch mit dem alten Text — in den Rollmodus und rollt dann
    // zum neuen (zwei Bilder später), sodass schon die erste Änderung rollt.
    var rolling by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(text) }
    val animatable = !reduceMotion && RollingDigits.canRoll(text) && RollingDigits.canRoll(shown)
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        if (animatable && !rolling) {
            rolling = true
            withFrameMillis { }
            withFrameMillis { }
        }
        shown = text
    }

    if (!animatable || !rolling) {
        Text(
            // Wartet der Wechsel in den Rollmodus, noch kurz den alten Text (kein Aufblitzen)
            text = if (animatable) shown else text,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            modifier = if (contentDescription == null) modifier
            else modifier.clearAndSetSemantics { this.contentDescription = spoken },
        )
        return
    }

    // Teile von links nach rechts, auch in Sprachen mit RTL-Layout (Zahlen laufen immer LTR)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier = modifier.clipToBounds().clearAndSetSemantics { this.contentDescription = spoken }) {
            for (slot in RollingDigits.slots(shown)) {
                key(slot.key) {
                    AnimatedContent(
                        targetState = slot.text,
                        transitionSpec = {
                            if (RollingDigits.rolls(initialState, targetState)) {
                                // Steigend: neue Ziffer kommt von unten, alte geht nach oben
                                val sign = if (up) 1 else -1
                                ((slideInVertically(tween(ROLL_MILLIS)) { height -> sign * height } +
                                    fadeIn(tween(ROLL_MILLIS))) togetherWith
                                    (slideOutVertically(tween(ROLL_MILLIS)) { height -> -sign * height } +
                                        fadeOut(tween(ROLL_MILLIS))))
                                    .using(SizeTransform(clip = true))
                            } else {
                                // Sofort tauschen, auch die Breite: Mit «None» bliebe der alte Teil
                                // sonst sichtbar, bis eine Grössen-Animation fertig ist (Überlappung)
                                (EnterTransition.None togetherWith ExitTransition.None)
                                    .using(SizeTransform(clip = true) { _, _ -> snap() })
                            }
                        },
                        label = "rolling_digit",
                    ) { part ->
                        Text(
                            text = part,
                            style = style,
                            color = color,
                            fontWeight = fontWeight,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animationen im System ausgeschaltet (Entwickleroption bzw. Bedienungshilfe
 * «Animationen entfernen»: Animator-Dauer 0)?
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
