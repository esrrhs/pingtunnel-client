package com.esrrhs.pingtunnel.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 监视底层物理网络切换（Wi-Fi ↔ 蜂窝、验证状态变化）。
 *
 * 只看带 INTERNET 且 NOT_VPN 的网络。当前出口消失时立刻松开
 * [android.net.VpnService.setUnderlyingNetworks]，避免继续钉在已经拆掉的 Wi-Fi 上；
 * 换到另一张已验证的网时再通知重建数据面。
 */
class NetworkWatchdog(
    context: Context,
    private val onNetworkUpdate: ((Network?) -> Unit)? = null,
    private val onDefaultNetworkChanged: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = PhysicalNetworkTracker()
    private val networks = HashMap<String, Network>()
    private val gate = Any()
    private var generation = 0

    @Volatile
    private var registered = false

    val current: Network?
        get() = synchronized(gate) { tracker.currentKey?.let { networks[it] } }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ingest(network, null)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            ingest(network, capabilities)
        }

        override fun onLost(network: Network) {
            forget(network)
        }
    }

    fun start() {
        val cm = cm ?: return
        val gen = synchronized(gate) {
            if (registered) return
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
            tracker.reset()
            networks.clear()
            true
        }
        if (shouldUnregister) runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private fun ingest(network: Network, caps: NetworkCapabilities?) {
        val resolved = caps ?: cm?.getNetworkCapabilities(network) ?: return
        if (!resolved.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !resolved.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) {
            forget(network)
            return
        }
        synchronized(gate) {
            if (!registered) return
            networks[key(network)] = network
            dispatchLocked(tracker.upsert(resolved.toCandidate(key(network))))
        }
    }

    private fun forget(network: Network) {
        synchronized(gate) {
            if (!registered) return
            networks.remove(key(network))
            dispatchLocked(tracker.remove(key(network)))
        }
    }

    private fun dispatchLocked(action: UpstreamAction) {
        val chosen = tracker.currentKey?.let { networks[it] }
        when (action) {
            UpstreamAction.NONE -> Unit
            UpstreamAction.BASELINE -> {
                Log.i(TAG, "baseline physical network: $chosen")
                onNetworkUpdate?.invoke(chosen)
            }
            UpstreamAction.CLEAR -> {
                Log.i(TAG, "physical network lost, release underlying")
                onNetworkUpdate?.invoke(null)
            }
            UpstreamAction.SWITCH -> {
                Log.i(TAG, "physical network changed: $chosen")
                onNetworkUpdate?.invoke(chosen)
                onDefaultNetworkChanged()
            }
        }
    }

    private fun key(network: Network): String = network.toString()

    private companion object {
        const val TAG = "NetworkWatchdog"
    }
}
