package com.cryptochecker.app.ui.features.about

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
class BatteryOptimizationViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    watchRepository: com.cryptochecker.app.data.WatchRepository,
) : ViewModel() {

    /**
     * null = noch nicht geladen (sonst blitzte der Dialog kurz auf). Erst fragen, wenn
     * Zuverlässigkeit im Hintergrund zählt: Hintergrund-Aktualisierung an und
     * mindestens ein Alarm — nie beim ersten Start, beim ersten Hinzufügen
     * oder bei den Start-Coins.
     */
    val pending: StateFlow<Boolean?> = kotlinx.coroutines.flow.combine(
        settingsRepository.settings,
        watchRepository.observeAllAlarms().map { it.isNotEmpty() }
    ) { s, hasAlarms ->
        !s.batteryPromptSeen && s.backgroundUpdates && hasAlarms
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismiss() {
        viewModelScope.launch { settingsRepository.setBatteryPromptSeen(true) }
    }
}

/**
 * Wird einmalig nach dem ersten Alarm gezeigt, sofern die App noch der
 * Akku-Optimierung unterliegt — in einem ruhigen Moment: [calm] ist erst wahr,
 * wenn der Nutzer wieder auf einem Haupt-Tab ist (also nicht über der
 * Alarm-Bestätigung im Alarm-Bildschirm).
 */
@Composable
fun BatteryOptimizationDialog(
    calm: Boolean,
    viewModel: BatteryOptimizationViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val pending by viewModel.pending.collectAsStateWithLifecycle()

    if (!calm || pending != true) return
    if (com.cryptochecker.app.util.BatteryOptimization.isIgnoring(context)) return

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.battery_title)) },
        text = {
            Text(
                stringResource(R.string.battery_message) + "\n\n" +
                    stringResource(R.string.battery_steps)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    com.cryptochecker.app.util.BatteryOptimization.openSettings(context)
                    viewModel.dismiss()
                }
            ) {
                Text(stringResource(R.string.battery_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss) {
                Text(stringResource(R.string.battery_later))
            }
        }
    )
}
