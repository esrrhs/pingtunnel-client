package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTimingBridgeTest {

    @Test
    fun testParseTimingJson() {
        val json = """
            [
                {
                    "name": "https://example.com/",
                    "initiatorType": "navigation",
                    "startTime": 0,
                    "responseEnd": 450.5,
                    "duration": 450.5,
                    "dnsStart": 10.0,
                    "dnsEnd": 55.0,
                    "connectStart": 55.0,
                    "connectEnd": 120.0,
                    "sslStart": 80.0,
                    "sslEnd": 120.0,
                    "requestStart": 125.0,
                    "responseStart": 280.0,
                    "transferSize": 1540,
                    "decodedBodySize": 3200,
                    "nextHopProtocol": "h2"
                }
            ]
        """.trimIndent()

        val list = BrowserTimingBridge.parseTimingJson(json)
        assertEquals(1, list.size)
        val item = list[0]
        assertEquals("https://example.com/", item.name)
        assertEquals("navigation", item.initiatorType)
        assertEquals(45.0, item.dnsDuration, 0.001)
        assertEquals(65.0, item.connectDuration, 0.001)
        assertEquals(40.0, item.sslDuration, 0.001)
        assertEquals(155.0, item.ttfb, 0.001)
        assertEquals(170.5, item.downloadDuration, 0.001)
        assertEquals("h2", item.nextHopProtocol)
    }

    @Test
    fun testPostLogLimits() {
        val bridge = BrowserTimingBridge()
        repeat(250) { i ->
            bridge.postLog("INFO", "message $i")
        }
        val logs = bridge.logs.value
        assertEquals(200, logs.size)
        assertTrue(logs.last().contains("message 249"))
    }

    @Test
    fun testRetainHistoryAcrossNavigation() {
        val bridge = BrowserTimingBridge()
        val jsonPage1 = """
            [
                { "name": "https://page1.com/index.html", "initiatorType": "navigation", "startTime": 0, "duration": 100 }
            ]
        """.trimIndent()
        val jsonPage2 = """
            [
                { "name": "https://page2.com/index.html", "initiatorType": "navigation", "startTime": 0, "duration": 150 }
            ]
        """.trimIndent()

        bridge.currentPageUrl = "https://page1.com"
        bridge.postTimingData(jsonPage1)
        assertEquals(1, bridge.records.value.size)

        bridge.currentPageUrl = "https://page2.com"
        bridge.postTimingData(jsonPage2)
        assertEquals(2, bridge.records.value.size)
        assertEquals("https://page1.com", bridge.records.value[0].pageUrl)
        assertEquals("https://page2.com", bridge.records.value[1].pageUrl)
    }

    @Test
    fun testClearPreventsReaddingPastRecordsOnSamePage() {
        val bridge = BrowserTimingBridge()
        val json = """
            [
                { "name": "https://page1.com/index.html", "initiatorType": "navigation", "startTime": 10.0, "duration": 100 },
                { "name": "https://page1.com/style.css", "initiatorType": "link", "startTime": 20.0, "duration": 50 }
            ]
        """.trimIndent()

        bridge.currentPageUrl = "https://page1.com"
        bridge.postTimingData(json)
        assertEquals(2, bridge.records.value.size)

        // 用户点击清空
        bridge.clear()
        assertEquals(0, bridge.records.value.size)

        // 页面未刷新的定时轮询再次推送相同的 json
        bridge.postTimingData(json)
        // 验证旧记录不会再重新冒出
        assertEquals(0, bridge.records.value.size)

        // 如果页面产生了新的后续请求（startTime 更大）
        val jsonNew = """
            [
                { "name": "https://page1.com/dynamic.js", "initiatorType": "script", "startTime": 30.0, "duration": 80 }
            ]
        """.trimIndent()
        bridge.postTimingData(jsonNew)
        assertEquals(1, bridge.records.value.size)
        assertEquals("https://page1.com/dynamic.js", bridge.records.value[0].name)
    }
}
