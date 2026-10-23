package com.esrrhs.pingtunnel.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationGateTest {

    @Test
    fun tokenIssuedBeforeDismiss_isCurrent() {
        val gate = GenerationGate()
        val token = gate.token()
        assertTrue(gate.isCurrent(token))
    }

    @Test
    fun bumpInvalidatesOutstandingToken() {
        val gate = GenerationGate()
        val token = gate.token()
        // 用户在结果回来前关闭弹窗
        gate.bump()
        assertFalse(gate.isCurrent(token))
    }

    @Test
    fun tokenIssuedAfterBump_isCurrentAgain() {
        val gate = GenerationGate()
        val stale = gate.token()
        gate.bump()
        // 关闭后重新发起检测，新协程拿到新 token
        val fresh = gate.token()
        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(fresh))
    }

    @Test
    fun multipleBumpsOnlyLatestTokenSurvives() {
        val gate = GenerationGate()
        val t0 = gate.token()
        gate.bump()
        val t1 = gate.token()
        gate.bump()
        gate.bump()
        assertFalse(gate.isCurrent(t0))
        assertFalse(gate.isCurrent(t1))
        assertTrue(gate.isCurrent(gate.token()))
    }

    @Test
    fun twoConcurrentRunsShareSameGenerationUntilDismiss() {
        // 正常路径下不会并发（Running 单飞），但同代 token 都应被接受
        val gate = GenerationGate()
        val a = gate.token()
        val b = gate.token()
        assertTrue(gate.isCurrent(a))
        assertTrue(gate.isCurrent(b))
    }
}
