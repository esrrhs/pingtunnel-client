package com.esrrhs.pingtunnel.client.util

import java.math.BigInteger
import java.net.InetAddress

/**
 * 精确 CIDR 区间匹配器（纯 JVM 逻辑，便于单测）。
 *
 * 把同一地址族的 CIDR 合并为排序、不重叠的 [start, end) 大整数区间，
 * 查询用二分查找 O(log n)，零近似、零膨胀：
 *
 * - 8.8k 条 IPv4 chnroute 全部精确保留，不再像 TUN 路由聚合那样把 1.1.1.1
 *   误判进直连块；
 * - TUN 始终全量抓包（0.0.0.0/0），IP 字面量目标在用户态分流代理里
 *   用本匹配器判定本地直连，彻底绕开 VpnService 路由表 Binder parcel
 *   （Android 15 NetworkMonitor TransactionTooLargeException）的尺寸限制。
 *
 * 一个实例只承载一个地址族（IPv4 = 4 字节 / IPv6 = 16 字节），不允许混族；
 * 空 CIDR 列表得到永不为真的匹配器。
 */
class IpRouteMatcher(cidrs: List<String>) {

    /** 4（IPv4）/ 16（IPv6）；0 表示空匹配器。 */
    private val width: Int
    private val starts: Array<BigInteger>
    private val ends: Array<BigInteger>

    val isEmpty: Boolean get() = starts.isEmpty()

    /** 合并后的区间数量（测试/诊断用）。 */
    internal val rangeCount: Int get() = starts.size

    init {
        if (cidrs.isEmpty()) {
            width = 0
            starts = emptyArray()
            ends = emptyArray()
        } else {
            val parsed = cidrs.map { parseCidr(it) }
            val w = parsed.first().third
            require(parsed.all { it.third == w }) { "mixed IPv4/IPv6 CIDRs in one matcher" }
            width = w

            // 按起始地址排序后合并相交/相邻（相邻也合并，区间数量更少，结果等价）
            val sorted = parsed.sortedBy { it.first }
            val merged = mutableListOf<Pair<BigInteger, BigInteger>>()
            for ((start, end, _) in sorted) {
                val last = merged.lastOrNull()
                if (last != null && start <= last.second) {
                    if (end > last.second) {
                        merged[merged.lastIndex] = last.first to end
                    }
                } else {
                    merged.add(start to end)
                }
            }
            starts = Array(merged.size) { merged[it].first }
            ends = Array(merged.size) { merged[it].second }
        }
    }

    /** 裸字节地址（网络字节序）是否命中任一区间。 */
    fun contains(ip: ByteArray): Boolean {
        if (ip.size != width || starts.isEmpty()) return false
        return contains(BigInteger(1, ip))
    }

    /** 大整数地址是否命中（内部二分）。 */
    private fun contains(value: BigInteger): Boolean {
        // 找最后一个 start <= value 的区间，再判 value < end
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= value) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return false
        return value < ends[found]
    }

    /** 文本字面量 IP（不触发 DNS）是否命中；非法文本返回 false。 */
    fun contains(ipLiteral: String): Boolean {
        val bytes = runCatching { parseLiteral(ipLiteral) }.getOrNull() ?: return false
        return bytes.size == width && contains(bytes)
    }

    private companion object {

        /** 解析 "1.2.3.0/24" / "2001:db8::/32"（可省略掩码=主机路由）为 (start, end, width)。 */
        fun parseCidr(text: String): Triple<BigInteger, BigInteger, Int> {
            val raw = text.trim()
            val slash = raw.indexOf('/')
            val addrText = if (slash >= 0) raw.substring(0, slash) else raw
            val bytes = parseLiteral(addrText)
            val w = bytes.size
            require(w == 4 || w == 16) { "unsupported CIDR: $text" }
            val maxBits = w * 8
            val prefix = if (slash >= 0) {
                raw.substring(slash + 1).trim().toInt().coerceIn(0, maxBits)
            } else {
                maxBits
            }
            val hostBits = maxBits - prefix

            val startValue = BigInteger(1, bytes).shiftRight(hostBits).shiftLeft(hostBits)
            // end = start + 2^hostBits：/0 时自然得到 2^32 / 2^128（定宽地址空间的末端+1）
            val endValue = startValue.add(BigInteger.ONE.shiftLeft(hostBits))
            return Triple(startValue, endValue, w)
        }

        /**
         * 仅解析 IP 字面量（v4/v6），不接受域名，避免意外触发 DNS。
         * v4 手工严格解析；v6 先做字符白名单再交给系统解析器。
         */
        fun parseLiteral(text: String): ByteArray {
            val t = text.trim()
            if (t.contains(':')) {
                require(t.all { it.isDigit() || it == ':' || it == '.' || it in "abcdefABCDEF" }) {
                    "not an IPv6 literal: $text"
                }
                val bytes = InetAddress.getByName(t).address
                require(bytes.size == 16) { "not an IPv6 literal: $text" }
                return bytes
            }
            val parts = t.split('.')
            require(parts.size == 4) { "not an IPv4 literal: $text" }
            val out = ByteArray(4)
            parts.forEachIndexed { i, p ->
                val v = p.toIntOrNull()
                // 拒绝空段、超范围与前导 +/0x 等写法
                require(v != null && v in 0..255 && v.toString() == p) { "bad IPv4 literal: $text" }
                out[i] = v.toByte()
            }
            return out
        }
    }
}
