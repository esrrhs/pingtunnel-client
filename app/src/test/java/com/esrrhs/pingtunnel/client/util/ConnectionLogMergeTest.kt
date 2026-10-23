package com.esrrhs.pingtunnel.client.util

import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_FAILED
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_SUCCESS
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_UNKNOWN
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_DIRECT
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_PROXY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogMergeTest {

    private fun row(
        key: String = "k1",
        uid: Int = 10001,
        dest: String = "www.example.com",
        domain: String? = dest,
        port: Int = 443,
        proto: String = "TCP",
        tx: Long = 0,
        rx: Long = 0,
        direct: Boolean = false,
        created: Long = 1000L,
    ) = HistoryRow(key, uid, "com.example", "Example", dest, domain, "1.2.3.4", port,
        proto, tx, rx, direct, created)

    private val noEvents = ConnectEventFinder { _, _, _ -> null }

    @Test
    fun newSession_startsUnknownWithRoute() {
        val tick = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(),
            listOf(row(direct = true)),
            nowMs = 2000L,
            finder = noEvents,
        )
        assertTrue(tick.finished.isEmpty())
        val e = tick.state.active.getValue("k1")
        assertEquals(RESULT_UNKNOWN, e.result)
        assertEquals(ROUTE_DIRECT, e.route)
        assertEquals(1000L, e.startMs)
    }

    @Test
    fun activeSessionWithFailureEvent_markedFailedWithReasonAndRoute() {
        val event = ConnectEventInfo(direct = true, success = false,
            reason = "after 8000ms", connectMs = 8001)
        val f = ConnectEventFinder { host, _, _ ->
            if (host == "www.example.com") event else null
        }
        val tick = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(),
            listOf(row()),
            nowMs = 9000L,
            finder = f,
        )
        val e = tick.state.active.getValue("k1")
        assertEquals(RESULT_FAILED, e.result)
        assertEquals("after 8000ms", e.reason)
        assertEquals(ROUTE_DIRECT, e.route)
        assertEquals(8001, e.connectMs)
    }

    @Test
    fun vanishedSession_withBytesAndNoEvent_isSuccess() {
        val first = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(),
            listOf(row(tx = 100, rx = 200)),
            2000L, noEvents,
        )
        val gone = ConnectionLogMerge.tick(first.state, emptyList(), 3000L, noEvents)
        val e = gone.finished.single()
        assertEquals(RESULT_SUCCESS, e.result)
        assertEquals(3000L, e.endMs)
        assertEquals(100L, e.txBytes)
    }

    @Test
    fun vanishedSession_noBytesNoEvent_isUnknown() {
        val first = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(), listOf(row()), 2000L, noEvents,
        )
        val gone = ConnectionLogMerge.tick(first.state, emptyList(), 3000L, noEvents)
        assertEquals(RESULT_UNKNOWN, gone.finished.single().result)
    }

    @Test
    fun vanishedSession_finalFailureEventOverridesUnknown() {
        val first = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(), listOf(row()), 2000L, noEvents,
        )
        val fail = ConnectEventInfo(direct = true, success = false, reason = "timeout", connectMs = 4000)
        val f = ConnectEventFinder { host, port, notBefore ->
            if (host == "www.example.com") fail else null
        }
        val gone = ConnectionLogMerge.tick(first.state, emptyList(), 6000L, f)
        val e = gone.finished.single()
        assertEquals(RESULT_FAILED, e.result)
        assertEquals(ROUTE_DIRECT, e.route)
        assertEquals("timeout", e.reason)
    }

    @Test
    fun failedResult_isStickyAcrossTicks() {
        val fail = ConnectEventInfo(direct = false, success = false, reason = "x", connectMs = 100)
        val f = ConnectEventFinder { _, _, _ -> fail }
        val first = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(), listOf(row()), 2000L, f,
        )
        // 下一轮事件查询故意返回成功，也不应覆盖已确认的失败
        val ok = ConnectEventInfo(direct = false, success = true, reason = null, connectMs = 50)
        val f2 = ConnectEventFinder { _, _, _ -> ok }
        val second = ConnectionLogMerge.tick(first.state, listOf(row()), 3000L, f2)
        assertEquals(RESULT_FAILED, second.state.active.getValue("k1").result)
        assertEquals("x", second.state.active.getValue("k1").reason)
    }

    @Test
    fun proxyEvent_marksRouteProxy() {
        val event = ConnectEventInfo(direct = false, success = true, reason = null, connectMs = 30)
        val f = ConnectEventFinder { host, _, _ ->
            if (host == "www.example.com") event else null
        }
        val tick = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(),
            listOf(row(direct = true)), // hev 侧判直连，但事件以分流器为准
            2000L, f,
        )
        assertEquals(ROUTE_PROXY, tick.state.active.getValue("k1").route)
        assertEquals(RESULT_SUCCESS, tick.state.active.getValue("k1").result)
    }

    @Test
    fun byteGrowth_isRefreshedEachTick() {
        val first = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(), listOf(row(tx = 10, rx = 20)), 2000L, noEvents,
        )
        val second = ConnectionLogMerge.tick(
            first.state, listOf(row(tx = 110, rx = 220)), 3000L, noEvents,
        )
        val e = second.state.active.getValue("k1")
        assertEquals(110L, e.txBytes)
        assertEquals(220L, e.rxBytes)
    }

    @Test
    fun finishAll_finalizesEveryActiveSession() {
        val first = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(),
            listOf(row(key = "a"), row(key = "b", uid = 2, dest = "b.com", tx = 5)),
            2000L, noEvents,
        )
        val all = ConnectionLogMerge.finishAll(first.state, 4000L, noEvents)
        assertEquals(2, all.size)
        assertTrue(all.all { it.endMs == 4000L })
        val b = all.single { it.key == "b" }
        assertEquals(RESULT_SUCCESS, b.result)
        assertNull(b.reason)
    }

    @Test
    fun staleEvent_beforeLookbackWindow_ignored() {
        // 事件时间早于 startMs - 8s 容差，不应被采用
        val event = ConnectEventInfo(direct = false, success = false, reason = "old", connectMs = 1)
        val f = ConnectEventFinder { host, port, notBefore ->
            // notBefore=startMs-8000= -7000；事件 ts=-100000 落在窗口外
            if (host == "www.example.com" && -100_000L >= notBefore) event else null
        }
        val tick = ConnectionLogMerge.tick(
            ConnectionLogMerge.State(), listOf(row()), 2000L, f,
        )
        assertEquals(RESULT_UNKNOWN, tick.state.active.getValue("k1").result)
        assertNotNull(Unit)
    }
}
