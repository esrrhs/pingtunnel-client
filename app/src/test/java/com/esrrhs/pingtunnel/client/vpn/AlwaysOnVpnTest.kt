package com.esrrhs.pingtunnel.client.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnVpnTest {

    @Test
    fun isEnabledFor_matchingPackage_isTrue() {
        assertTrue(AlwaysOnVpn.isEnabledFor("com.esrrhs.pingtunnel.client", "com.esrrhs.pingtunnel.client"))
    }

    @Test
    fun isEnabledFor_otherVpnSelected_isFalse() {
        // 系统里配的是别的 VPN：不能误报"已开启"
        assertFalse(AlwaysOnVpn.isEnabledFor("com.other.vpn", "com.esrrhs.pingtunnel.client"))
    }

    @Test
    fun isEnabledFor_unset_isFalse() {
        assertFalse(AlwaysOnVpn.isEnabledFor(null, "com.esrrhs.pingtunnel.client"))
        assertFalse(AlwaysOnVpn.isEnabledFor("", "com.esrrhs.pingtunnel.client"))
        assertFalse(AlwaysOnVpn.isEnabledFor("   ", "com.esrrhs.pingtunnel.client"))
    }
}
