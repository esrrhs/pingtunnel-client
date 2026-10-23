package com.esrrhs.pingtunnel.client.vpn

import com.esrrhs.pingtunnel.client.pingtunnel.PerAppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppRoutingResolverTest {

    private val self = "com.esrrhs.pingtunnel.client"
    private val appA = "com.example.a"
    private val appB = "com.example.b"

    @Test
    fun allModeOnlyDisallowsSelf() {
        val r = PerAppRoutingResolver.resolve(PerAppMode.ALL, listOf(appA, appB), self)
        assertTrue(r.allowedPackages.isEmpty())
        assertEquals(setOf(self), r.disallowedPackages)
    }

    @Test
    fun allowedModeDisallowsSelfEvenWhenSelfSelected() {
        // 关键不变量：白名单模式下自身也必须在黑名单，否则本进程流量回环进 TUN
        val r = PerAppRoutingResolver.resolve(
            PerAppMode.ALLOWED,
            listOf(appA, self, appB, appA), // 含自身 + 重复
            self,
        )
        assertEquals(setOf(appA, appB), r.allowedPackages)
        assertTrue(self in r.disallowedPackages)
        assertFalse("self must never be whitelisted", self in r.allowedPackages)
    }

    @Test
    fun allowedModeNormalCaseStillDisallowsSelf() {
        val r = PerAppRoutingResolver.resolve(PerAppMode.ALLOWED, listOf(appA), self)
        assertEquals(setOf(appA), r.allowedPackages)
        assertEquals(setOf(self), r.disallowedPackages)
    }

    @Test
    fun disallowedModeIncludesSelfWithoutDuplication() {
        val r = PerAppRoutingResolver.resolve(
            PerAppMode.DISALLOWED,
            listOf(appA, self, appB),
            self,
        )
        assertTrue(r.allowedPackages.isEmpty())
        assertEquals(setOf(appA, appB, self), r.disallowedPackages)
    }

    @Test
    fun selfNeverAppearsInBothListsForAnyMode() {
        // 同一个包同时 addAllowed + addDisallowed 会让 Builder 抛异常
        PerAppMode.entries.forEach { mode ->
            val r = PerAppRoutingResolver.resolve(mode, listOf(self, appA), self)
            assertTrue("$mode: self missing from disallow", self in r.disallowedPackages)
            assertFalse("$mode: self leaked into allow", self in r.allowedPackages)
        }
    }

    @Test
    fun emptyPackageListStillGuardsSelf() {
        PerAppMode.entries.forEach { mode ->
            val r = PerAppRoutingResolver.resolve(mode, emptyList(), self)
            assertTrue(self in r.disallowedPackages)
        }
    }
}
