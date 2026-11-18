package com.cryptochecker.app.data.local

import android.content.Context
import com.cryptochecker.app.data.DexTokenAddressStore
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.portfolio.PortfolioRestoreNotice
import com.cryptochecker.app.domain.local.StorageRecoveryKind
import com.cryptochecker.app.domain.local.StoredNotices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** [StoredNotices] über die Vermerke in den SharedPreferences dieses Geräts. */
class StoredNoticeFiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : StoredNotices {
    override val recoveryChanges: StateFlow<Int> get() = StorageRecoveryNotice.changes

    override fun pendingRecovery(): StorageRecoveryKind? = StorageRecoveryNotice.pending(context)

    override fun acknowledgeRecovery(kind: StorageRecoveryKind) = StorageRecoveryNotice.acknowledge(context, kind)

    override fun hideSetAsideForNow() = StorageRecoveryNotice.hideSetAsideForNow()

    override fun isAddressServerUnknown(): Boolean = StorageRecoveryNotice.isAddressServerUnknown(context)

    override fun takePortfolioRestoreInvalid(): Int = PortfolioRestoreNotice.take(context)

    override fun isEncryptionPending(): Boolean = DatabaseEncryptionNotice.isPending(context)

    override fun lastFullCheck(): Long = RefreshStats.storedLastFullCheck(context)

    override fun dexTokenAddress(pairId: String?): String? = DexTokenAddressStore.get(context, pairId)
}
