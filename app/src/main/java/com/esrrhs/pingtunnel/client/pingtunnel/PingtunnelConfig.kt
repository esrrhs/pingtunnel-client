package com.esrrhs.pingtunnel.client.pingtunnel

import kotlinx.serialization.Serializable

/**
 * pingtunnel 连接配置，字段与 pingtunnel client 命令行参数一一对应
 * （见 README「pingtunnel 参数映射」）。传输层固定为 ICMP（借 ping socket 免 root），
 * 本地以 SOCKS5 模式监听（-sock5 1，隐含 tcp 可靠传输模式）。
 */
@Serializable
data class PingtunnelConfig(
    /** 服务器地址（-s），支持域名或 IP，客户端会定期重新解析域名。 */
    val serverHost: String = "",
    /**
     * 旧式纯数字密码（-key，int 类型 0..2147483647）。
     * 仅在 [encryptMode] 为空时使用。
     */
    val key: Long = 0,
    /**
     * 加密模式（-encrypt）：空 = 不启用加密（走旧式 -key 弱鉴权）；
     * 否则 aes128 / aes256 / chacha20（AES-GCM / ChaCha20-Poly1305 AEAD）。
     */
    val encryptMode: String = "",
    /** 加密密钥（-encrypt-key）：16/32 字节 base64 直用，否则 PBKDF2 派生。 */
    val encryptKey: String = "",
    /** 连接不活跃超时秒数（-timeout）。 */
    val timeout: Int = 60,
    /** tcp 模式发送接收缓冲区大小字节（-tcp_bs）。 */
    val tcpBufferSize: Int = DEFAULT_TCP_BUFFER_SIZE,
    /** tcp 模式最大窗口（-tcp_mw），受 FRAME_MAX_ID/10 = 100000 上限约束。 */
    val tcpMaxWin: Int = DEFAULT_TCP_MAX_WIN,
    /** tcp 模式超时重传毫秒（-tcp_rst）。 */
    val tcpResendTimems: Int = DEFAULT_TCP_RESEND_TIME_MS,
    /** tcp 模式数据超过该大小启用压缩（-tcp_gz），0 不压缩。 */
    val tcpCompress: Int = 0,
    /** 最大连接数（-maxconn），0 不限制。 */
    val maxConn: Int = 0,
    /** 拥塞控制算法（-congestion），默认 bb（带宽自适应），空串关闭。 */
    val congestion: String = DEFAULT_CONGESTION,
    /** 是否接管 IPv6（addRoute ::/0）；关闭时仅代理 IPv4。 */
    val enableIpv6: Boolean = true,
) {
    /** 是否启用 AEAD 加密（加密模式与旧式 -key 二选一）。 */
    val encrypted: Boolean
        get() = encryptMode != NO_ENCRYPT

    /** 列表上展示的协议名：pingtunnel 固定走 ICMP。 */
    val displayProto: String
        get() = "icmp"

    /** 返回首个校验错误码；null 表示合法。 */
    fun validate(): ValidationError? = when {
        serverHost.isBlank() -> ValidationError.HOST_REQUIRED
        // 白名单只约束加密模式（编辑器下拉之外的取值只能来自扫码/文件导入，
        // 不应原样拼给 pingtunnel 子进程）
        encryptMode !in ENCRYPT_MODES -> ValidationError.ENCRYPT_MODE_UNSUPPORTED
        encrypted && encryptKey.isBlank() -> ValidationError.ENCRYPT_KEY_REQUIRED
        !encrypted && (key < 0 || key > MAX_KEY) -> ValidationError.KEY_RANGE
        tcpMaxWin * 10 > FRAME_MAX_ID -> ValidationError.TCP_MAXWIN_TOO_BIG
        // 越界的数值只会让内核静默行为异常，提前在校验层拦截
        timeout <= 0 || tcpBufferSize <= 0 || tcpResendTimems <= 0 ||
            tcpCompress < 0 || maxConn < 0 -> ValidationError.TCP_PARAMS_RANGE
        else -> null
    }

    companion object {
        const val NO_ENCRYPT = ""
        val ENCRYPT_MODES = listOf(NO_ENCRYPT, "aes128", "aes256", "chacha20")

        /** -key 为 Go int（32 位），合法范围 0..2147483647。 */
        const val MAX_KEY = 2_147_483_647L

        /** gohome FrameMgr 的帧 ID 上限（pingtunnel.go FRAME_MAX_ID）。 */
        const val FRAME_MAX_ID = 1_000_000

        const val DEFAULT_TCP_BUFFER_SIZE = 1 * 1024 * 1024
        const val DEFAULT_TCP_MAX_WIN = 20_000
        const val DEFAULT_TCP_RESEND_TIME_MS = 400
        const val DEFAULT_CONGESTION = "bb"
    }
}
