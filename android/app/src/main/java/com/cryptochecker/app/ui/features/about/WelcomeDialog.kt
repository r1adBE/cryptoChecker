package com.cryptochecker.app.ui.features.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * null heißt: noch nicht geladen. Ohne diesen Zwischenzustand würde der
     * Dialog bei jedem Start kurz aufblitzen, bevor die Einstellung da ist.
     */
    val showWelcome: StateFlow<Boolean?> = settingsRepository.settings
        .map { !it.aboutSeen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun confirm() {
        viewModelScope.launch { settingsRepository.setAboutSeen(true) }
    }
}

/**
 * Wird einmalig nach der Installation gezeigt: Kurztext, drei Zeilen
 * (Beobachten, Alarmieren, Verstehen — je mit kurzer Frage darüber) und «Los geht's»: schließt und führt zur
 * Merkliste, wo die leere Liste die Starter-Auswahl (Top-Coins) zeigt.
 * Lizenz und Kontakt stehen in den Einstellungen unter «Über».
 */
@Composable
fun WelcomeDialog(
    onStart: () -> Unit = {},
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val show by viewModel.showWelcome.collectAsStateWithLifecycle()

    if (show != true) return

    AlertDialog(
        onDismissRequest = viewModel::confirm,
        title = { Text(stringResource(R.string.welcome_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.welcome_text))
                WelcomeLine(R.string.welcome_watch_q, R.string.welcome_watch, Modifier.padding(top = 14.dp))
                WelcomeLine(R.string.welcome_alert_q, R.string.welcome_alert, Modifier.padding(top = 10.dp))
                WelcomeLine(R.string.welcome_understand_q, R.string.welcome_understand, Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.confirm()
                onStart()
            }) {
                Text(stringResource(R.string.welcome_start))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::confirm) {
                Text(stringResource(R.string.welcome_later))
            }
        }
    )
}

/**
 * Eine der drei Zeilen: kleine Frage obenauf («Was passiert?»), darunter der bisherige
 * Text. Für den Screenreader ein Element.
 */
@Composable
private fun WelcomeLine(@StringRes question: Int, @StringRes text: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) { }) {
        Text(
            stringResource(question),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(text),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
