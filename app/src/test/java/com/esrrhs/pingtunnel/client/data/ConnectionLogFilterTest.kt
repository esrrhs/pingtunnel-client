package com.esrrhs.pingtunnel.client.data

import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_FAILED
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.RESULT_SUCCESS
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_DIRECT
import com.esrrhs.pingtunnel.client.data.ConnectionLogEntry.Companion.ROUTE_PROXY
import com.esrrhs.pingtunnel.client.ui.HistoryIpFilter
import com.esrrhs.pingtunnel.client.ui.HistoryProtoFilter
import com.esrrhs.pingtunnel.client.ui.HistoryResultFilter
import com.esrrhs.pingtunnel.client.ui.HistoryRouteFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogFilterTest {

    private fun sampleEntry(
        remoteIp: String = "1.2.3.4",
        proto: String = "TCP",
        route: String = ROUTE_PROXY,
        result: String = RESULT_SUCCESS,
        domain: String? = null,
    ) = ConnectionLogEntry(
        key = "sample",
        uid = 1000,
        label = "App",
        proto = proto,
        domain = domain,
        remoteIp = remoteIp,
        remotePort = 443,
        route = route,
        result = result,
        startMs = 1000L,
    )

    @Test
    fun isIpv6_detectsIpv6Correctly() {
        val v4 = sampleEntry(remoteIp = "142.250.72.206")
        val v6 = sampleEntry(remoteIp = "2402:4e00:c032:6100::1")
        val v6Compressed = sampleEntry(remoteIp = "::1")
        val v6Domain = sampleEntry(remoteIp = "100.64.0.1", domain = "2001:db8::1")

        assertFalse(v4.isIpv6)
        assertTrue(v6.isIpv6)
        assertTrue(v6Compressed.isIpv6)
        assertTrue(v6Domain.isIpv6)
    }

    @Test
    fun filter_byResult() {
        val entries = listOf(
            sampleEntry(result = RESULT_SUCCESS),
            sampleEntry(result = RESULT_FAILED),
        )

        val successOnly = entries.filter { entry ->
            when (HistoryResultFilter.SUCCESS) {
                HistoryResultFilter.ALL -> true
                HistoryResultFilter.SUCCESS -> entry.result == RESULT_SUCCESS
                HistoryResultFilter.FAILED -> entry.result == RESULT_FAILED
            }
        }
        assertEquals(1, successOnly.size)
        assertEquals(RESULT_SUCCESS, successOnly.first().result)

        val failedOnly = entries.filter { entry ->
            when (HistoryResultFilter.FAILED) {
                HistoryResultFilter.ALL -> true
                HistoryResultFilter.SUCCESS -> entry.result == RESULT_SUCCESS
                HistoryResultFilter.FAILED -> entry.result == RESULT_FAILED
            }
        }
        assertEquals(1, failedOnly.size)
        assertEquals(RESULT_FAILED, failedOnly.first().result)
    }

    @Test
    fun filter_byIpVersion() {
        val entries = listOf(
            sampleEntry(remoteIp = "1.1.1.1"),
            sampleEntry(remoteIp = "2606:4700::1111"),
        )

        val v4Only = entries.filter { !it.isIpv6 }
        val v6Only = entries.filter { it.isIpv6 }

        assertEquals(1, v4Only.size)
        assertEquals("1.1.1.1", v4Only.first().remoteIp)

        assertEquals(1, v6Only.size)
        assertEquals("2606:4700::1111", v6Only.first().remoteIp)
    }

    @Test
    fun filter_combinedConditions() {
        val entries = listOf(
            sampleEntry(remoteIp = "1.1.1.1", result = RESULT_SUCCESS, route = ROUTE_PROXY, proto = "TCP"),
            sampleEntry(remoteIp = "2606:4700::1111", result = RESULT_SUCCESS, route = ROUTE_PROXY, proto = "TCP"),
            sampleEntry(remoteIp = "2606:4700::1111", result = RESULT_FAILED, route = ROUTE_PROXY, proto = "TCP"),
            sampleEntry(remoteIp = "2606:4700::1111", result = RESULT_SUCCESS, route = ROUTE_DIRECT, proto = "TCP"),
            sampleEntry(remoteIp = "2606:4700::1111", result = RESULT_SUCCESS, route = ROUTE_PROXY, proto = "UDP"),
        )

        // Filter: SUCCESS + IPv6 + PROXY + TCP
        val filtered = entries.filter { entry ->
            val matchResult = entry.result == RESULT_SUCCESS
            val matchIp = entry.isIpv6
            val matchRoute = entry.route == ROUTE_PROXY
            val matchProto = entry.proto.equals("TCP", ignoreCase = true)
            matchResult && matchIp && matchRoute && matchProto
        }

        assertEquals(1, filtered.size)
        assertEquals("2606:4700::1111", filtered.first().remoteIp)
    }
}
