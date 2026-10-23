package com.esrrhs.pingtunnel.client.util

/**
 * 日志清洗工具。SPP 子进程的 stdout 带 ANSI 颜色码，落盘/回显前需剥除。
 */
internal object LogSanitizer {

    /** ESC 由码值构造，避免在源码中写易出错的转义字符。 */
    private val ansiPattern =
        Regex(Regex.escape(27.toChar().toString()) + "\\[[0-9;?]*[a-zA-Z]")

    /** 剥除一行中的 ANSI CSI 转义序列（颜色/光标等）。 */
    fun stripAnsi(line: String): String = line.replace(ansiPattern, "")
}
