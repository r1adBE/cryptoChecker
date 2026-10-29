package com.cryptochecker.app.ui

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.StartupClock
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.ui.components.CoinLogoSource
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.ui.components.LocalPortfolioCoinLogoSource
import com.cryptochecker.app.ui.components.LocalCoinNameSource
import com.cryptochecker.app.ui.components.LocalCoinSearchNameSource
import com.cryptochecker.app.ui.navigation.AppNavHost
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme
import com.cryptochecker.app.ui.theme.rememberHighContrast
import com.cryptochecker.app.settings.AppearanceApplier
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var appearanceApplier: AppearanceApplier

    @Inject lateinit var appLockState: AppLockState

    @Inject lateinit var widgetUpdater: com.cryptochecker.app.widget.WidgetUpdater

    @Inject lateinit var coinLogoRepository: CoinLogoRepository

    @Inject lateinit var coinLogoSync: com.cryptochecker.app.data.CoinLogoSync

    /** Ziel aus einer App-Verknüpfung («add», «alarms», «cycle»). */
    private val openTarget = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Startbildschirm (Theme.CryptoChecker.Starting) vor super.onCreate: danach gilt das App-Theme
        val splash = installSplashScreen()
        // Ab Android 16 (targetSdk 36) zeichnet das System ohnehin randlos.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // AppCompat setzt die Sprache neu: Zahlen-Locale (Sprache + Region) nachziehen
        com.cryptochecker.app.settings.AppLanguages.applyNumberLocale()
        if (savedInstanceState == null) {
            // App-Start bis zum ersten Bild der Merkliste (Bericht «Ablauf»)
            StartupClock.onUiCreated()
            openTarget.value = intent?.let(::targetOf)
            // Neue Oberfläche: ein neuer Prozess sperrt das Portfolio (sofern die Sperre an ist),
            // ein Neuaufbau kurz nach dem Verlassen nicht (siehe AppLockState.onColdStart)
            appLockState.onColdStart()
        }
        keepSplashUntilSettingsShown(splash)

        setContent {
            // Zuerst «gelesen?», dann den Wert: loaded wird erst nach cached gesetzt
            val initialSettings = remember {
                val wasLoaded = settingsRepository.loaded
                settingsRepository.cached to wasLoaded
            }
            val settings by settingsRepository.settings.collectAsState(initial = initialSettings.first)
            // Erstes Bild erst, wenn gelesene Einstellungen zusammengesetzt sind: entweder
            // waren sie schon beim Aufbau da, oder ein Wert aus der Datei ist eingetroffen
            SideEffect {
                if (initialSettings.second || settings !== initialSettings.first) settingsShown = true
            }
            // Ausserhalb der Navigation gemerkt: Tab-Zustand bleibt über Neuzusammensetzungen
            val navController = rememberNavController()

            // Vorschaubild in «Zuletzt verwendet» ohne Werte, solange die Portfolio-Sperre an ist
            LaunchedEffect(settings.appLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!settings.appLock)
                }
            }

            // Nur in instrumentierten Tests gesetzt (Audit mit grosser Schrift / Arabisch), sonst unverändert
            TestDisplayOverride.Provide {
                CryptoCheckerTheme(
                    dark = settings.darkMode ?: isSystemInDarkTheme(),
                    accent = settings.accentColor,
                    priceColors = settings.priceColorScheme,
                    highContrast = rememberHighContrast(settings.highContrast),
                    priceColorsInverted = settings.priceColorsInverted,
                ) {
                    // Coin-Logos je Schalter (App, Portfolio); aus → null, keine Plakette
                    val repositorySource = remember { RepositoryLogoSource(coinLogoRepository) }
                    CompositionLocalProvider(
                        LocalCoinLogoSource provides repositorySource.takeIf { settings.coinLogos },
                        LocalPortfolioCoinLogoSource provides repositorySource.takeIf { settings.portfolioCoinLogos },
                        LocalCoinNameSource provides repositorySource.takeIf { settings.watchlistNames },
                        LocalCoinSearchNameSource provides repositorySource,
                    ) {
                        // Die App ist nie ganz gesperrt: Die Portfolio-Sperre prüft AppNavHost
                        // für den Portfolio-Tab und seine Unterseiten.
                        // Benachrichtigungs-Erlaubnis erst, wenn eine Meldung eingeschaltet wird.
                        AppNavHost(
                            navigation = navController,
                            openTarget = openTarget.value,
                            onOpenTargetHandled = { openTarget.value = null }
                        )
                        // Rückfrage «nicht gespeichert» vor der ersten Änderung, solange die Merkliste nicht gespeichert wird
                        com.cryptochecker.app.ui.components.WatchlistNotSavedGateHost()
                        // Die eine Erklärung vor der Mitteilungs-Abfrage (nie je Bildschirm eine eigene)
                        com.cryptochecker.app.ui.components.NotificationPrePromptHost()
                    }
                }
            }
        }
    }

    /** true, sobald die Oberfläche mit den aus der Datei gelesenen Einstellungen steht. */
    @Volatile
    private var settingsShown = false

    /**
     * Kein runBlocking beim Prozessstart: Die Einstellungen kommen
     * asynchron aus DataStore; bis sie in der Oberfläche stehen, bleibt der Startbildschirm
     * stehen (setKeepOnScreenCondition, wird vor jedem Bild abgefragt) – der Hauptthread bleibt
     * frei, kein ANR. So zeigt schon das erste Bild Akzent, Tabs und Hell/Dunkel wie gespeichert.
     * Nach [FIRST_FRAME_WAIT_MILLIS] verschwindet er auf jeden Fall (dann mit Standardwerten).
     */
    private fun keepSplashUntilSettingsShown(splash: SplashScreen) {
        if (settingsRepository.loaded) {
            settingsShown = true
            return
        }
        // Lesen anstossen (die App startet es in onCreate ohnehin; doppelt schadet nicht)
        lifecycleScope.launch { runCatchingCancellable { settingsRepository.current() } }
        val since = SystemClock.uptimeMillis()
        var extraFrame = true
        splash.setKeepOnScreenCondition {
            val timedOut = SystemClock.uptimeMillis() - since > FIRST_FRAME_WAIT_MILLIS
            if (settingsShown && extraFrame && !timedOut) {
                // Ein Bild mehr: ViewModels mit eigener Sammlung (z. B. Portfolio-Tab) ziehen nach
                extraFrame = false
                true
            } else {
                !((settingsShown && !extraFrame) || timedOut)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
        appLockState.onForeground()
        // Coin-Logos: fehlende (alle Coins der Rangliste, nie einzeln) im Hintergrund nachladen
        lifecycleScope.launch { coinLogoSync.startIfEnabled() }
        // System-Kontrast geändert? Widgets mit den passenden Farben neu zeichnen.
        lifecycleScope.launch {
            runCatching { widgetUpdater.updateIfContrastChanged() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    /** singleTop: Tipp auf Mitteilung, Widget oder Verknüpfung, während die Activity läuft. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetOf(intent)?.let { openTarget.value = it }
    }

    /**
     * Ziel eines Aufrufs: [EXTRA_OPEN] (Verknüpfung, Ziel-Mitteilung) oder das Paar aus
     * [EXTRA_WATCH_ID] (Kurs-/Alarm-Mitteilung, Zeile im Listen-Widget) → [openWatchTarget].
     * Aus «Zuletzt verwendet» neu gestartet: kein Ziel (der alte Tipp gilt nicht noch einmal). [EXTRA_OPEN] von einer
     * fremden App (ohne Geheimnis, [OpenTargetGuard]) gilt nur für die Ziele der App-Verknüpfungen.
     */
    private fun targetOf(intent: Intent): String? {
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        intent.getStringExtra(EXTRA_OPEN)?.let { return OpenTargetGuard.acceptedTarget(this, intent, it) }
        val watchId = intent.getLongExtra(EXTRA_WATCH_ID, NO_WATCH)
        return if (watchId > 0) openWatchTarget(watchId) else null
    }

    /**
     * App-Icon erst beim Verlassen umschalten: Geschieht es, während die App
     * sichtbar ist, beenden manche Android-Versionen sie sofort. Beim Drehen
     * des Bildschirms (Neuaufbau) nicht. Das Icon hängt nur an der Akzentfarbe
     * (nicht an Hell/Dunkel), ändert sich also nur nach einem Farbwechsel.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppVisibility.onStop()
            appLockState.onBackground()
            // Erst mit gelesenen Einstellungen: sonst stellte der Standardwert das Icon auf Orange
            if (settingsRepository.loaded) appearanceApplier.applyLauncherIcon(settingsRepository.cached.accentColor)
        }
    }

    companion object {
        /** Paar (Watch-Id), das beim Öffnen gezeigt wird (Merkliste mit seinem Aktionsblatt). */
        const val EXTRA_WATCH_ID = "watch_id"
        private const val NO_WATCH = -1L

        /** Längstens so lange wartet das erste Bild auf die Einstellungen. */
        private const val FIRST_FRAME_WAIT_MILLIS = 1_500L

        /**
         * Von den App-Verknüpfungen (res/xml/shortcuts.xml), Mitteilungen und Widgets gesetzt; eigene Intents tragen dazu
         * das Geheimnis ([withOpenToken], [OpenTargetGuard]).
         */
        const val EXTRA_OPEN = "open"

        /**
         * Ziel der Mitteilung «Wirtschaftstermine»: Markt-Tab, Register mit dem Hinweis («Jetzt» bei
         * einem Termin in ±2 h, sonst «Daten»). Wie iOS `cycle/macro`.
         */
        const val OPEN_MARKET_MACRO = "cycle/macro"

        /** Ziel «Was bewegt sich mit?» aus einem Alarm: Merkliste mit dem Blatt des Paars. */
        private const val WHY_PREFIX = "why/"

        fun openWhyTarget(watchId: Long): String = "$WHY_PREFIX$watchId"

        /** Paar aus einem Ziel [openWhyTarget]; null bei anderen Zielen. */
        fun whyWatchId(target: String?): Long? =
            target?.takeIf { it.startsWith(WHY_PREFIX) }?.removePrefix(WHY_PREFIX)?.toLongOrNull()

        /** Ziel «Paar öffnen» (Mitteilung, Widget-Zeile): Merkliste mit dem Aktionsblatt des Paars. Wie iOS `watch/<id>`. */
        private const val WATCH_PREFIX = "watch/"

        fun openWatchTarget(watchId: Long): String = "$WATCH_PREFIX$watchId"

        /** Paar aus einem Ziel [openWatchTarget]; null bei anderen Zielen. */
        fun watchTargetId(target: String?): Long? =
            target?.takeIf { it.startsWith(WATCH_PREFIX) }?.removePrefix(WATCH_PREFIX)?.toLongOrNull()
    }
}

/** CoinBadge holt die Logos über das Repository (Speicher → Datei → CoinGecko). */
private class RepositoryLogoSource(private val repository: CoinLogoRepository) : CoinLogoSource {
    override val revision = repository.revision
    override val tradFiPairs = repository.tradFiPairs
    override val names = repository.names
    override fun cached(symbol: String) = repository.cached(symbol)
    override suspend fun load(symbol: String) = repository.logo(symbol)
    override suspend fun loadSavedNames() = repository.loadSavedNames()
}
