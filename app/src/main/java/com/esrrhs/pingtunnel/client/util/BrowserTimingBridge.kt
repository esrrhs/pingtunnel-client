package com.esrrhs.pingtunnel.client.util

import android.webkit.JavascriptInterface
import com.esrrhs.pingtunnel.client.data.TimingRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 注入到 WebView 中的 JS 接口，接收网页前端发回的 Performance Resource & Navigation Timing 数据。
 */
class BrowserTimingBridge {

    private val _records = MutableStateFlow<List<TimingRecord>>(emptyList())
    val records: StateFlow<List<TimingRecord>> = _records.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile
    var currentPageUrl: String = ""

    @Volatile
    var lastUserTriggerMs: Long = 0L

    // 记录用户手动点击「清空」时各页面的最后已清空时间戳（毫秒）
    private val clearedPageTimeMap = java.util.concurrent.ConcurrentHashMap<String, Double>()

    @JavascriptInterface
    fun postTimingData(jsonString: String) {
        val list = parseTimingJson(jsonString)
        if (list.isEmpty()) return

        val page = currentPageUrl
        val minStartTime = clearedPageTimeMap[page] ?: -1.0

        // 过滤掉清空前已经开始的旧请求（如果页面未刷新，其 startTime 必然小于清空时刻的相对时间）
        // 并为新来的记录打上对应的页面 URL 和用户点击触发时间戳
        val trigger = lastUserTriggerMs
        val incoming = list
            .filter { it.startTime > minStartTime }
            .map { it.copy(pageUrl = page, userTriggerMs = trigger) }
        if (incoming.isEmpty()) return
        val current = _records.value
        val merged = current.toMutableList()
        val existingKeys = HashSet<String>(current.size)
        current.forEach { existingKeys.add("${it.pageUrl}|${it.name}|${it.startTime.toInt()}") }

        var hasNewOrUpdate = false
        for (item in incoming) {
            val key = "${item.pageUrl}|${item.name}|${item.startTime.toInt()}"
            if (existingKeys.add(key)) {
                merged.add(item)
                hasNewOrUpdate = true
            } else {
                // 如果已存在但持续时间发生了变化（比如进行中 -> 完成），更新记录
                val idx = merged.indexOfFirst {
                    it.pageUrl == item.pageUrl && it.name == item.name && it.startTime.toInt() == item.startTime.toInt()
                }
                if (idx != -1 && (item.duration > merged[idx].duration || item.responseEnd > merged[idx].responseEnd)) {
                    merged[idx] = item
                    hasNewOrUpdate = true
                }
            }
        }

        if (hasNewOrUpdate) {
            // 最多保留 1000 条历史记录，防止无限增长
            if (merged.size > 1000) {
                _records.value = merged.takeLast(1000)
            } else {
                _records.value = merged
            }
        }
    }

    @JavascriptInterface
    fun postLog(level: String, msg: String) {
        val entry = "[$level] $msg"
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > 200) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clear() {
        // 记录当前页面已经加载到的最大 startTime，后续同一页面的旧性能条目不再重新采入
        val page = currentPageUrl
        val currentMaxStartTime = _records.value
            .filter { it.pageUrl == page }
            .maxOfOrNull { it.startTime } ?: 0.0
        clearedPageTimeMap[page] = currentMaxStartTime

        _records.value = emptyList()
        _logs.value = emptyList()
    }

