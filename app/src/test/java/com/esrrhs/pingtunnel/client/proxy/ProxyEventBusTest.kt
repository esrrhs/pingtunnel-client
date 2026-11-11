package com.esrrhs.pingtunnel.client.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProxyEventBusTest {

    @Before
    fun setUp() {
        ProxyEventBus.clearTraces()
    }

    @Test
    fun testUpdatePipelineAndClear() {
        val timing = InternalPipelineTiming(
            host = "example.com",
            port = 443,
            isDirect = false,
            routeMs = 5L,
            dnsOrConnectMs = 150L,
            handshakeMs = 80L,
            waitFirstDataMs = 200L,
            dataTransferMs = 500L,
            totalDurationMs = 935L,
            uploadBytes = 1024L,
            downloadBytes = 4096L,
            status = "CLOSED"
        )

        ProxyEventBus.updatePipeline(timing)
        assertEquals(1, ProxyEventBus.pipelines.value.size)
        assertEquals("example.com", ProxyEventBus.pipelines.value[0].host)
        assertEquals(935L, ProxyEventBus.pipelines.value[0].totalDurationMs)

        // Update same pipeline
        val updated = timing.copy(downloadBytes = 8192L)
        ProxyEventBus.updatePipeline(updated)
        assertEquals(1, ProxyEventBus.pipelines.value.size)
        assertEquals(8192L, ProxyEventBus.pipelines.value[0].downloadBytes)

        // Clear
        ProxyEventBus.clearTraces()
        assertTrue(ProxyEventBus.pipelines.value.isEmpty())
        assertTrue(ProxyEventBus.traces.value.isEmpty())
    }
}
