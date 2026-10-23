package com.esrrhs.pingtunnel.client.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLogPruneTest {

    @Test
    fun keepsNewestThousand() {
        val now = 10_000_000L
        val entries = (0 until 1_500).map { entry(endMs = now - it) }
        val pruned = pruneConnectionLogs(entries, now)
        assertEquals(ConnectionLogRepository.MAX_ENTRIES, pruned.size)
        assertEquals(now, pruned.first().endMs)
        assertEquals(now - 999, pruned.last().endMs)
    }

    @Test
    fun dropsEntriesOlderThanThirtyDays() {
        val now = 40L * 24 * 60 * 60 * 1000
        val fresh = entry(endMs = now - 1_000)
        val stale = entry(endMs = now - ConnectionLogRepository.MAX_AGE_MS - 1)
        val pruned = pruneConnectionLogs(listOf(stale, fresh), now)
        assertEquals(listOf(fresh), pruned)
    }

    private fun entry(endMs: Long) = ConnectionLogEntry(
        key = "k$endMs",
        uid = 1,
        label = "app",
        proto = "TCP",
        remoteIp = "1.1.1.1",
        remotePort = 443,
        startMs = endMs - 10,
        endMs = endMs,
    )
}
