// Vertragsadresse des Tokens im Kopf des Aktionsblatts (nur DEX-Pools), gekürzt, Tippen kopiert sie ganz.
// Eigene Datei wie `WatchSheetDexAddress.swift`; `WatchActionsHeader` setzt sie unter die Börse.
package com.cryptochecker.app.ui.features.watchlist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.cryptochecker.app.R
import com.cryptochecker.app.data.DexTokenAddressStore
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.DexLiquidity
import com.cryptochecker.app.domain.watch.DexTokenAddress
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.BidiText

/**
 * «0x1234…abcd» unter der Börse eines DEX-Pools ([DexTokenAddress]); Tippen legt die volle Adresse in die
 * Zwischenablage (Android 13+ bestätigt das selbst). Ohne gemerkte Adresse (Paar vor Build 91 hinzugefügt) nichts.
 */
@Composable
internal fun DexTokenAddressLine(watch: WatchEntity) {
    if (!DexLiquidity.isDex(watch.marketKey)) return
    val context = LocalContext.current
    val address = remember(watch.pairId) { DexTokenAddressStore.get(context, watch.pairId) } ?: return
    val copyLabel = stringResource(R.string.dex_token_address_copy)
    Text(
        BidiText.isolate(DexTokenAddress.shown(address)),
        style = MaterialTheme.typography.label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClickLabel = copyLabel, role = Role.Button) { copyAddress(context, copyLabel, address) }
    )
}

private fun copyAddress(context: Context, label: String, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, address))
}
