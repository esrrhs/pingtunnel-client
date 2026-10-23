package com.esrrhs.pingtunnel.client.util

/**
 * 把长文本切成适合 logd 单条负载的小块（按 UTF-8 字节计）。
 *
 * 一条 android.util.Log 的有效负载超过约 4KB 会被 logd 静默截断：调用方拼了
 * 几十上百 KB 的诊断串，实际只留下开头一小段，既浪费 CPU/内存又丢失诊断信息。
 *
 * 切分规则：
 * - 优先在行边界打包，保持每个逻辑行完整（正则按行匹配的消费方不受影响）；
 * - 单行超过单块上限时按字符硬切；
 * - 总量超过 [maxTotalBytes] 后停止追加，并在末尾补一条截断标记；
 * - 空串返回空列表（不产生无意义日志）。
 */
internal object LogcatChunks {

    /** 略小于 LOGGER_ENTRY_MAX_PAYLOAD(4068)，给 tag/优先级头留余量。 */
    const val MAX_CHUNK_BYTES = 3_800

    /** 单次诊断输出的总字节上限：典型会话表只有数 KB（=1 条），极端情况最多 ~6 条。 */
    const val MAX_TOTAL_BYTES = 24 * 1_024

    private const val TRUNCATION_MARKER = "...(diag truncated; %d more bytes)"

    fun chunk(
        text: String,
        maxChunkBytes: Int = MAX_CHUNK_BYTES,
        maxTotalBytes: Int = MAX_TOTAL_BYTES,
    ): List<String> {
        if (text.isEmpty()) return emptyList()

        // split('\n') 后给除最后一段外的行补回换行；text 以 '\n' 结尾时末段为空串（忽略）。
        val rawLines = text.split('\n')
        val pieces = ArrayList<String>(rawLines.size)
        for (i in rawLines.indices) {
            if (i < rawLines.lastIndex) {
                pieces += rawLines[i] + '\n'
            } else if (rawLines[i].isNotEmpty()) {
                pieces += rawLines[i]
            }
        }

        val allBytes = text.toByteArray(Charsets.UTF_8).size
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var totalBytes = 0
        var truncated = false

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
                currentBytes = 0
            }
        }

        for (piece in pieces) {
            for (seg in hardSplitByBytes(piece, maxChunkBytes)) {
                val segBytes = seg.toByteArray(Charsets.UTF_8).size
                if (totalBytes + segBytes > maxTotalBytes) {
                    truncated = true
                    break
                }
                if (currentBytes > 0 && currentBytes + segBytes > maxChunkBytes) flush()
                current.append(seg)
                currentBytes += segBytes
                totalBytes += segBytes
            }
            if (truncated) break
        }

        if (truncated) {
            flush()
            // 精确丢弃量：全文字节 - 已输出字节（标记本身不再计入）
            chunks.add(TRUNCATION_MARKER.format((allBytes - totalBytes).coerceAtLeast(0)))
        } else {
            flush()
        }
        return chunks
    }

    /** 单行超过 [maxBytes] 时按 UTF-8 字节硬切成若干段（不拆代理对）。 */
    private fun hardSplitByBytes(text: String, maxBytes: Int): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            // 代理对整体取，避免产出半个 emoji
            val cpLen = if (i + 1 < text.length && Character.isHighSurrogate(text[i]) &&
                Character.isLowSurrogate(text[i + 1])
            ) 2 else 1
            val part = text.substring(i, i + cpLen)
            val partBytes = part.toByteArray(Charsets.UTF_8).size
            if (bytes + partBytes > maxBytes && sb.isNotEmpty()) {
                out += sb.toString()
                sb.setLength(0)
                bytes = 0
            }
            sb.append(part)
            bytes += partBytes
            i += cpLen
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
