package com.cryptochecker.app.ui.features.settings

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing

// ── Portfolio ────────────────────────────────────────────────────────────────

/** Portfolio als eigener Tab und die Portfolio-Sperre (nur mit eingeschaltetem Portfolio). */
@Composable
internal fun PortfolioPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsSubPage(title = stringResource(R.string.portfolio_title), onBack = onBack) {
        GroupCard {
            SettingsAnchor("portfolio.tab") {
                SwitchRow(
                    title = stringResource(R.string.settings_portfolio_tab),
                    subtitle = stringResource(R.string.portfolio_setting_hint),
                    checked = settings.portfolioEnabled,
                    onCheckedChange = viewModel::setPortfolioEnabled
                )
            }
            // Nur mit eingeschaltetem Portfolio; der Wert bleibt beim Ausblenden erhalten
            if (PortfolioLockPolicy.showSetting(settings.portfolioEnabled)) {
                val unavailableText = stringResource(R.string.app_lock_unavailable)
                val reason = stringResource(R.string.portfolio_lock_reason)
                RowDivider()
                SettingsAnchor("portfolio.lock") {
                    SwitchRow(
                        title = stringResource(R.string.settings_portfolio_lock),
                        subtitle = stringResource(R.string.settings_portfolio_lock_hint),
                        checked = settings.appLock,
                        onCheckedChange = { on ->
                            val activity = context.findFragmentActivity()
                            if (!on) {
                                // Solange gesperrt, erst entsperren — sonst wäre die Sperre hier zu umgehen
                                viewModel.disableAppLock(activity, reason)
                            } else if (activity == null || !AppLockAuth.canAuthenticate(context)) {
                                Toast.makeText(context, unavailableText, Toast.LENGTH_LONG).show()
                            } else {
                                // Einschalten erst nach einer erfolgreichen Entsperrung
                                viewModel.enableAppLock(activity, reason)
                            }
                        }
                    )
                }
                RowDivider()
                // «Beträge verbergen»: wie das Auge im Portfolio-Kopf (Portfolio und Widget)
                SettingsAnchor("portfolio.hide") {
                    SwitchRow(
                        title = stringResource(R.string.portfolio_hide_amounts),
                        subtitle = stringResource(R.string.portfolio_hide_amounts_hint),
                        checked = settings.hidePortfolioAmounts,
                        onCheckedChange = viewModel::setHidePortfolioAmounts
                    )
                }
            }
        }
        // Ehrlich: Die Sperre schützt die Anzeige; die Daten schützt die Geräteverschlüsselung
        if (PortfolioLockPolicy.showSetting(settings.portfolioEnabled)) {
            Text(
                text = stringResource(R.string.settings_portfolio_lock_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
            )
        }
    }
}
