package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun bytesBelowKilo_useBUnit() {
        assertEquals("0B", Formatters.formatBytes(0))
        assertEquals("512B", Formatters.formatBytes(512))
        assertEquals("1023B", Formatters.formatBytes(1023))
    }

    @Test
    fun bytesAtKiloBoundary_useKb() {
        assertEquals("1.0KB", Formatters.formatBytes(1024))
        assertEquals("1.5KB", Formatters.formatBytes(1536))
    }

    @Test
    fun largerUnits_areSelected() {
        assertEquals("1.0MB", Formatters.formatBytes(1024L * 1024))
        assertEquals("1.0GB", Formatters.formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0TB", Formatters.formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun rateAppendsPerSecondSuffix() {
        assertEquals("0B/s", Formatters.formatRate(0))
        assertEquals("1.0KB/s", Formatters.formatRate(1024))
    }

    @Test
    fun duration_formatsByMagnitude() {
        assertEquals("0秒", Formatters.formatDuration(0))
        assertEquals("0秒", Formatters.formatDuration(-1))
        assertEquals("5秒", Formatters.formatDuration(5_000))
        assertEquals("59秒", Formatters.formatDuration(59_000))
        assertEquals("1分05秒", Formatters.formatDuration(65_000))
        assertEquals("1小时02分", Formatters.formatDuration(3_720_000))
    }
}
