package com.esrrhs.pingtunnel.client.pingtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingtunnelConfigTest {

    private fun validConfig() = PingtunnelConfig(
        serverHost = "example.com",
        key = 123456L,
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
    fun zeroKey_isValid() {
        // pingtunnel 默认 key=0（不设密码），应视为合法
        assertNull(validConfig().copy(key = 0L).validate())
    }

    @Test
    fun encrypted_usesEncryptKeyInsteadOfLegacyKey() {
        val config = validConfig().copy(
            encryptMode = "aes256",
            encryptKey = "passphrase",
        )
        assertTrue(config.encrypted)
        assertNull(config.validate())
    }

    @Test
    fun notEncrypted_whenModeBlank() {
        assertFalse(validConfig().encrypted)
    }

    @Test
    fun defaults_areExpected() {
        val config = PingtunnelConfig()
        assertEquals(0L, config.key)
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
    fun encryptModes_containAesAndChacha() {
        assertTrue(PingtunnelConfig.ENCRYPT_MODES.contains(""))
        assertTrue(PingtunnelConfig.ENCRYPT_MODES.contains("aes128"))
        assertTrue(PingtunnelConfig.ENCRYPT_MODES.contains("aes256"))
        assertTrue(PingtunnelConfig.ENCRYPT_MODES.contains("chacha20"))
    }
}
