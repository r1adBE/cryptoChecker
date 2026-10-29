package com.cryptochecker.app.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Portfolio-Sperre: Entsperren mit Biometrie ODER Geräte-Sperre (PIN, Muster, Passwort), über androidx.biometric
 * ([authenticatorsFor]).
 *  - ab API 30: setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) — nur starke
 *    Biometrie (Klasse 3), schwache (z. B. reine Kamera-Gesichtserkennung) führt zur Geräte-Sperre
 *  - API 26–29: BIOMETRIC_WEAK mit setDeviceCredentialAllowed(true) (dort so verlangt; STRONG mit
 *    DEVICE_CREDENTIAL gibt es dort nicht)
 * Mit Geräte-Sperre als Ausweg gibt es keinen «Abbrechen»-Knopf (negativeButton).
 */
object AppLockAuth {

    /** Erlaubte Verfahren je API-Stufe: ab API 30 starke Biometrie oder Geräte-Sperre. */
    fun authenticatorsFor(sdk: Int): Int =
        if (sdk >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private val authenticators get() = authenticatorsFor(Build.VERSION.SDK_INT)

    /** Kann dieses Gerät entsperren (Biometrie oder eingerichtete Displaysperre)? */
    fun canAuthenticate(context: Context): Boolean {
        val biometric = runCatching {
            BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
        if (biometric) return true
        // Vor API 30 prüft canAuthenticate die Geräte-Sperre nicht überall zuverlässig
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true
    }

    /**
     * Zeigt die System-Abfrage. [onResult] true = entsperrt; false = abgebrochen oder
     * Fehler (einzelne Fehlversuche lässt die Abfrage selbst wiederholen).
     */
    fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Timber.d("Portfolio-Sperre: %d %s", errorCode, errString)
                    onResult(false)
                }
            }
        )
        val builder = BiometricPrompt.PromptInfo.Builder().setTitle(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(authenticators)
        } else {
            // Android 8–10: BIOMETRIC_STRONG | DEVICE_CREDENTIAL ist dort nicht erlaubt, nur dieser Weg zur Geräte-PIN
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        runCatching { prompt.authenticate(builder.build()) }
            .onFailure {
                Timber.w(it, "Portfolio-Sperre: Abfrage nicht möglich")
                onResult(false)
            }
    }
}

/** Die FragmentActivity hinter einem (Compose-)Context, falls es eine gibt. */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Zustand der Portfolio-Sperre für die laufende App (Prozess).
 *
 * Gesperrt wird beim Kaltstart (neuer Prozess) und nach mehr als
 * [PortfolioLockPolicy.BACKGROUND_LIMIT_MILLIS] im Hintergrund — gemessen mit der monotonen Uhr
 * ([SystemClock.elapsedRealtime], läuft auch im Tiefschlaf weiter): Eine zurückgestellte Systemuhr verlängert die
 * Frist nicht — wirksam nur, wenn die
 * Einstellung «Portfolio-Sperre» an ist (siehe [PortfolioLockPolicy.access]; die Oberfläche
 * prüft das, weil die Einstellungen beim Kaltstart erst geladen werden müssen). Die übrige
 * App ist nie gesperrt.
 */
@Singleton
class AppLockState @Inject constructor() {

    /** Startwert true: ein neuer Prozess beginnt gesperrt (sofern die Sperre an ist). */
    private val _lockRequested = MutableStateFlow(true)
    val lockRequested: StateFlow<Boolean> = _lockRequested.asStateFlow()

    /** Zeitpunkt, seit dem die App im Hintergrund ist; 0 = im Vordergrund. */
    @Volatile
    private var backgroundSince = 0L

    /** Läuft gerade eine Abfrage? Dann zählt deren Fenster nicht als «Hintergrund». */
    @Volatile
    var authenticating = false

    /**
     * Entsperren verlangt, aber keine Displaysperre (und keine Biometrie) eingerichtet: Das Portfolio bleibt gesperrt —
     * nie still entsperrt. Der Sperr-Zustand nennt das («Displaysperre einrichten …») und bietet an, die Sperre nach
     * Rückfrage auszuschalten. Bei der Rückkehr in den Vordergrund neu geprüft (inzwischen eingerichtet?).
     */
    private val _screenLockMissing = MutableStateFlow(false)
    val screenLockMissing: StateFlow<Boolean> = _screenLockMissing.asStateFlow()

    /**
     * Kaltstart der Oberfläche (Activity ohne gespeicherten Zustand). Ein neuer Prozess ist
     * ohnehin gesperrt (Startwert). Baut ein Tipp auf Mitteilung oder Widget die Activity
     * neu auf (FLAG_ACTIVITY_CLEAR_TOP), während die App sichtbar ist oder erst kurz im
     * Hintergrund war, wird nicht erneut gesperrt. Länger weg: sperrt [onForeground].
     */
    fun onColdStart(now: Long = SystemClock.elapsedRealtime()) {
        authenticating = false
        if (PortfolioLockPolicy.relockAfterBackground(backgroundSince, now)) _lockRequested.value = true
    }

    fun onBackground(now: Long = SystemClock.elapsedRealtime()) {
        if (authenticating) return
        backgroundSince = now
    }

    fun onForeground(now: Long = SystemClock.elapsedRealtime()) {
        val since = backgroundSince
        backgroundSince = 0L
        if (PortfolioLockPolicy.relockAfterBackground(since, now)) _lockRequested.value = true
        // Vielleicht inzwischen eine Displaysperre eingerichtet: der nächste Versuch prüft neu
        _screenLockMissing.value = false
        // Eine Abfrage, die nie zurückgemeldet hat (z. B. nicht angezeigt), blockiert sonst den Knopf
        authenticating = false
    }

    /** Erfolgreich entsperrt (oder Sperre gerade eingeschaltet bzw. ausgeschaltet). */
    fun unlock() {
        _lockRequested.value = false
        _screenLockMissing.value = false
    }

    /**
     * Portfolio entsperren, falls nötig: Ist [locked] false, sofort [onUnlocked]; sonst die
     * System-Abfrage (nur eine gleichzeitig) und bei Erfolg entsperren und [onUnlocked].
     */
    fun requireUnlock(activity: FragmentActivity?, locked: Boolean, reason: String, onUnlocked: () -> Unit) {
        if (!locked) {
            onUnlocked()
            return
        }
        if (activity == null || authenticating) return
        // Keine Displaysperre mehr eingerichtet: nicht still entsperren (sonst wäre die Sperre mit dem Entfernen der
        // Displaysperre umgangen) — gesperrt lassen und es nennen; Ausweg: Sperre nach Rückfrage ausschalten
        if (!AppLockAuth.canAuthenticate(activity)) {
            _screenLockMissing.value = true
            return
        }
        authenticating = true
        AppLockAuth.authenticate(activity, reason) { ok ->
            authenticating = false
            if (ok) {
                unlock()
                onUnlocked()
            }
        }
    }
}
