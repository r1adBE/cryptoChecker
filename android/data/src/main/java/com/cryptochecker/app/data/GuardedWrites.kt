package com.cryptochecker.app.data

import com.cryptochecker.app.domain.local.WriteGuard
import javax.inject.Inject

/** [WriteGuard] über [guardedWrite] und [reportWriteFailure]. */
class GuardedWrites @Inject constructor() : WriteGuard {
    override suspend fun <T> guarded(failed: T, block: suspend () -> T): T = guardedWrite(failed, block = block)

    override fun reportFailure(e: Throwable) = reportWriteFailure(e)
}
