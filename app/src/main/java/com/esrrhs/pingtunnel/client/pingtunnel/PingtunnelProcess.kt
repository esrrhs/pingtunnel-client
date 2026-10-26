package com.esrrhs.pingtunnel.client.pingtunnel

import android.content.Context
import com.esrrhs.pingtunnel.client.R
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import com.esrrhs.pingtunnel.client.util.LOOPBACK_V4
import com.esrrhs.pingtunnel.client.util.LogSanitizer
import com.esrrhs.pingtunnel.client.util.isLoopbackListening
import java.util.concurrent.TimeUnit

open class PingtunnelException(message: String) : Exception(message)

/**
 * pingtunnel 会话认证失败（password 与服务器不匹配）。
 *
 * 保留异常类型以对齐上层故障切换逻辑；注意 pingtunnel 服务端对认证码
 * 不匹配的包静默丢包，目前没有确定性的日志标记，主要依赖上层
 * SocksProbe 端到端探测失败来触发重连/轮换。
 */
class PingtunnelAuthException(message: String) : PingtunnelException(message)

/**
 * 以独立子进程运行 pingtunnel client（打包为 jniLibs 里的 libpingtunnel.so，
 * 安装后位于 nativeLibraryDir，API 29+ 仍允许执行该目录下的文件）。
 *
 * 与 SPP 内核不同：pingtunnel 的本地 SOCKS5 端口在进程启动后立即绑定，
 * 不代表到 server 的隧道已可用（无显式握手；连通性靠周期 PING 测 RTT，
 * 弱鉴权失败时服务端静默丢包）。因此端口探测仅作为「进程就绪」判定，
 * 隧道真实可用性由上层 SocksProbe（CONNECT+TLS+HTTP 端到端）负责。
 *
 * 环路防护说明：`VpnService.protect()` 只能保护本进程创建的 fd，对子进程无效；
 * 本 App 采用 `addDisallowedApplication(自身包名)` 把整个 App 排除在 VPN 之外，
 * pingtunnel 子进程的出站 ICMP socket 天然绕过 TUN，不会形成「VPN 套 VPN」环路；
 * 而 hev-socks5-tunnel 在本进程内直接读写 TUN fd，不受 disallowed 影响。
 */
class PingtunnelProcess(context: Context) {

    private val appContext = context.applicationContext
    private var process: Process? = null
    private val outputLines = ArrayDeque<String>()

    /** 主动 stop 时置位，看门狗据此区分「意外退出」。 */
    @Volatile
    private var intentionallyStopped = false

    /** 进程意外退出（非 [stop]）回调；在守护线程触发，实现方需自行切线程。 */
    @Volatile
    var onUnexpectedExit: (() -> Unit)? = null

    /**
     * 启动 pingtunnel client（本地 SOCKS5 模式），阻塞直至本地端口开始监听。
     *
     * @param startupTimeoutMs 等待本地 SOCKS 端口上线的最长时间（含首次解析
     * server 域名的时间；服务器不可达时端口仍会绑定，隧道问题由上层探测发现）。
     * @return 本地 SOCKS5 监听端口（仅绑定 127.0.0.1）
     */
    fun start(config: PingtunnelConfig, startupTimeoutMs: Long = 15_000L): Int {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, "libpingtunnel.so")
        if (!binary.exists() || !binary.canExecute()) {
            throw PingtunnelException(appContext.getString(R.string.error_missing_native))
        }

        val port = findFreeLoopbackPort()
        val args = buildList {
            add(binary.absolutePath)
            add("-type"); add("client")
            add("-l"); add("127.0.0.1:$port")
            add("-s"); add(config.serverHost)
            // SOCKS5 模式（v3 为布尔开关）隐含 tcp 可靠传输，无需 -t 目标
            add("-socks5")
            // v3：单一密码负责认证与加密，流量始终加密（默认 ChaCha20-Poly1305）
            add("-password"); add(config.password)
            if (config.cipher != PingtunnelConfig.CIPHER_DEFAULT) {
                add("-encrypt"); add(config.cipher)
            }
            add("-timeout"); add(config.timeout.toString())
            if (config.maxConn > 0) {
                add("-maxconn"); add(config.maxConn.toString())
            }
            if (config.congestion.isNotBlank()) {
                add("-congestion"); add(config.congestion)
            }
            // Android 上进程 cwd 不可写，gohome/loggo 会往 cwd 写日志文件而崩溃；
            // 关闭其日志文件，输出走 stdout（下面已采集并落盘）
            add("-nolog")
        }

