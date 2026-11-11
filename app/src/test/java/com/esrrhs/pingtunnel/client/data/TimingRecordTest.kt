package com.esrrhs.pingtunnel.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingRecordTest {

    @Test
    fun testDurationsCalculation() {
        val record = TimingRecord(
            name = "https://example.com/test.js",
            initiatorType = "script",
            startTime = 100.0,
            responseEnd = 500.0,
            duration = 400.0,
            dnsStart = 110.0,
            dnsEnd = 150.0,        // 40ms
            connectStart = 150.0,
            connectEnd = 250.0,    // 100ms
            sslStart = 180.0,
            sslEnd = 250.0,        // 70ms
            requestStart = 260.0,
            responseStart = 380.0, // TTFB: 120ms
        )

        assertEquals(40.0, record.dnsDuration, 0.001)
        assertEquals(100.0, record.connectDuration, 0.001)
        assertEquals(70.0, record.sslDuration, 0.001)
        assertEquals(120.0, record.ttfb, 0.001)
        assertEquals(120.0, record.downloadDuration, 0.001)
        assertTrue(record.hasConnectionPhase)
    }

    @Test
    fun testZeroDurationsWhenNotAvailable() {
        val record = TimingRecord(
            name = "https://example.com/cached.png",
            initiatorType = "img",
            startTime = 10.0,
            responseEnd = 20.0,
            duration = 10.0,
        )

        assertEquals(0.0, record.dnsDuration, 0.001)
        assertEquals(0.0, record.connectDuration, 0.001)
        assertEquals(0.0, record.sslDuration, 0.001)
        assertEquals(0.0, record.ttfb, 0.001)
        assertEquals(0.0, record.downloadDuration, 0.001)
        assertFalse(record.hasConnectionPhase)
    }
}
