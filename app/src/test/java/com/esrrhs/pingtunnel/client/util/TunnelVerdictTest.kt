package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelVerdictTest {

    @Test
    fun differentIps_meansProxyWorks() {
        assertEquals(
            TunnelStatus.PROXY_OK,
            TunnelVerdict.of(directIp = "1.2.3.4", proxyIp = "5.6.7.8"),
        )
    }

    @Test
    fun sameIp_warns() {
        assertEquals(
            TunnelStatus.SAME_IP,
            TunnelVerdict.of(directIp = "1.2.3.4", proxyIp = "1.2.3.4"),
        )
    }

    @Test
    fun sameIpWithSurroundingWhitespace_stillSame() {
        assertEquals(
            TunnelStatus.SAME_IP,
            TunnelVerdict.of(directIp = " 1.2.3.4\n", proxyIp = "1.2.3.4 "),
        )
    }

    @Test
    fun onlyDirectWorks_proxyFailed() {
        assertEquals(
            TunnelStatus.PROXY_FAILED,
            TunnelVerdict.of(directIp = "1.2.3.4", proxyIp = null),
        )
        assertEquals(
            TunnelStatus.PROXY_FAILED,
            TunnelVerdict.of(directIp = "1.2.3.4", proxyIp = "  "),
        )
    }

    @Test
    fun neitherWorks_networkFailed() {
        assertEquals(TunnelStatus.NETWORK_FAILED, TunnelVerdict.of(null, null))
        assertEquals(TunnelStatus.NETWORK_FAILED, TunnelVerdict.of("", ""))
    }
}
