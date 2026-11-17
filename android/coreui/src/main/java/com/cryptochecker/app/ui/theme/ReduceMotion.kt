package com.cryptochecker.app.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// «Animationen entfernen» des Systems einmal oben im Baum beobachten (vorher Teil von RollingNumberText.kt).

/**
 * Einmal oben im Baum beobachtete Einstellung «Animationen entfernen» ([CryptoCheckerTheme][com.cryptochecker.app.ui.theme.CryptoCheckerTheme]
 * stellt sie bereit); null = keine bereitgestellt, dann beobachtet [rememberReduceMotion] selbst.
 */
val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

/**
 * Animationen im System ausgeschaltet (Entwickleroption bzw. Bedienungshilfe
 * «Animationen entfernen»: Animator-Dauer 0)? Aus [LocalReduceMotion] — nicht je Kurs und Pille einer Zeile einen
 * eigenen Beobachter (Anmelden beim System auf dem Haupt-Thread beim Scrollen); ohne Bereitstellung selbst beobachtet.
 */
@Composable
fun rememberReduceMotion(): Boolean = LocalReduceMotion.current ?: observeReduceMotion()

/**
 * Beobachtet die Einstellung, damit ein Wechsel bei laufender App sofort gilt (nicht erst nach dem Neuaufbau des
 * Bildschirms). Einmal je Baum ([LocalReduceMotion]).
 */
@Composable
fun observeReduceMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var reduce by remember(resolver) { mutableStateOf(readReduceMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(resolver)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        }.isSuccess
        // Zwischen erstem Lesen und Anmelden geändert? Einmal nachlesen.
        reduce = readReduceMotion(resolver)
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun readReduceMotion(resolver: ContentResolver): Boolean = runCatching {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
