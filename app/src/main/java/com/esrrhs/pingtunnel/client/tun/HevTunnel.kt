package com.esrrhs.pingtunnel.client.tun

import android.util.Log
import hev.htproxy.TProxyService

/**
 * hev-socks5-tunnel 的 Kotlin 封装。
 *
 * native 方法注册在固定类名 hev.htproxy.TProxyService 上（见 hev-jni.c），
 * 这里做一层转调并吞掉 so 缺失时的链接错误，转为可读异常。
 */
object HevTunnel {

    private const val TAG = "HevTunnel"

    @Volatile
    private var loadError: String? = null

    /** 确保 so 已加载；缺失时抛出带修复提示的异常。 */
    fun ensureLoaded() {
        loadError?.let { throw IllegalStateException(it) }
        try {
            // 触发类初始化（System.loadLibrary）
            TProxyService.TProxyIsRunning()
        } catch (e: UnsatisfiedLinkError) {
            loadError = "缺少 libhev-socks5-tunnel.so，请先运行 scripts/build_native.sh 再打包"
            Log.e(TAG, "load hev-socks5-tunnel failed", e)
            throw IllegalStateException(loadError!!, e)
        }
    }

    /** 启动隧道；内部线程运行，立即返回。 */
    fun start(configPath: String, tunFd: Int): Boolean {
        ensureLoaded()
        return TProxyService.TProxyStartService(configPath, tunFd)
    }

    fun stop(): Boolean = runCatching {
        TProxyService.TProxyStopService()
    }.onFailure { Log.w(TAG, "stop failed", it) }.getOrDefault(false)

    fun isRunning(): Boolean = runCatching {
        TProxyService.TProxyIsRunning()
    }.getOrDefault(false)

    /** [txPackets, txBytes, rxPackets, rxBytes]；so 未加载时返回 null。 */
    fun stats(): LongArray? = runCatching {
        TProxyService.TProxyGetStats()
    }.getOrNull()

    /**
     * 当前隧道会话文本，每行：
     * proto|srcIp|srcPort|dstIp|dstPort|upload|download|createdMs[|domain]；
     * domain 为 mapped-DNS 反查到的真实域名，可能缺失或为空。
     * 未运行/无会话时为空串；so 未加载时返回 null。
     */
    fun sessions(): String? = runCatching {
        TProxyService.TProxyGetSessions()
    }.getOrNull()
}
