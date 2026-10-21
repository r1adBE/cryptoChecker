// Vertragsadresse des Tokens im Kopf des Aktionsblatts (nur DEX-Pools), gekürzt, Tippen kopiert sie ganz.
// Eigene Datei wie `WatchSheetDexAddress.swift`; `WatchActionsHeader` setzt sie unter die Börse.
package com.cryptochecker.app.ui.features.watchlist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cryptochecker.app.R
import com.cryptochecker.app.data.DexTokenAddressStore
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.DexLiquidity
import com.cryptochecker.app.domain.watch.DexTokenAddress
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.util.BidiText

/**
 * «0x1234…abcd» unter der Börse eines DEX-Pools ([DexTokenAddress]); Tippen legt die volle Adresse in die
 * Zwischenablage (Android 13+ bestätigt das selbst, darunter ein Toast — wie iOS). Ohne gemerkte Adresse (Paar vor
 * Build 91 hinzugefügt) nichts. TalkBack liest statt «0 x 1 2 3 4 …» die letzten vier Zeichen einzeln (wie iOS).
 */
@Composable
internal fun DexTokenAddressLine(watch: WatchEntity) {
    if (!DexLiquidity.isDex(watch.marketKey)) return
    val context = LocalContext.current
    val address = remember(watch.pairId) { DexTokenAddressStore.get(context, watch.pairId) } ?: return
    val copyLabel = stringResource(R.string.dex_token_address_copy)
    val copiedText = stringResource(R.string.dex_token_address_copied)
    val spoken = stringResource(R.string.dex_token_address_label, dexSpokenTail(address))
    Text(
        BidiText.isolate(DexTokenAddress.shown(address)),
        style = MaterialTheme.typography.label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClickLabel = copyLabel, role = Role.Button) { copyAddress(context, copyLabel, address, copiedText) }
            .semantics { contentDescription = spoken }
    )
}

/** Letzte vier Zeichen der Adresse, einzeln gesprochen («a b c d») — wie iOS `DexTokenAddressLine.spokenTail`. */
internal fun dexSpokenTail(address: String): String = address.trim().takeLast(4).toList().joinToString(" ")

private fun copyAddress(context: Context, label: String, address: String, copiedText: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, address))
    // Ab Android 13 bestätigt das System das Kopieren selbst (sichtbar und für TalkBack); darunter sonst nichts
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
}
