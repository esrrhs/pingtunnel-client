package com.esrrhs.pingtunnel.client.pingtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingtunnelConfigTest {

    private fun validConfig() = PingtunnelConfig(
        serverHost = "example.com",
        password = "secret-pass",
    )

    @Test
    fun defaultConfig_isInvalidBecauseHostMissing() {
        assertEquals(ValidationError.HOST_REQUIRED, PingtunnelConfig().validate())
    }

    @Test
    fun blankHost_reportsHostError() {
        val config = validConfig().copy(serverHost = "   ")
        assertEquals(ValidationError.HOST_REQUIRED, config.validate())
    }

    @Test
    fun validConfig_passesValidation() {
        assertNull(validConfig().validate())
    }

    @Test
    fun blankPassword_isRejected() {
        // v3 起密码必填：认证与加密一体
        assertEquals(ValidationError.PASSWORD_REQUIRED, validConfig().copy(password = "").validate())
    }

    @Test
    fun shortPassword_isRejected() {
        assertEquals(
            ValidationError.PASSWORD_TOO_SHORT,
            validConfig().copy(password = "1234567").validate(),
        )
    }

    @Test
    fun passwordAtMinLength_isValid() {
        assertNull(validConfig().copy(password = "12345678").validate())
    }

    @Test
    fun defaults_areExpected() {
        val config = PingtunnelConfig()
        assertEquals("", config.password)
        assertEquals("", config.cipher)
        assertEquals(60, config.timeout)
        assertEquals(1 * 1024 * 1024, config.tcpBufferSize)
        assertEquals(20_000, config.tcpMaxWin)
        assertEquals(400, config.tcpResendTimems)
        assertEquals(0, config.tcpCompress)
        assertEquals(0, config.maxConn)
        assertEquals("bb", config.congestion)
        assertTrue(config.enableIpv6)
    }

    @Test
    fun displayProto_isIcmp() {
        assertEquals("icmp", validConfig().displayProto)
    }

    @Test
    fun ciphers_containAesOverridesOnly() {
        // v3：流量始终加密，"" 为默认 ChaCha20，AES 仅作算法覆盖
        assertTrue(PingtunnelConfig.CIPHERS.contains(""))
        assertTrue(PingtunnelConfig.CIPHERS.contains("aes256"))
        assertTrue(PingtunnelConfig.CIPHERS.contains("aes128"))
    }
}
