package com.cryptochecker.app.ui.lock

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Portfolio-Sperre für die Oberfläche: Zustand des Portfolio-Bereichs ([access]) und
 * Entsperren vor einer Portfolio-Aktion ausserhalb des Tabs ([requireUnlock]).
 */
@HiltViewModel
class PortfolioLockViewModel @Inject constructor(
    private val appLockState: AppLockState,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Bis die Einstellungen gelesen sind PENDING (sofern eine Sperre fällig wäre). */
    val access: StateFlow<PortfolioAccess> = combine(
        settingsRepository.settings.map<AppSettings, Boolean?> { it.appLock }.onStart { emit(null) },
        appLockState.lockRequested,
    ) { setting, requested -> PortfolioLockPolicy.access(setting, requested) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            PortfolioLockPolicy.access(null, appLockState.lockRequested.value)
        )

    /**
     * Einstellung «Portfolio-Sperre» (unabhängig vom Entsperr-Zustand): schützt das Fenster,
     * solange Portfolio-Beträge sichtbar sein können (FLAG_SECURE, [SecureWindowEffect]).
     */
    val lockEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.appLock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.cached.appLock)

    /**
     * [onUnlocked] sofort, wenn das Portfolio frei ist; sonst erst nach erfolgreicher
     * System-Abfrage. Die Einstellung wird dafür frisch gelesen.
     */
    fun requireUnlock(activity: FragmentActivity?, reason: String, onUnlocked: () -> Unit) {
        viewModelScope.launch {
            val locked = PortfolioLockPolicy.isLocked(
                settingsRepository.current().appLock,
                appLockState.lockRequested.value
            )
            appLockState.requireUnlock(activity, locked, reason, onUnlocked)
        }
    }
}

/**
 * Ruhiger Sperr-Zustand im Portfolio-Tab (und in dessen Unterseiten): Schloss, kurzer Text,
 * «Entsperren». Fragt beim Erscheinen einmal von selbst nach; danach nur noch per Knopf.
 * Keine Beträge, keine Coins.
 */
@Composable
fun PortfolioLockedState(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            text = stringResource(R.string.portfolio_locked_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 16.dp)
                .semantics { heading() }
        )
        Text(
            text = stringResource(R.string.portfolio_locked_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        FilledTonalButton(onClick = onUnlock, modifier = Modifier.padding(top = Spacing.lg)) {
            Text(stringResource(R.string.app_lock_unlock))
        }
    }
}

/**
 * FLAG_SECURE am Fenster der Aktivität, solange [active]: kein Vorschaubild mit Beträgen in
 * «Zuletzt verwendet» (auch vor Android 13, wo setRecentsScreenshotEnabled fehlt) und keine
 * Bildschirmfotos. Beim Verlassen bzw. mit [active] = false wieder entfernt.
 */
@Composable
fun SecureWindowEffect(active: Boolean) {
    val activity = LocalContext.current.findFragmentActivity()
    DisposableEffect(active, activity) {
        val window = activity?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (active) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Ohne gelesene Einstellungen (Kaltstart, Sperre fällig): leere Fläche statt kurz Beträge. */
@Composable
fun PortfolioPendingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}
