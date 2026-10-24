package com.cryptochecker.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.LocalPriceColorScheme
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.ui.theme.SnackbarPalette
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Meldung mit Art ([SnackbarKind]) für [AppSnackbarHost]. Ein gewöhnliches `showSnackbar(String)` kommt ohne Art an
 * und gilt als [SnackbarKind.INFO]. Dauer wie bei Material: ohne Aktion kurz, mit Aktion ohne Ablauf.
 */
data class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration =
        if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
    val kind: SnackbarKind = SnackbarKind.INFO,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
}

/** Meldung [text] der Art [kind] zeigen (wie `showSnackbar`, Ergebnis ebenso). */
suspend fun SnackbarHostState.showAppSnackbar(
    text: String,
    kind: SnackbarKind = SnackbarKind.INFO,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
): SnackbarResult = showSnackbar(AppSnackbarVisuals(text, actionLabel, duration, kind))

/**
 * Snackbar-Bereich aller Bildschirme: Fläche passend zum Thema (`surfaceContainerHighest` — hell hell, dunkel
 * dunkel, statt der hellen Material-Pille `inverseSurface` im Dunkelmodus), Text `onSurface`, Aktion in der
 * Akzentfarbe.
 * Erfolg und Fehler mit Rahmen 1.5 dp und Symbol vorne im Ton aus [SnackbarPalette]; Info nur mit dezenter Kontur
 * (`outlineVariant`), ohne Symbol. Das Symbol ist dekorativ — der Screenreader liest den Meldungstext (Ansage über
 * [SnackbarHost]). Wie iOS `SnackbarSurface`.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> AppSnackbar(data) }
}

@Composable
private fun AppSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    val kind = (visuals as? AppSnackbarVisuals)?.kind ?: SnackbarKind.INFO
    val tone = snackbarTone(kind)
    val colors = MaterialTheme.colorScheme
    val shape = SnackbarDefaults.shape
    val action: (@Composable () -> Unit)? = visuals.actionLabel?.let { label ->
        {
            TextButton(
                onClick = { data.performAction() },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
            ) { Text(label) }
        }
    }
    Snackbar(
        // Aussenabstand wie die Material-Snackbar; der Rahmen liegt auf der Fläche selbst
        modifier = Modifier
            .padding(Spacing.md)
            .border(if (tone != null) 1.5.dp else 1.dp, tone ?: colors.outlineVariant, shape),
        action = action,
        shape = shape,
        containerColor = colors.surfaceContainerHighest,
        contentColor = colors.onSurface,
        actionContentColor = colors.primary,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tone != null) {
                Icon(
                    painterResource(if (kind == SnackbarKind.SUCCESS) R.drawable.ic_status_ok else R.drawable.ic_error),
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.md))
            }
            Text(visuals.message)
        }
    }
}

/** Ton von Rahmen und Symbol für [kind] im aktuellen Thema (Kursfarben-Schema, hoher Kontrast); null = Info. */
@Composable
@ReadOnlyComposable
private fun snackbarTone(kind: SnackbarKind): Color? =
    SnackbarPalette.tone(
        kind,
        LocalPriceColorScheme.current,
        LocalDarkTheme.current,
        LocalHighContrast.current,
        LocalAccentColor.current,
    )?.let { Color(it) }
