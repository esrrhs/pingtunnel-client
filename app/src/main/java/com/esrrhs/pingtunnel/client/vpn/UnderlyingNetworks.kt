package com.esrrhs.pingtunnel.client.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 监听「有网且不是 VPN」的物理网络。
 *
 * [ConnectivityManager.registerDefaultNetworkCallback] 在 VPN 进程里会把 VPN 自己当成默认网络，
 * Wi-Fi 掉线时回调根本看不到蜂窝，底层网络就会一直钉在已经消失的 Wi-Fi 上。
 */
internal fun ConnectivityManager.registerUnderlyingCallback(
    callback: ConnectivityManager.NetworkCallback,
) {
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    val handler = Handler(Looper.getMainLooper())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        registerBestMatchingNetworkCallback(request, callback, handler)
    } else {
        registerNetworkCallback(request, callback, handler)
    }
}

internal fun NetworkCapabilities.toCandidate(key: String) = PhysicalNetworkTracker.Candidate(
    key = key,
    validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    preferTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
    downstreamKbps = linkDownstreamBandwidthKbps,
)
