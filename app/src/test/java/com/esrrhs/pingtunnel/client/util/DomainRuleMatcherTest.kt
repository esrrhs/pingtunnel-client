package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRuleMatcherTest {

    @Test
    fun parse_ignoresBlankLinesCommentsAndNormalizesPrefixes() {
        val text = """
            # comment
            baidu.com

            .taobao.com
            *.tmall.com
              Example.COM
        """.trimIndent()
        assertEquals(
            setOf("baidu.com", "taobao.com", "tmall.com", "example.com"),
            DomainRuleMatcher.parse(text),
        )
    }

    @Test
    fun matches_apexAndSubdomains_caseInsensitive() {
        val rules = setOf("baidu.com")
        assertTrue(DomainRuleMatcher.matches("baidu.com", rules))
        assertTrue(DomainRuleMatcher.matches("www.baidu.com", rules))
        assertTrue(DomainRuleMatcher.matches("WWW.Baidu.Com.", rules))
    }

    @Test
    fun matches_doesNotOverlapUnrelatedSuffix() {
        val rules = setOf("baidu.com")
        assertFalse(DomainRuleMatcher.matches("notbaidu.com", rules))
        assertFalse(DomainRuleMatcher.matches("evilbaidu.com", rules))
    }

    @Test
    fun matches_nullBlankOrEmptyRules() {
        assertFalse(DomainRuleMatcher.matches(null, setOf("a.com")))
        assertFalse(DomainRuleMatcher.matches("  ", setOf("a.com")))
        assertFalse(DomainRuleMatcher.matches("a.com", emptySet()))
    }

    @Test
    fun bareTldFilter_keepsRegionalButDropsGenericGtlds() {
        // 地区性 TLD 保留（整后缀直连符合预期）
        assertTrue(DomainRuleMatcher.isBareTldAllowed("cn"))
        assertTrue(DomainRuleMatcher.isBareTldAllowed("xn--fiqs8s")) // .中国
        // 通用国际 TLD 丢弃，避免 *.top / *.wang 被整体判直连
        assertFalse(DomainRuleMatcher.isBareTldAllowed("top"))
        assertFalse(DomainRuleMatcher.isBareTldAllowed("wang"))
        assertFalse(DomainRuleMatcher.isBareTldAllowed("com"))
        // 普通具体域名一律保留
        assertTrue(DomainRuleMatcher.isBareTldAllowed("hupu.com"))
        assertTrue(DomainRuleMatcher.isBareTldAllowed("n.c9c.top"))
    }

    @Test
    fun nDotC9cTop_notMatchedAfterBareTldFiltered() {
        // 模拟内置表净化：含 top 裸 TLD 的原始集合经 isBareTldAllowed 过滤后
        val raw = setOf("hupu.com", "top", "cn")
        val sanitized = raw.filter(DomainRuleMatcher::isBareTldAllowed).toSet()
        assertFalse(DomainRuleMatcher.matches("n.c9c.top", sanitized))
        assertTrue(DomainRuleMatcher.matches("www.hupu.com", sanitized))
        assertTrue(DomainRuleMatcher.matches("example.cn", sanitized))
    }
}
