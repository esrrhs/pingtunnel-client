package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatChunksTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun emptyProducesNoChunks() {
        assertTrue(LogcatChunks.chunk("").isEmpty())
    }

    @Test
    fun shortTextIsSingleChunkWithExactContent() {
        val chunks = LogcatChunks.chunk("17|1.2.3.4|5|6.7.8.9|19001|0|0|1|host\n")
        assertEquals(1, chunks.size)
        assertEquals("17|1.2.3.4|5|6.7.8.9|19001|0|0|1|host\n", chunks[0])
    }

    @Test
    fun packsMultipleLinesUntilChunkLimit() {
        val text = (1..10).joinToString("") { "line$it\n" }
        val chunks = LogcatChunks.chunk(text, maxChunkBytes = 40, maxTotalBytes = 10_000)
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size >= 2)
        chunks.forEach {
            assertTrue("chunk exceeds payload: ${bytes(it)}", bytes(it) <= 40)
        }
        // 不截断时可无损还原
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun singleOversizedLineIsHardSplitWithinChunkLimit() {
        val text = "x".repeat(5_000) // 单行无换行
        val chunks = LogcatChunks.chunk(text, maxChunkBytes = 1_000, maxTotalBytes = 10_000)
        assertTrue(chunks.size >= 5)
        chunks.forEach { assertTrue(bytes(it) <= 1_000) }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun totalCapAddsTruncationMarkerAndBoundsOutput() {
        val line = "y".repeat(500) + "\n" // 每行 501 字节
        val text = line.repeat(20)       // 共 10020 字节
        val chunks = LogcatChunks.chunk(text, maxChunkBytes = 1_000, maxTotalBytes = 2_000)
        val joined = chunks.joinToString("")
        // 末块是截断标记
        assertTrue(chunks.last().startsWith("...(diag truncated;"))
        // 除标记外的输出总量不超过上限
        val bodyBytes = chunks.dropLast(1).sumOf { bytes(it) }
        assertTrue("body=$bodyBytes", bodyBytes <= 2_000)
        // 行边界切分：正文部分仍由完整的 501 字节行构成
        chunks.dropLast(1).forEach { c ->
            c.split('\n').filter { it.isNotEmpty() }.forEach {
                assertEquals("line must stay intact", 500, it.length)
            }
        }
        assertTrue(joined.contains("more bytes"))
    }

    @Test
    fun multibyteCharactersAreNotSplitAcrossChunks() {
        // CJK 每字 3 字节、emoji 4 字节：切分不得拆坏代理对/字符
        val text = "中".repeat(400) + "😀".repeat(100)
        val chunks = LogcatChunks.chunk(text, maxChunkBytes = 500, maxTotalBytes = 10_000)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(bytes(it) <= 500) }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun trailingNewlineIsPreserved() {
        val chunks = LogcatChunks.chunk("a\nb\n", maxChunkBytes = 10, maxTotalBytes = 10_000)
        assertTrue(chunks.joinToString("").endsWith("\n"))
        assertTrue(chunks.none { it.isEmpty() })
    }
}
