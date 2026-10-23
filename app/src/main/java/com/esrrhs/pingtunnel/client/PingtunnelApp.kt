package com.esrrhs.pingtunnel.client

import android.app.Application
import com.esrrhs.pingtunnel.client.util.DirectProxySelector

/**
 * 进程入口。
 *
 * 目前只做一件事：安装绕过系统代理的 [DirectProxySelector]。必须在任何网络调用之前
 * 完成（Application.onCreate 早于 Activity / Service / BootReceiver），否则本机 SOCKS
 * 端口探测与分流直连会被系统级 SOCKS 代理劫持。
 *
 * 注意：本机 socks5_client 是独立子进程（Go），不受 JVM 的 ProxySelector 影响，
 * 它的出站本来就走 socket 直连。
 */
class PingtunnelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DirectProxySelector.install()
    }
}
