package com.esrrhs.pingtunnel.client.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class Socks5CodecTest {

    @Test
    fun greeting_isNoAuthOnly() {
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), Socks5Codec.greeting())
    }

    // ---------------- UDP 报文头 ----------------

    @Test
    fun udpHeader_ipv4RoundTripsWithPayloadOffset() {
        val payload = "hello".toByteArray()
        val header = Socks5Codec.buildUdpHeader(Socks5Codec.ATYP_IPV4.toInt(), "1.2.3.4", 53)
        val packet = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(payload, 0, packet, header.size, payload.size)

        val (endpoint, offset) = Socks5Codec.parseUdpPacket(packet, packet.size)!!
        assertEquals(Socks5Codec.ATYP_IPV4.toInt(), endpoint.atyp)
        assertEquals("1.2.3.4", endpoint.host)
        assertEquals(53, endpoint.port)
        assertEquals(header.size, offset)
        assertArrayEquals(payload, packet.copyOfRange(offset, packet.size))
    }

    @Test
    fun udpHeader_domainRoundTrips() {
        val header = Socks5Codec.buildUdpHeader(Socks5Codec.ATYP_DOMAIN.toInt(), "dns.alidns.com", 443)
        val parsed = Socks5Codec.parseUdpPacket(header, header.size)!!
        assertEquals(Socks5Codec.ATYP_DOMAIN.toInt(), parsed.first.atyp)
        assertEquals("dns.alidns.com", parsed.first.host)
        assertEquals(443, parsed.first.port)
    }

    @Test
    fun udpHeader_ipv6RoundTrips() {
        val header = Socks5Codec.buildUdpHeader(Socks5Codec.ATYP_IPV6.toInt(), "2001:db8::1", 5353)
        val parsed = Socks5Codec.parseUdpPacket(header, header.size)!!
        assertEquals(Socks5Codec.ATYP_IPV6.toInt(), parsed.first.atyp)
        // hostAddress 的压缩形式随 JDK 版本不同，按字节比较最稳妥
        assertArrayEquals(
            Socks5Codec.ipv6Bytes("2001:db8::1"),
            Socks5Codec.ipv6Bytes(parsed.first.host),
        )
        assertEquals(5353, parsed.first.port)
    }

    @Test
    fun udpPacket_rejectsFragmentsAndGarbage() {
        // FRAG != 0 → null（不支持分片）
        val frag = Socks5Codec.buildUdpHeader(Socks5Codec.ATYP_IPV4.toInt(), "1.1.1.1", 53)
        frag[2] = 1
        assertEquals(null, Socks5Codec.parseUdpPacket(frag, frag.size))
        // RSV 非零 → null
        val badRsv = frag.copyOf().also { it[2] = 0; it[1] = 5 }
        assertEquals(null, Socks5Codec.parseUdpPacket(badRsv, badRsv.size))
        // 截断 → null
        assertEquals(null, Socks5Codec.parseUdpPacket(ByteArray(3), 3))
    }

    @Test
    fun domainRequest_roundTripsThroughBuilder() {
        val raw = Socks5Codec.buildRequest(
            Socks5Codec.CMD_CONNECT,
            Socks5Codec.ATYP_DOMAIN.toInt(),
            "example.com",
            443,
        )
        // VER CMD RSV ATYP LEN DOMAIN PORT
        assertEquals(0x05.toByte(), raw[0])
        assertEquals(0x01.toByte(), raw[1])
        assertEquals(0x00.toByte(), raw[2])
        assertEquals(0x03.toByte(), raw[3])
        assertEquals(11, raw[4].toInt())
        assertEquals(
            "example.com",
            raw.copyOfRange(5, 16).toString(Charsets.US_ASCII),
        )
        assertEquals(0x01.toByte(), raw[raw.size - 2])
        assertEquals(0xBB.toByte(), raw[raw.size - 1])
    }

    @Test
    fun ipv6Text_roundTripsToBytes() {
        val bytes = Socks5Codec.ipv6Bytes("2001:db8::1")
        assertEquals(16, bytes.size)
        assertEquals(0x20.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertEquals(0x0d.toByte(), bytes[2])
        assertEquals(0xb8.toByte(), bytes[3])
        assertEquals(0x00.toByte(), bytes[14])
        assertEquals(0x01.toByte(), bytes[15])
    }

    @Test
    fun ipv6FullAddress_encodesAllGroups() {
        val bytes = Socks5Codec.ipv6Bytes("2001:0db8:0000:0000:0000:0000:0000:0001")
        assertEquals(0x20.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertEquals(0x01.toByte(), bytes[15])
        // 中间全 0
        for (i in 4..13) assertEquals(0.toByte(), bytes[i])
    }

    @Test
    fun readReply_parsesIpv6BindAddress() {
        val raw = byteArrayOf(5, 0, 0, 4) +
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1) +
            byteArrayOf(0x01, 0xBB.toByte())
        val reply = Socks5Codec.readReply(DataInputStream(ByteArrayInputStream(raw)))
        assertEquals("2001:db8:0:0:0:0:0:1", reply?.first)
        assertEquals(443, reply?.second)
    }

    @Test
    fun authenticateClient_userPassRoundTrip() {
        val serverIn = ByteArrayOutputStream()
        // server script: method 0x02, then auth status 0
        val fromServer = ByteArrayInputStream(byteArrayOf(0x05, 0x02, 0x01, 0x00))
        val ok = Socks5Codec.authenticateClient(
            DataInputStream(fromServer),
            serverIn,
            "esrrhs",
            "secret",
        )
        assertTrue(ok)
        val sent = serverIn.toByteArray()
        assertEquals(0x05.toByte(), sent[0])
        assertEquals(0x02.toByte(), sent[2])
        // 方法协商 3 字节后是 RFC 1929：VER ULEN user PLEN pass
        assertEquals(0x01.toByte(), sent[3])
        assertEquals(6, sent[4].toInt())
        assertEquals("esrrhs", sent.copyOfRange(5, 11).toString(Charsets.UTF_8))
    }

    @Test
    fun authenticateClient_rejectsOversizedUser() {
        val out = ByteArrayOutputStream()
        val ok = Socks5Codec.authenticateClient(
            DataInputStream(ByteArrayInputStream(byteArrayOf())),
            out,
            "a".repeat(256),
            "x",
        )
        assertFalse(ok)
        assertEquals(0, out.size())
    }

    @Test
    fun ipv4Request_encodesAddressBytes() {
        val raw = Socks5Codec.buildRequest(
            Socks5Codec.CMD_UDP_ASSOCIATE,
            Socks5Codec.ATYP_IPV4.toInt(),
            "0.0.0.0",
            0,
        )
        // VER CMD RSV ATYP 4B ADDR 2B PORT = 10
        assertEquals(10, raw.size)
        assertEquals(0x01.toByte(), raw[3])
        assertEquals(0x03.toByte(), raw[1])
    }
}
