package com.routemock.app.dashboard

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DashboardSyncTest {
    private val first = Pairing("https://a.example", "token-a", "")
    private val second = Pairing("https://b.example", "token-b", "")

    private class FakeState(pending: List<Pairing> = emptyList(), private val paired: Pairing? = null) : SyncState {
        val queue = pending.toMutableList()
        override fun pendingRevocations() = queue.toList()
        override fun clearPendingRevocation(pairing: Pairing) {
            queue.remove(pairing)
        }
        override var receiptUploadPending = false
        override fun currentPairing() = paired
    }

    private fun sync(state: SyncState, revoke: suspend (Pairing) -> Unit, upload: suspend (Pairing) -> Unit = {}) =
        DashboardSync(state, revoke, upload)

    @Test fun `a delivered revocation leaves the queue`() = runTest {
        val state = FakeState(listOf(first))
        assertTrue(sync(state, {}).flush())
        assertTrue(state.queue.isEmpty())
    }

    @Test fun `offline, the revocation is kept for later`() = runTest {
        val state = FakeState(listOf(first))
        assertFalse(sync(state, { throw IOException("offline") }).flush())
        assertEquals(listOf(first), state.queue)
    }

    @Test fun `it goes through on reconnect`() = runTest {
        val state = FakeState(listOf(first))
        var online = false
        val sync = sync(state, { if (!online) throw IOException("offline") })
        assertFalse(sync.flush())
        online = true
        assertTrue(sync.flush())
        assertTrue(state.queue.isEmpty())
    }

    @Test fun `server errors and rate limits are retried`() = runTest {
        listOf(429, 500, 502, 503).forEach { status ->
            val state = FakeState(listOf(first))
            assertFalse(sync(state, { throw DashboardException(status) }).flush())
            assertEquals(1, state.queue.size)
        }
    }

    @Test fun `a credential the server already refuses needs no retry`() = runTest {
        listOf(401, 403, 404).forEach { status ->
            val state = FakeState(listOf(first))
            assertTrue(sync(state, { throw DashboardException(status) }).flush())
            assertTrue(state.queue.isEmpty())
        }
    }

    @Test fun `one failing dashboard does not block another`() = runTest {
        val state = FakeState(listOf(first, second))
        assertFalse(
            sync(state, { if (it == first) throw IOException("offline") }).flush(),
        )
        assertEquals(listOf(first), state.queue)
    }

    @Test fun `a queued receipt is sent, and kept while offline`() = runTest {
        val state = FakeState(paired = first).apply { receiptUploadPending = true }
        var online = false
        val sync = sync(state, {}, { if (!online) throw IOException("offline") })
        assertFalse(sync.flush())
        assertTrue(state.receiptUploadPending)
        online = true
        assertTrue(sync.flush())
        assertFalse(state.receiptUploadPending)
    }

    @Test fun `a receipt with nowhere to go is dropped rather than retried forever`() = runTest {
        val state = FakeState(paired = null).apply { receiptUploadPending = true }
        assertTrue(sync(state, {}).flush())
        assertFalse(state.receiptUploadPending)
    }

    @Test fun `nothing is contacted when nothing is queued`() = runTest {
        var calls = 0
        assertTrue(sync(FakeState(), { calls++ }, { calls++ }).flush())
        assertEquals(0, calls)
    }
}
