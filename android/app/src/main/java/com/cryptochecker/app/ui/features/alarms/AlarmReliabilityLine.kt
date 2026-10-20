package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.AlarmReliability
import com.cryptochecker.app.ui.components.NotificationPrePrompt
import com.cryptochecker.app.ui.features.watchlist.ago
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.AlarmReadinessProbe

/** Test-Tag der Zeile (UI-Tests). */
const val ALARM_RELIABILITY_TAG = "alarm_reliability_line"

/**
 * Eine Zeile über der Merkliste (Build 91): Kommen die Alarme an ([AlarmReliability])? Ruhig eine graue Zeile
 * «Letzte Prüfung vor 12 Min.», sonst Bernstein mit dem wichtigsten Grund; Tipp öffnet «Damit Alarme ankommen»
 * ([LocalOpenAlarmDelivery]). Liest denselben Zustand wie die Seite ([AlarmDeliveryViewModel], [AlarmReadinessProbe]),
 * nach der Rückkehr aus den Systemeinstellungen neu. Ohne Alarme oder ohne Navigation: nichts.
 * Wie `AlarmReliabilityLine.swift`.
 *
 * @param now Uhr der Merkliste (tickt ohnehin für «vor 2 Min.»).
 */
@Composable
fun AlarmReliabilityLine(now: Long, modifier: Modifier = Modifier) {
    // Ohne Navigation (Vorschau, Widget-Einrichtung) auch kein ViewModel anlegen
    val openDelivery = LocalOpenAlarmDelivery.current ?: return
    AlarmReliabilityContent(now, openDelivery, modifier)
}

@Composable
private fun AlarmReliabilityContent(
    now: Long,
    openDelivery: () -> Unit,
    modifier: Modifier,
    viewModel: AlarmDeliveryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastCheck by viewModel.lastBackgroundCheck.collectAsStateWithLifecycle()
    val hasAlarms by viewModel.hasAlarms.collectAsStateWithLifecycle()
    var readAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        readAt = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    // Systemzustand nur bei Änderung bzw. Rückkehr lesen; die Uhr ([now]) nur für «zu alt» und die Zeitangabe
    val input = remember(settings, lastCheck, hasAlarms, readAt) {
        AlarmReadinessProbe.read(
            context, settings, lastCheck, hasAlarms, readAt,
            notificationsNotDetermined = NotificationPrePrompt.notDetermined(context),
        )
    }
    val status = AlarmReliability.status(input.copy(now = now))
    if (status.kind == AlarmReliability.Kind.HIDDEN) return
    val text = when (status.kind) {
        AlarmReliability.Kind.RECENT ->
            stringResource(R.string.alarm_reliability_recent, lastCheck?.let { ago(it, now) }.orEmpty())
        AlarmReliability.Kind.STALE ->
            stringResource(R.string.alarm_reliability_stale, lastCheck?.let { ago(it, now) }.orEmpty())
        AlarmReliability.Kind.NEVER -> stringResource(R.string.alarm_reliability_never)
        AlarmReliability.Kind.NOTIFICATIONS_OFF -> stringResource(R.string.alarm_reliability_notifications_off)
        AlarmReliability.Kind.BATTERY -> stringResource(R.string.alarm_reliability_battery)
        AlarmReliability.Kind.BACKGROUND_REFRESH -> stringResource(R.string.alarm_reliability_background_refresh)
        AlarmReliability.Kind.HIDDEN -> return
    }
    val color = if (status.warn) AppColors.warningText else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                onClickLabel = stringResource(R.string.delivery_title),
                role = Role.Button,
                onClick = openDelivery,
            )
            // Tippfläche 48 dp, auch wenn die Zeile selbst nur eine Textzeile hoch ist
            .heightIn(min = 48.dp)
            .testTag(ALARM_RELIABILITY_TAG),
    ) {
        Icon(
            painterResource(if (status.warn) R.drawable.ic_warning else R.drawable.ic_notifications),
            contentDescription = null,
            tint = if (status.warn) AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
