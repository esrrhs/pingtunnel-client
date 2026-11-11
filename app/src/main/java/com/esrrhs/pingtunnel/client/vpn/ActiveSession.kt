package com.esrrhs.pingtunnel.client.vpn

/**
 * 当前数据面信息：本地 SOCKS5 端口及活动配置参数。
 * Service 建立会话时写入、拆链时清空；UI 层据此做隧道内实测与诊断展示。
 */
object ActiveSession {
    @Volatile
    var socksPort: Int? = null

    @Volatile
    var sppProto: String = "icmp"

    @Volatile
    var sppCompress: Int = 0

    @Volatile
    var sppEncrypt: String = ""
}
