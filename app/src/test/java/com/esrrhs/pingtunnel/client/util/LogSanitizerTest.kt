package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSanitizerTest {

    private val esc = 27.toChar()

    @Test
    fun plainLine_isUnchanged() {
        assertEquals("[INFO] nothing here", LogSanitizer.stripAnsi("[INFO] nothing here"))
    }

    @Test
    fun leadingColorCode_isStripped() {
        val raw = "${esc}[38;5;46m[INFO] started"
        assertEquals("[INFO] started", LogSanitizer.stripAnsi(raw))
    }

    @Test
    fun resetAndColorCodes_areBothStripped() {
        val raw = "${esc}[0;00m${esc}[38;5;46m[INFO] ok"
        assertEquals("[INFO] ok", LogSanitizer.stripAnsi(raw))
    }

    @Test
    fun emptyString_isUnchanged() {
        assertEquals("", LogSanitizer.stripAnsi(""))
    }
}
