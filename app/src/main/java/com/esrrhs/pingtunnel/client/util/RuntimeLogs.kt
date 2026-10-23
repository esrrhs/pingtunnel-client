package com.esrrhs.pingtunnel.client.util

import java.io.File
import java.io.RandomAccessFile

/**
 * 运行日志体积上限。pingtunnel.log 由本进程追加；hev.log 由 hev 以 O_APPEND 写着，
 * 截断后新日志仍会从文件末尾接着写。滚动段只留尾部，避免 .1 自己再涨成大文件。
 */
object RuntimeLogs {

    const val MAX_BYTES = 256L * 1024
    const val KEEP_BYTES = 128L * 1024

    fun trim(file: File) {
        shrinkToTail(File(file.parentFile, "${file.name}.1"), KEEP_BYTES)
        if (!file.isFile || file.length() <= MAX_BYTES) return
        val tail = readTail(file, KEEP_BYTES)
        File(file.parentFile, "${file.name}.1").writeBytes(tail)
        RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

    private fun shrinkToTail(file: File, maxBytes: Long) {
        if (!file.isFile || file.length() <= maxBytes) return
        file.writeBytes(readTail(file, maxBytes))
    }

    private fun readTail(file: File, maxBytes: Long): ByteArray {
        val length = file.length()
        val keep = maxBytes.coerceAtMost(length).toInt()
        if (keep <= 0) return ByteArray(0)
        val raw = ByteArray(keep)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - keep)
            raf.readFully(raw)
        }
        val newline = raw.indexOf('\n'.code.toByte())
        val start = if (newline >= 0 && newline < raw.lastIndex) newline + 1 else 0
        return if (start == 0) raw else raw.copyOfRange(start, raw.size)
    }
}
