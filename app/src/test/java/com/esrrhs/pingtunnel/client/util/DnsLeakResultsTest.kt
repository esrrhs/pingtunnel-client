package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsLeakResultsTest {

    @Test
    fun parsesDnsResolvers() {
        val body = """
            [
              {"type":"ip","ip":"5.6.7.8","country_name":"US"},
              {"type":"dns","ip":"8.8.8.8","country_name":"US","asn_name":"Google"},
              {"type":"dns","ip":"1.1.1.1","country_name":"AU","asn_name":"Cloudflare"},
              {"type":"conclusion","value":"done"}
            ]
        """.trimIndent()
        val resolvers = DnsLeakResults.parse(body)
        assertEquals(2, resolvers.size)
        assertEquals("8.8.8.8", resolvers[0].ip)
        assertEquals("Google", resolvers[0].asnName)
        assertEquals("1.1.1.1", resolvers[1].ip)
        assertEquals("AU", resolvers[1].country)
    }

    @Test
    fun emptyOrInvalidJson_returnsEmpty() {
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse(""))
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse("not json"))
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse("[]"))
    }

    @Test
    fun dnsEntryWithoutIp_isSkipped() {
        val body = """[{"type":"dns","country_name":"US"}]"""
        assertEquals(emptyList<DnsResolver>(), DnsLeakResults.parse(body))
    }

    @Test
    fun nonStringFields_skipOnlyBadElementNotWholeResponse() {
        // 外部 API 字段类型不符时只丢该元素：合法的 dns 条目仍应解析出来
        val body = """
            [
              {"type":{"weird":1},"ip":"1.2.3.4"},
              {"type":"dns","ip":["8.8.8.8"],"country_name":"US","asn_name":"Google"},
              {"type":"dns","ip":"1.1.1.1","country_name":["AU"],"asn_name":{"x":1}},
              ["not","an","object"],
              "primitive",
              {"type":"dns","ip":"9.9.9.9","country_name":"US","asn_name":"Quad9"}
            ]
        """.trimIndent()
        val resolvers = DnsLeakResults.parse(body)
        // 第 2 条 ip 为数组 → 跳过；第 3 条 ip 正常但其余字段畸形 → 保留、畸形字段落空串；
        // 非对象元素跳过；最后一条完整保留
        assertEquals(2, resolvers.size)
        assertEquals("1.1.1.1", resolvers[0].ip)
        assertEquals("", resolvers[0].country)
        assertEquals("", resolvers[0].asnName)
        assertEquals("9.9.9.9", resolvers[1].ip)
        assertEquals("Quad9", resolvers[1].asnName)
    }

    @Test
    fun nullLiteralFieldsAreSafe() {
        val body = """[{"type":"dns","ip":"8.8.8.8","country_name":null,"asn_name":null}]"""
        val resolvers = DnsLeakResults.parse(body)
        assertEquals(1, resolvers.size)
        assertEquals("", resolvers[0].country)
        assertEquals("", resolvers[0].asnName)
    }
}
