package com.esrrhs.pingtunnel.client.vpn

/**
 * 当前数据面信息：本地 SOCKS5 端口。
 * Service 建立会话时写入、拆链时清空；UI 层据此做隧道内实测。
 */
object ActiveSession {
    @Volatile
    var socksPort: Int? = null
}
