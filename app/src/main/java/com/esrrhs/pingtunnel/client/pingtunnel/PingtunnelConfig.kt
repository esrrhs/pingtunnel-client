package com.esrrhs.pingtunnel.client.pingtunnel

import kotlinx.serialization.Serializable

/**
 * pingtunnel 连接配置，字段与 pingtunnel v3 client 命令行参数一一对应
 * （见 README「pingtunnel 参数映射」）。传输层固定为 ICMP（借 ping socket 免 root），
 * 本地以 SOCKS5 模式监听（-socks5，隐含 tcp 可靠传输模式）。
 *
 * v3 起（tag 3.0，commit 0e728cd）认证与加密合并为单一 -password：
 * 流量始终加密（默认 ChaCha20-Poly1305），旧式数字 -key 与 -encrypt-key 已移除，
 * 与 v2 服务端不互通。
 */
@Serializable
data class PingtunnelConfig(
    /** 服务器地址（-s），支持域名或 IP，客户端会定期重新解析域名。 */
    val serverHost: String = "",
    /**
     * 共享密钥（-password）：认证与加密一体（认证码经 PBKDF2 从密钥派生），
     * 必须与服务端一致，最少 [MIN_PASSWORD_LEN] 个字符。
     */
    val password: String = "",
    /**
     * 加密算法覆盖（-encrypt）：空 = 默认 ChaCha20-Poly1305；
     * 可选 aes256 / aes128。v3 起流量始终加密，无法关闭。
     */
    val cipher: String = CIPHER_DEFAULT,
    /** 连接不活跃超时秒数（-timeout）。 */
    val timeout: Int = 60,
    /** @deprecated pingtunnel v3.1+ 自动管理缓冲区大小。保留用于旧配置兼容。 */
    @Deprecated("Managed automatically by pingtunnel v3.1+")
    val tcpBufferSize: Int = DEFAULT_TCP_BUFFER_SIZE,
    /** @deprecated pingtunnel v3.1+ 自动管理滑动窗口。保留用于旧配置兼容。 */
    @Deprecated("Managed automatically by pingtunnel v3.1+")
    val tcpMaxWin: Int = DEFAULT_TCP_MAX_WIN,
    /** @deprecated pingtunnel v3.1+ 自动管理超时重传。保留用于旧配置兼容。 */
    @Deprecated("Managed automatically by pingtunnel v3.1+")
    val tcpResendTimems: Int = DEFAULT_TCP_RESEND_TIME_MS,
    /** @deprecated pingtunnel v3.1+ 始终全量加密传输，无需压缩。保留用于旧配置兼容。 */
    @Deprecated("Managed automatically by pingtunnel v3.1+")
    val tcpCompress: Int = 0,
    /** 最大连接数（-maxconn），0 不限制。 */
    val maxConn: Int = 0,
    /** 拥塞控制算法（-congestion），默认 bb（带宽自适应），空串关闭。 */
    val congestion: String = DEFAULT_CONGESTION,
    /** 是否接管 IPv6（addRoute ::/0）；关闭时仅代理 IPv4。 */
    val enableIpv6: Boolean = true,
) {
    /** 列表上展示的协议名：pingtunnel 固定走 ICMP。 */
    val displayProto: String
        get() = "icmp"

    /** 返回首个校验错误码；null 表示合法。 */
    fun validate(): ValidationError? = when {
        serverHost.isBlank() -> ValidationError.HOST_REQUIRED
        // 白名单只约束算法（编辑器下拉之外的取值只能来自扫码/文件导入，
        // 不应原样拼给 pingtunnel 子进程）
        cipher !in CIPHERS -> ValidationError.CIPHER_UNSUPPORTED
        password.isBlank() -> ValidationError.PASSWORD_REQUIRED
        password.length < MIN_PASSWORD_LEN -> ValidationError.PASSWORD_TOO_SHORT
        tcpMaxWin * 10 > FRAME_MAX_ID -> ValidationError.TCP_MAXWIN_TOO_BIG
        // 越界的数值只会让内核静默行为异常，提前在校验层拦截
        timeout <= 0 || tcpBufferSize <= 0 || tcpResendTimems <= 0 ||
            tcpCompress < 0 || maxConn < 0 -> ValidationError.TCP_PARAMS_RANGE
        else -> null
    }

    companion object {
        /** 空串 = 不传 -encrypt，使用默认 ChaCha20-Poly1305。 */
        const val CIPHER_DEFAULT = ""
        val CIPHERS = listOf(CIPHER_DEFAULT, "aes256", "aes128")

        /** 与 pingtunnel v3 crypto.go 的 minPasswordLen 一致。 */
        const val MIN_PASSWORD_LEN = 8

        /** gohome FrameMgr 的帧 ID 上限（pingtunnel.go FRAME_MAX_ID）。 */
        const val FRAME_MAX_ID = 1_000_000

        const val DEFAULT_TCP_BUFFER_SIZE = 1 * 1024 * 1024
        const val DEFAULT_TCP_MAX_WIN = 20_000
        const val DEFAULT_TCP_RESEND_TIME_MS = 400
        const val DEFAULT_CONGESTION = "bb"
    }
}
