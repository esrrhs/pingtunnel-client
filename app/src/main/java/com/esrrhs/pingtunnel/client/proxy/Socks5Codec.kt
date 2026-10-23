package com.esrrhs.pingtunnel.client.proxy

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress

/**
 * SOCKS5 报文编解码（纯逻辑 + 流读写），供本地分流代理使用。
 * RFC 1928：METHOD 协商、CONNECT/UDP ASSOCIATE 请求、BND 地址解析。
 */
object Socks5Codec {

    const val VER: Byte = 0x05
    const val CMD_CONNECT: Byte = 0x01
    const val CMD_UDP_ASSOCIATE: Byte = 0x03
    const val ATYP_IPV4: Byte = 0x01
    const val ATYP_DOMAIN: Byte = 0x03
    const val ATYP_IPV6: Byte = 0x04

    const val REP_SUCCESS: Byte = 0x00
    const val REP_GENERAL_FAILURE: Byte = 0x01
    const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07

    data class Request(
        val command: Int,
        val atyp: Int,
        /** 域名（ATYP=3）或 IP 文本（ATYP=1/4）。 */
        val host: String,
        val port: Int,
        /** 原始请求字节（VER 起到端口结束），可直接转发给上游。 */
        val raw: ByteArray,
    )

    const val METHOD_NO_AUTH: Byte = 0x00
    const val METHOD_USERPASS: Byte = 0x02

    fun greeting(): ByteArray = byteArrayOf(VER, 0x01, METHOD_NO_AUTH)

    /**
     * 作为 SOCKS5 客户端完成方法协商；[username] 非空时走 RFC 1929 用户名密码。
     * 成功返回 true。用户名或密码超过 255 字节视为失败。
     */
    fun authenticateClient(
        input: DataInputStream,
        out: OutputStream,
        username: String,
        password: String,
    ): Boolean {
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        val useAuth = user.isNotEmpty()
        if (useAuth && (user.size > 255 || pass.size > 255)) return false
        out.write(
            if (useAuth) byteArrayOf(VER, 0x01, METHOD_USERPASS)
            else byteArrayOf(VER, 0x01, METHOD_NO_AUTH),
        )
        out.flush()
        if (input.readUnsignedByte() != 0x05) return false
        val method = input.readUnsignedByte()
        if (!useAuth) return method == METHOD_NO_AUTH.toInt()
        if (method != METHOD_USERPASS.toInt()) return false
        out.write(byteArrayOf(0x01, user.size.toByte()))
        out.write(user)
        out.write(byteArrayOf(pass.size.toByte()))
        out.write(pass)
        out.flush()
        return input.readUnsignedByte() == 0x01 && input.readUnsignedByte() == 0x00
    }

    /** 读取服务端侧的方法协商，仅接受 NO-AUTH；不支持时回复 0xFF 并返回 false。 */
    fun acceptMethod(input: DataInputStream, out: OutputStream): Boolean {
        val ver = input.readUnsignedByte()
        if (ver != 0x05) return false
        val n = input.readUnsignedByte()
        repeat(n) { input.readUnsignedByte() }
        // 始终回 NO-AUTH
        out.write(byteArrayOf(VER, 0x00))
        out.flush()
        return true
    }

    /** 解析 CONNECT / UDP ASSOCIATE 请求；报文非法抛 [IOException]。 */
    fun readRequest(input: DataInputStream): Request {
        val ver = input.readUnsignedByte()
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()
        require(ver == 0x05) { "bad socks version $ver" }
        val host = when (atyp) {
            0x01 -> {
                val b = ByteArray(4)
                input.readFully(b)
                b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.readUnsignedByte()
                val b = ByteArray(len)
                input.readFully(b)
                String(b, Charsets.US_ASCII)
            }
            0x04 -> {
                val b = ByteArray(16)
                input.readFully(b)
                (0..7).joinToString(":") { i ->
                    String.format(
                        "%02x%02x",
                        b[i * 2].toInt() and 0xFF,
                        b[i * 2 + 1].toInt() and 0xFF,
                    )
                }
            }
            else -> throw IOException("unsupported atyp $atyp")
        }
        val portHi = input.readUnsignedByte()
        val portLo = input.readUnsignedByte()
        val port = (portHi shl 8) or portLo

        // 重新序列化一份规整的请求用于上游转发
        val raw = buildRequest(cmd.toByte(), atyp, host, port)
        return Request(cmd, atyp, host, port, raw)
    }

