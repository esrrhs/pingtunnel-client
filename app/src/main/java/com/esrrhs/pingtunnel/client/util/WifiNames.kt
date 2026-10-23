package com.esrrhs.pingtunnel.client.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.core.content.ContextCompat

/** 读取当前 WiFi SSID；无权限/非 WiFi/未知时返回 null。 */
object WifiNames {

    /** 去掉系统 SSID 外的双引号；未知 SSID 归一为 null。 */
    fun unquote(raw: String?): String? = SsidNames.unquote(raw)

    fun currentSsid(context: Context): String? {
        if (!hasLocationPermission(context)) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = cm.getNetworkCapabilities(cm.activeNetwork)
                ?.transportInfo as? WifiInfo
            unquote(info?.ssid)
        } else {
            @Suppress("DEPRECATION")
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            unquote(wifi?.connectionInfo?.ssid)
        }
    }

    /** API 29+ 可从具体网络的 capabilities 取 SSID；低版本回退当前连接。 */
    @Suppress("DEPRECATION")
    fun ssidOf(context: Context, network: Network?): String? {
        if (!hasLocationPermission(context)) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && network != null) {
            val info = cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
            unquote(info?.ssid)?.let { return it }
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
        return unquote(wifi?.connectionInfo?.ssid)
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}
