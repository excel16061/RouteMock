package com.routemock.app.dashboard

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** What must survive a restart until the dashboard has been told. */
interface SyncState {
    fun pendingRevocations(): List<Pairing>
    fun clearPendingRevocation(pairing: Pairing)
    var receiptUploadPending: Boolean
    fun currentPairing(): Pairing?
}

/**
 * Delivers withdrawals to the dashboard. Local enforcement never waits for this: by the
 * time anything here runs, the phone has already stopped.
 */
class DashboardSync(
    private val state: SyncState,
    private val revoke: suspend (Pairing) -> Unit,
    private val uploadReceipt: suspend (Pairing) -> Unit,
) {
    private val mutex = Mutex()

    val hasWork: Boolean
        get() = state.pendingRevocations().isNotEmpty() || state.receiptUploadPending

    /** Returns true when nothing is left to deliver. */
    suspend fun flush(): Boolean = mutex.withLock {
        for (pairing in state.pendingRevocations()) {
            if (delivered { revoke(pairing) }) state.clearPendingRevocation(pairing)
        }
        val pairing = state.currentPairing()
        when {
            !state.receiptUploadPending -> Unit
            pairing == null -> state.receiptUploadPending = false
            delivered { uploadReceipt(pairing) } -> state.receiptUploadPending = false
        }
        !hasWork
    }

    /**
     * True if there is no reason to try again. A refused credential counts: the dashboard
     * already considers it finished. Retrying continues for outages and server errors.
     */
    private suspend fun delivered(action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (e: DashboardException) {
        e.status in FINAL_STATUSES
    } catch (_: IOException) {
        false
    }

    private companion object {
        val FINAL_STATUSES = setOf(400, 401, 403, 404)
    }
}