    /** 构造请求报文（域名使用 ATYP=3，IP 按字面量判断 v4/v6）。 */
    fun buildRequest(cmd: Byte, atypHint: Int? = null, host: String, port: Int): ByteArray {
        val atyp = atypHint ?: when {
            host.contains(":") -> 0x04
            else -> 0x03
        }
        val head = byteArrayOf(VER, cmd, 0x00, atyp.toByte())
        val addr = when (atyp) {
            0x01 -> host.split(".").map { it.toInt().toByte() }.toByteArray()
            0x04 -> ipv6Bytes(host)
            else -> {
                val b = host.toByteArray(Charsets.US_ASCII)
                byteArrayOf(b.size.toByte()) + b
            }
        }
        return head + addr + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    /** 解析 IPv6 文本（支持 :: 压缩等所有标准格式）为 16 字节；解析失败返回 16 个 0。 */
    fun ipv6Bytes(text: String): ByteArray {
        val clean = text.removePrefix("[").removeSuffix("]")
        val parsed = runCatching { InetAddress.getByName(clean) }.getOrNull()
        if (parsed is java.net.Inet6Address) {
            return parsed.address
        }
        val parts = arrayOfNulls<String>(8)
        val split = clean.split("::")
        val head = if (split[0].isEmpty()) emptyList() else split[0].split(":")
        val tail = if (split.size == 2 && split[1].isNotEmpty()) split[1].split(":") else emptyList()
        head.forEachIndexed { i, g -> parts[i] = g }
        tail.forEachIndexed { i, g -> parts[8 - tail.size + i] = g }
        val out = ByteArray(16)
        for (i in 0..7) {
            val v = parts[i]?.toIntOrNull(16) ?: 0
            out[i * 2] = (v ushr 8).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        return out
    }

    /** 构造成功回复（监听本地回环端口时使用）。 */
    fun successReply(boundHost: String, boundPort: Int): ByteArray {
        val addr = byteArrayOf(ATYP_IPV4) +
            boundHost.split(".").map { it.toInt().toByte() }.toByteArray()
        return byteArrayOf(VER, REP_SUCCESS, 0x00) + addr +
            byteArrayOf((boundPort ushr 8).toByte(), boundPort.toByte())
    }

    fun failureReply(rep: Byte): ByteArray =
        byteArrayOf(VER, rep, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0)

    /** SOCKS5 UDP 报文承载的目标端点。 */
    data class UdpEndpoint(val atyp: Int, val host: String, val port: Int)

    /**
     * 解析 SOCKS5 UDP ASSOCIATE 数据报文（RSV(2) + FRAG + ATYP + ADDR + PORT + payload）。
     *
     * 返回 (端点, payload 起始偏移)；以下情况返回 null：
     * - RSV 不为 0 的非法报文；
     * - FRAG != 0（分片不支持，RFC 1928 允许直接丢弃）；
     * - 报文截断或 ATYP 未知。
     */
    fun parseUdpPacket(buf: ByteArray, len: Int): Pair<UdpEndpoint, Int>? {
        if (len < 4 || buf[0] != 0.toByte() || buf[1] != 0.toByte()) return null
        if (buf[2].toInt() and 0xFF != 0) return null // 不支持分片
        var offset = 3
        val atyp = buf[offset++].toInt() and 0xFF
        val host: String = when (atyp) {
            0x01 -> {
                if (len < offset + 4 + 2) return null
                val b = ByteArray(4)
                System.arraycopy(buf, offset, b, 0, 4)
                offset += 4
                b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                if (len < offset + 1) return null
                val dlen = buf[offset++].toInt() and 0xFF
                if (len < offset + dlen + 2) return null
                val b = ByteArray(dlen)
                System.arraycopy(buf, offset, b, 0, dlen)
                offset += dlen
                String(b, Charsets.US_ASCII)
            }
            0x04 -> {
                if (len < offset + 16 + 2) return null
                val b = ByteArray(16)
                System.arraycopy(buf, offset, b, 0, 16)
                offset += 16
                InetAddress.getByAddress(b).hostAddress?.substringBefore('%') ?: return null
            }
            else -> return null
        }
        if (len < offset + 2) return null
        val port = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        offset += 2
        return UdpEndpoint(atyp, host, port) to offset
    }

    /** 构造 SOCKS5 UDP 报文头（RSV/FRAG 为 0）；[atyp] 取 ATYP_* 常量。 */
    fun buildUdpHeader(atyp: Int, host: String, port: Int): ByteArray {
        val addr = when (atyp) {
            ATYP_IPV4.toInt() -> host.substringBefore('%').split(".")
                .map { it.toInt().toByte() }.toByteArray()
            ATYP_IPV6.toInt() -> ipv6Bytes(host.substringBefore('%'))
            else -> {
                val b = host.toByteArray(Charsets.US_ASCII)
                byteArrayOf(b.size.toByte()) + b
            }
        }
        return byteArrayOf(0, 0, 0, atyp.toByte()) + addr +
            byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    /** 读取上游回复，返回 BND 地址（host, port）；REP 非 0 或报文非法时返回 null。 */
    fun readReply(input: DataInputStream): Pair<String, Int>? {
        if (input.readUnsignedByte() != 0x05) return null
        val rep = input.readUnsignedByte()
        input.readUnsignedByte()
        if (rep != 0) return null
        val atyp = input.readUnsignedByte()
        val host = when (atyp) {
            0x01 -> {
                val b = ByteArray(4); input.readFully(b)
                b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.readUnsignedByte()
                val b = ByteArray(len); input.readFully(b)
                String(b, Charsets.US_ASCII)
            }
            0x04 -> {
                val b = ByteArray(16)
                input.readFully(b)
                (0 until 8).joinToString(":") { i ->
                    val v = ((b[i * 2].toInt() and 0xFF) shl 8) or
                        (b[i * 2 + 1].toInt() and 0xFF)
                    "%x".format(v)
                }
            }
            else -> return null
        }
        val port = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        return host to port
    }
}