        intentionallyStopped = false
        synchronized(outputLines) { outputLines.clear() }
        val proc = ProcessBuilder(args)
            // pingtunnel 的日志写 stdout，合并两个流统一采集
            .redirectErrorStream(true)
            // 防御：万一有代码仍按相对路径写文件，落到应用私有目录而不是 /
            .directory(File(appContext.filesDir.absolutePath))
            .start()
        process = proc

        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { raw ->
                    // pingtunnel 的 stdout 带 ANSI 颜色码，剥除后再回放/落盘
                    val line = LogSanitizer.stripAnsi(raw)
                    synchronized(outputLines) {
                        outputLines.addLast(line)
                        while (outputLines.size > MAX_OUTPUT_LINES) outputLines.removeFirst()
                    }
                    appendToLogFile(line)
                }
            }
        }.apply { isDaemon = true; name = "pingtunnel-output" }.start()

        Thread {
            runCatching { proc.waitFor() }
            if (!intentionallyStopped) {
                android.util.Log.w(TAG, "pingtunnel process exited unexpectedly")
                onUnexpectedExit?.invoke()
            }
        }.apply { isDaemon = true; name = "pingtunnel-exit-watch" }.start()

        if (!waitUntilListening(port, startupTimeoutMs)) {
            val alive = proc.isAlive
            val tail = synchronized(outputLines) { outputLines.joinToString("\n") }
            stop()
            val detail = tail.ifBlank { appContext.getString(R.string.log_no_output) }
            val message = when {
                // 端口始终未监听：参数被拒绝或 ICMP socket 打不开（如系统禁用 ping socket）
                alive -> appContext.getString(R.string.error_pingtunnel_session, detail)
                else -> appContext.getString(R.string.error_pingtunnel_exited, detail)
            }
            throw PingtunnelException(message)
        }
        return port
    }

    fun stop() {
        val proc = process ?: return
        intentionallyStopped = true
        process = null
        proc.destroy()
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
    }

    /** 最近的输出（用于启动失败时报错回放）。 */
    fun recentOutput(): String = synchronized(outputLines) { outputLines.joinToString("\n") }

    private fun waitUntilListening(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var firstError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            val proc = process ?: return false
            if (!proc.isAlive) return false
            try {
                // 探测本机端口：绕过系统 ProxySelector + 固定 IPv4 回环，细节见 isLoopbackListening
                if (isLoopbackListening(port, 250)) {
                    android.util.Log.i(TAG, "socks port $port is listening")
                    return true
                }
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e
                    android.util.Log.w(TAG, "probe 127.0.0.1:$port -> ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Thread.sleep(100)
        }
        android.util.Log.w(TAG, "probe timeout, last error: ${firstError?.message}")
        return false
    }

    // 与 -l 的绑定域保持一致，用 IPv4 回环探测空闲端口
    private fun findFreeLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_V4)).use { it.localPort }

    /** 追加到 pingtunnel.log；由 ConnectionRecorder 定期截断，避免长时间运行占满存储。 */
    private fun appendToLogFile(line: String) {
        runCatching {
            val file = File(appContext.filesDir, LOG_FILE)
            file.appendText(line + "\n")
        }
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 50
        const val LOG_FILE = "pingtunnel.log"
        const val TAG = "PingtunnelProcess"

        /**
         * pingtunnel 服务端对 key/magic 不匹配的包静默丢弃，没有确定性的认证错误
         * 日志标记；鉴权失败表现为隧道不通，由上层 SocksProbe 探测兜底。
         */
        val AUTH_ERROR_MARKERS = emptyList<String>()
    }
}
