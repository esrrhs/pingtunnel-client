package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRegionPresetTest {

    @Test
    fun fromCode_caseInsensitive() {
        assertEquals(DnsRegionPreset.IR, DnsRegionPreset.fromCode("ir"))
        assertEquals(DnsRegionPreset.RU, DnsRegionPreset.fromCode("RU"))
        assertEquals(DnsRegionPreset.NONE, DnsRegionPreset.fromCode("none"))
        assertEquals(DnsRegionPreset.CN, DnsRegionPreset.fromCode("unknown"))
        assertEquals(DnsRegionPreset.CN, DnsRegionPreset.fromCode(null))
    }

    @Test
    fun presets_containExpectedTldsAndDomains() {
        val ir = DnsRegionPreset.IR
        assertTrue(ir.directTlds.contains("ir"))
        assertTrue(ir.directDomains.contains("digikala.com"))

        val ru = DnsRegionPreset.RU
        assertTrue(ru.directTlds.contains("ru"))
        assertTrue(ru.directDomains.contains("yandex.ru"))

        val none = DnsRegionPreset.NONE
        assertTrue(none.directTlds.isEmpty())
        assertTrue(none.directDomains.isEmpty())
    }

    @Test
    fun languages_containAllRequired() {
        assertEquals(AppLanguage.FA, AppLanguage.fromCode("fa"))
        assertEquals(AppLanguage.RU, AppLanguage.fromCode("ru"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
    }
}
