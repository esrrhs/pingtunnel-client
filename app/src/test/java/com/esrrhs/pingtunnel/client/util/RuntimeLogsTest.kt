package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeLogsTest {

    @Test
    fun trimKeepsTailAndClearsLiveFile() {
        val dir = File.createTempFile("logs", "").apply {
            delete()
            mkdirs()
        }
        val live = File(dir, "hev.log")
        val body = StringBuilder()
        var n = 0
        while (body.length < RuntimeLogs.MAX_BYTES + 4096) {
            body.append("line ").append(n++).append(" payload\n")
        }
        live.writeText(body.toString())
        val rotatedBefore = File(dir, "hev.log.1")
        rotatedBefore.writeBytes(ByteArray((RuntimeLogs.KEEP_BYTES + 1000).toInt()) { 'x'.code.toByte() })

        RuntimeLogs.trim(live)

        assertTrue(live.length() == 0L)
        val rotated = File(dir, "hev.log.1")
        assertTrue(rotated.length() in 1..RuntimeLogs.KEEP_BYTES)
        assertTrue(rotated.readText().trim().endsWith("payload"))
        dir.deleteRecursively()
    }
}
