package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SocksProbeTest {

    @Test
    fun greeting_offeredNoAuthOnly() {
        val bytes = SocksProbe.greeting().toList()
        assertEquals(listOf<Byte>(0x05, 0x01, 0x00), bytes)
    }

    @Test
    fun connectDomain_usesDomainAddressType() {
        val packet = SocksProbe.connectDomain("example.com", 443).toList()
        // VER CMD RSV ATYP LEN DOMAIN PORT(2)
        assertEquals(0x05.toByte(), packet[0])
        assertEquals(0x01.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x03.toByte(), packet[3])
        assertEquals("example.com".length, packet[4].toInt())
        assertEquals(
            "example.com",
            packet.subList(5, 5 + 11).map { it.toInt().toChar() }.joinToString(""),
        )
        assertEquals(0x01.toByte(), packet[packet.size - 2]) // 443 >> 8
        assertEquals(0xBB.toByte(), packet[packet.size - 1]) // 443
    }

    @Test
    fun connectDomain_rejectsOversizedHost() {
        assertThrows(IllegalArgumentException::class.java) {
            SocksProbe.connectDomain("a".repeat(256), 443)
        }
    }

    @Test
    fun connectDomain_rejectsEmptyHost() {
        assertThrows(IllegalArgumentException::class.java) {
            SocksProbe.connectDomain("", 443)
        }
    }

    @Test
    fun connectIPv4_usesIPv4AddressType() {
        val packet = SocksProbe.connectIPv4("1.2.3.4", 443).toList()
        // VER CMD RSV ATYP = 0x01，随后 4 字节地址、2 字节端口，共 10 字节
        assertEquals(10, packet.size)
        assertEquals(0x05.toByte(), packet[0])
        assertEquals(0x01.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x01.toByte(), packet[3])
        assertEquals(listOf<Byte>(1, 2, 3, 4), packet.subList(4, 8))
        assertEquals(0x01.toByte(), packet[8]) // 443 >> 8
        assertEquals(0xBB.toByte(), packet[9]) // 443
    }

    @Test
    fun connectIPv4_rejectsMalformed() {
        assertThrows(IllegalArgumentException::class.java) {
            SocksProbe.connectIPv4("1.2.3", 443)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SocksProbe.connectIPv4("1.2.3.256", 443)
        }
    }
}
