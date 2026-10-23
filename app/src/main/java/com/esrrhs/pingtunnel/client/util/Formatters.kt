package com.esrrhs.pingtunnel.client.util

/**
 * 展示用格式化工具。
 */
internal object Formatters {

    /** 自适应单位格式化字节数（B/KB/MB/GB/TB），保留一位小数。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        do {
            value /= 1024.0
            unitIndex++
        } while (value >= 1024 && unitIndex < units.lastIndex)
        return String.format("%.1f%s", value, units[unitIndex])
    }

    /** 速率：自适应字节单位 + /s。 */
    fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

    /** 时长：0 秒 / 12 秒 / 3分05秒 / 1小时02分。 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0秒"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}小时${"%02d".format(minutes)}分"
            minutes > 0 -> "${minutes}分${"%02d".format(seconds)}秒"
            else -> "${seconds}秒"
        }
    }
}
