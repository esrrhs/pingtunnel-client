package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedWifiTest {

    @Test
    fun unquote_stripsSystemQuotes() {
        assertEquals("Home", SsidNames.unquote("\"Home\""))
        assertEquals("Home", SsidNames.unquote("Home"))
    }

    @Test
    fun unquote_unknownOrBlank_becomesNull() {
        assertNull(SsidNames.unquote(null))
        assertNull(SsidNames.unquote(""))
        assertNull(SsidNames.unquote("   "))
        assertNull(SsidNames.unquote("<unknown ssid>"))
        assertNull(SsidNames.unquote("0x"))
    }

    @Test
    fun match_isExactAgainstSet() {
        val trusted = setOf("Home", "Office")
        assertTrue(TrustedWifi.isTrusted("Home", trusted))
        assertTrue(TrustedWifi.isTrusted("Office", trusted))
        assertFalse(TrustedWifi.isTrusted("Cafe", trusted))
        assertFalse(TrustedWifi.isTrusted("Hom", trusted))
    }

    @Test
    fun match_nullOrBlankSsid_neverTrusted() {
        assertFalse(TrustedWifi.isTrusted(null, setOf("Home")))
        assertFalse(TrustedWifi.isTrusted("  ", setOf("Home")))
        assertFalse(TrustedWifi.isTrusted("Home", emptySet()))
    }
}