    companion object {
        const val JS_INTERFACE_NAME = "AndroidTimingBridge"

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 注入到页面的脚本：
         * 1. 劫持 console.log / warn / error
         * 2. 定期与在 load 完成时提取 performance.getEntriesByType('navigation') 和 'resource'
         */
        const val INJECTION_SCRIPT = """
            (function() {
                if (window.__timing_injected) return;
                window.__timing_injected = true;

                // 劫持 console
                var oldLog = console.log;
                var oldWarn = console.warn;
                var oldErr = console.error;

                console.log = function() {
                    var msg = Array.prototype.slice.call(arguments).join(' ');
                    if (window.AndroidTimingBridge) window.AndroidTimingBridge.postLog('LOG', msg);
                    if (oldLog) oldLog.apply(console, arguments);
                };
                console.warn = function() {
                    var msg = Array.prototype.slice.call(arguments).join(' ');
                    if (window.AndroidTimingBridge) window.AndroidTimingBridge.postLog('WARN', msg);
                    if (oldWarn) oldWarn.apply(console, arguments);
                };
                console.error = function() {
                    var msg = Array.prototype.slice.call(arguments).join(' ');
                    if (window.AndroidTimingBridge) window.AndroidTimingBridge.postLog('ERR', msg);
                    if (oldErr) oldErr.apply(console, arguments);
                };

                window.__collectTiming = function() {
                    try {
                        if (!window.performance) return;
                        var origin = (window.performance.timeOrigin) || (window.performance.timing ? window.performance.timing.navigationStart : 0) || Date.now();
                        var entries = [];
                        var nav = performance.getEntriesByType('navigation');
                        if (nav && nav.length > 0) {
                            for (var i = 0; i < nav.length; i++) {
                                var n = nav[i];
                                entries.push({
                                    name: n.name || window.location.href,
                                    initiatorType: 'navigation',
                                    startTime: n.startTime || 0,
                                    responseEnd: n.responseEnd || 0,
                                    duration: n.duration || 0,
                                    unloadStart: n.unloadEventStart || 0,
                                    unloadEnd: n.unloadEventEnd || 0,
                                    redirectStart: n.redirectStart || 0,
                                    redirectEnd: n.redirectEnd || 0,
                                    fetchStart: n.fetchStart || 0,
                                    dnsStart: n.domainLookupStart || 0,
                                    dnsEnd: n.domainLookupEnd || 0,
                                    connectStart: n.connectStart || 0,
                                    connectEnd: n.connectEnd || 0,
                                    sslStart: n.secureConnectionStart || 0,
                                    sslEnd: n.connectEnd || 0,
                                    requestStart: n.requestStart || 0,
                                    responseStart: n.responseStart || 0,
                                    transferSize: n.transferSize || 0,
                                    decodedBodySize: n.decodedBodySize || 0,
                                    nextHopProtocol: n.nextHopProtocol || '',
                                    timeOrigin: origin
                                });
                            }
                        }
                        var res = performance.getEntriesByType('resource');
                        if (res && res.length > 0) {
                            for (var j = 0; j < res.length; j++) {
                                var r = res[j];
                                entries.push({
                                    name: r.name,
                                    initiatorType: r.initiatorType || 'other',
                                    startTime: r.startTime || 0,
                                    responseEnd: r.responseEnd || 0,
                                    duration: r.duration || 0,
                                    fetchStart: r.fetchStart || 0,
                                    dnsStart: r.domainLookupStart || 0,
                                    dnsEnd: r.domainLookupEnd || 0,
                                    connectStart: r.connectStart || 0,
                                    connectEnd: r.connectEnd || 0,
                                    sslStart: r.secureConnectionStart || 0,
                                    sslEnd: r.connectEnd || 0,
                                    requestStart: r.requestStart || 0,
                                    responseStart: r.responseStart || 0,
                                    transferSize: r.transferSize || 0,
                                    decodedBodySize: r.decodedBodySize || 0,
                                    nextHopProtocol: r.nextHopProtocol || '',
                                    timeOrigin: origin
                                });
                            }
                        }
                        if (entries.length > 0 && window.AndroidTimingBridge) {
                            window.AndroidTimingBridge.postTimingData(JSON.stringify(entries));
                        }
                    } catch (e) {
                        if (window.AndroidTimingBridge) window.AndroidTimingBridge.postLog('ERR', 'Timing collection error: ' + e);
                    }
                };

                // 定时轮询 + 监听完成
                setInterval(window.__collectTiming, 1000);
                window.addEventListener('load', window.__collectTiming);

                // 提供给 Android 客户端直接清空前端 Performance Buffer 的方法
                window.__clearTimingBuffer = function() {
                    try {
                        if (window.performance && window.performance.clearResourceTimings) {
                            window.performance.clearResourceTimings();
                        }
                    } catch (e) {}
                };
            })();
        """

        fun parseTimingJson(jsonString: String): List<TimingRecord> {
            return try {
                val element = jsonParser.parseToJsonElement(jsonString)
                val array = element.jsonArray
                val result = ArrayList<TimingRecord>(array.size)
                for (item in array) {
                    val obj = item.jsonObject
                    result.add(
                        TimingRecord(
                            name = obj["name"]?.jsonPrimitive?.content ?: "",
                            initiatorType = obj["initiatorType"]?.jsonPrimitive?.content ?: "",
                            startTime = obj["startTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            responseEnd = obj["responseEnd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            duration = obj["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            unloadStart = obj["unloadStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            unloadEnd = obj["unloadEnd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            redirectStart = obj["redirectStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            redirectEnd = obj["redirectEnd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            fetchStart = obj["fetchStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            dnsStart = obj["dnsStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            dnsEnd = obj["dnsEnd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            connectStart = obj["connectStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            connectEnd = obj["connectEnd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            sslStart = obj["sslStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            sslEnd = obj["sslEnd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            requestStart = obj["requestStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            responseStart = obj["responseStart"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            transferSize = obj["transferSize"]?.jsonPrimitive?.longOrNull ?: 0L,
                            decodedBodySize = obj["decodedBodySize"]?.jsonPrimitive?.longOrNull ?: 0L,
                            nextHopProtocol = obj["nextHopProtocol"]?.jsonPrimitive?.content ?: "",
                            timeOrigin = obj["timeOrigin"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        )
                    )
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
