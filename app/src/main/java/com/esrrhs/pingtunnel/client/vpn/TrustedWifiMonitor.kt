package com.esrrhs.pingtunnel.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.esrrhs.pingtunnel.client.util.WifiNames

/**
 * 监视默认网络在「可信 WiFi / 非可信网络」之间的边沿变化。
 *
 * - 注册后第一次观察到的网络只作为基线（可能正是用户主动连 VPN 时的网络，不应立即暂停）；
 * - 之后可信↔非可信的状态翻转才触发 [onTrustedChanged]；
 * - 蜂窝网络 SSID 为 null，天然是非可信。
 */
class TrustedWifiMonitor(
    context: Context,
    private val evaluate: (ssid: String?) -> Boolean,
    private val onTrustedChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val tracker = PhysicalNetworkTracker()
    private val networks = HashMap<String, Network>()
    private val gate = Any()
    private var generation = 0

    @Volatile
    private var registered = false
    private var baselineDone = false
    private var lastTrusted: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = ingest(network, null)

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) = ingest(network, capabilities)

        override fun onLost(network: Network) {
            val chosen = synchronized(gate) {
                if (!registered) return
                networks.remove(key(network))
                val action = tracker.remove(key(network))
                // 短暂没有网络时不把空窗当成离开可信 Wi-Fi；已经切到另一张网则马上判定
                if (action != UpstreamAction.SWITCH) return
                tracker.currentKey?.let { networks[it] }
            } ?: return
            handle(chosen)
        }
    }

    private fun handle(network: Network) {
        val ssid = WifiNames.ssidOf(appContext, network)
        val trusted = evaluate(ssid)
        if (!baselineDone) {
            baselineDone = true
            lastTrusted = trusted
            Log.i(TAG, "baseline network trusted=$trusted ssid=$ssid")
            return
        }
        if (lastTrusted != trusted) {
            lastTrusted = trusted
            Log.i(TAG, "trusted state changed: $trusted (ssid=$ssid)")
            onTrustedChanged(trusted)
        }
    }

    private fun ingest(network: Network, caps: NetworkCapabilities?) {
        val resolved = caps ?: cm?.getNetworkCapabilities(network) ?: return
        if (!resolved.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !resolved.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) {
            return
        }
        val chosen = synchronized(gate) {
            if (!registered) return
            networks[key(network)] = network
            val action = tracker.upsert(resolved.toCandidate(key(network)))
            if (action != UpstreamAction.BASELINE && action != UpstreamAction.SWITCH) return
            tracker.currentKey?.let { networks[it] }
        } ?: return
        handle(chosen)
    }

    /** 重新注册并重置基线（每次成功建会话时调用）。 */
    fun start() {
        stop()
        val cm = cm ?: return
        baselineDone = false
        lastTrusted = null
        val gen = synchronized(gate) {
            registered = true
            generation += 1
            tracker.reset()
            networks.clear()
            generation
        }
        val registeredOk = runCatching { cm.registerUnderlyingCallback(callback) }
            .onFailure { Log.w(TAG, "register underlying callback failed", it) }
            .isSuccess
        synchronized(gate) {
            if (!registeredOk || !registered || generation != gen) {
                if (registeredOk) runCatching { cm.unregisterNetworkCallback(callback) }
                if (generation == gen) {
                    registered = false
                    tracker.reset()
                    networks.clear()
                }
            }
        }
    }

    fun stop() {
        val cm = cm
        val shouldUnregister = synchronized(gate) {
            if (!registered) return
            registered = false
            generation += 1
            baselineDone = false
            lastTrusted = null
            tracker.reset()
            networks.clear()
            true
        }
        if (shouldUnregister) runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private fun key(network: Network): String = network.toString()

    private companion object {
        const val TAG = "TrustedWifiMonitor"
    }
}
