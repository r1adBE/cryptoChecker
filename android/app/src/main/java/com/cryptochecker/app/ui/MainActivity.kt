package com.cryptochecker.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.cryptochecker.app.R
import com.cryptochecker.app.service.PriceServiceController
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.ui.lock.LockScreen
import com.cryptochecker.app.ui.navigation.AppNavHost
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import com.cryptochecker.app.ui.theme.rememberHighContrast
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.AppearanceApplier
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var appearanceApplier: AppearanceApplier

    @Inject lateinit var appLockState: AppLockState

    @Inject lateinit var serviceController: PriceServiceController

    @Inject lateinit var widgetUpdater: com.cryptochecker.app.widget.WidgetUpdater

    /** Ziel aus einer App-Verknüpfung («add», «alarms», «cycle»). */
    private val openTarget = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ab Android 16 (targetSdk 36) zeichnet das System ohnehin randlos.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            openTarget.value = intent?.getStringExtra(EXTRA_OPEN)
            // Neue Oberfläche: ein neuer Prozess ist gesperrt (sofern die App-Sperre an ist),
            // ein Neuaufbau kurz nach dem Verlassen nicht (siehe AppLockState.onColdStart)
            appLockState.onColdStart()
        }

        setContent {
            // null, bis die Einstellungen gelesen sind — erst dann ist klar, ob gesperrt wird
            val loadedSettings: AppSettings? by settingsRepository.settings.collectAsState(initial = null)
            val settings = loadedSettings ?: settingsRepository.cached
            val lockRequested by appLockState.lockRequested.collectAsState()
            // Ausserhalb der Sperre gemerkt: Nach dem Entsperren geht es im selben Tab weiter
            val navController = rememberNavController()

            // Vorschaubild in «Zuletzt verwendet» ohne Kurse, solange die Sperre an ist
            LaunchedEffect(settings.appLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!settings.appLock)
                }
            }

            CryptoCheckerTheme(
                dark = settings.darkMode ?: isSystemInDarkTheme(),
                accent = settings.accentColor,
                priceColors = settings.priceColorScheme,
                highContrast = rememberHighContrast(settings.highContrast),
                priceColorsInverted = settings.priceColorsInverted,
            ) {
                when {
                    // Einstellungen noch nicht gelesen: nichts zeigen statt kurz die Kurse
                    loadedSettings == null && lockRequested -> Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    )
                    settings.appLock && lockRequested -> LockScreen(onUnlock = { promptUnlock() })
                    // Benachrichtigungs-Erlaubnis erst, wenn eine Meldung eingeschaltet wird.
                    else -> AppNavHost(
                        navigation = navController,
                        openTarget = openTarget.value,
                        onOpenTargetHandled = { openTarget.value = null }
                    )
                }
            }
        }
    }

    /** System-Abfrage der App-Sperre; nur eine gleichzeitig. */
    private fun promptUnlock() {
        if (appLockState.authenticating) return
        appLockState.authenticating = true
        AppLockAuth.authenticate(this, getString(R.string.app_lock_reason)) { ok ->
            appLockState.authenticating = false
            if (ok) appLockState.unlock()
        }
    }

    override fun onStart() {
        super.onStart()
        appLockState.onForeground()
        // Live-Dienst nur mit sichtbarer App (wieder) starten: Nach einem Neustart
        // (ab Android 15 kein dataSync-Dienst aus BOOT_COMPLETED) oder nach dem
        // 6-Stunden-Limit (onTimeout) läuft er erst wieder, wenn der Nutzer die App
        // öffnet. Läuft er schon, ist der Aufruf harmlos.
        lifecycleScope.launch {
            if (settingsRepository.current().liveService) serviceController.start()
        }
        // System-Kontrast geändert? Widgets mit den passenden Farben neu zeichnen.
        lifecycleScope.launch {
            runCatching { widgetUpdater.updateIfContrastChanged() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openTarget.value = it }
    }

    /**
     * App-Icon erst beim Verlassen umschalten: Geschieht es, während die App
     * sichtbar ist, beenden manche Android-Versionen sie sofort. Beim Drehen
     * des Bildschirms (Neuaufbau) nicht.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            appLockState.onBackground()
            val settings = settingsRepository.cached
            val systemDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            appearanceApplier.applyLauncherIcon(settings.accentColor, settings.darkMode ?: systemDark)
        }
    }

    companion object {
        const val EXTRA_WATCH_ID = "watch_id"

        /** Von den App-Verknüpfungen gesetzt (res/xml/shortcuts.xml). */
        const val EXTRA_OPEN = "open"
    }
}
