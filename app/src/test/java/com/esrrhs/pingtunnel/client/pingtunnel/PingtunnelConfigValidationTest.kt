package com.esrrhs.pingtunnel.client.pingtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingtunnelConfigValidationTest {

    private fun base(
        host: String = "example.com",
        key: Long = 123456L,
        encryptMode: String = "",
        encryptKey: String = "",
    ) = PingtunnelConfig(
        serverHost = host,
        key = key,
        encryptMode = encryptMode,
        encryptKey = encryptKey,
    )

    @Test
    fun legacyKeyBoundaries_areAccepted() {
        // -key 为 Go int32：0..2147483647 均合法
        assertNull(base(key = 0L).validate())
        assertNull(base(key = PingtunnelConfig.MAX_KEY).validate())
    }

    @Test
    fun legacyKeyOutOfRange_isRejected() {
        assertEquals(ValidationError.KEY_RANGE, base(key = -1L).validate())
        assertEquals(ValidationError.KEY_RANGE, base(key = PingtunnelConfig.MAX_KEY + 1).validate())
    }

    @Test
    fun unknownEncryptModeRejected() {
        // 外部导入（扫码/文件）可携带任意字符串，白名单外不应拼给 pingtunnel 子进程
        assertEquals(
            ValidationError.ENCRYPT_MODE_UNSUPPORTED,
            base(encryptMode = "rot13").validate(),
        )
    }

    @Test
    fun encryptKeyRequiredWhenModeSet() {
        assertEquals(
            ValidationError.ENCRYPT_KEY_REQUIRED,
            base(encryptMode = "aes256", encryptKey = "").validate(),
        )
    }

    @Test
    fun encryptKeyBlankIgnoredWhenModeNone() {
        // 不加密时 encryptKey 无意义，不应报错
        assertNull(base(encryptMode = "", encryptKey = "").validate())
    }

    @Test
    fun tcpMaxWinTooBig_isRejected() {
        // FrameMgr 约束：maxwin*10 <= FRAME_MAX_ID(1000000)
        assertEquals(
            ValidationError.TCP_MAXWIN_TOO_BIG,
            base().copy(tcpMaxWin = PingtunnelConfig.FRAME_MAX_ID / 10 + 1).validate(),
        )
    }

    @Test
    fun tcpMaxWinBoundary_isAccepted() {
        assertNull(base().copy(tcpMaxWin = PingtunnelConfig.FRAME_MAX_ID / 10).validate())
    }

    @Test
    fun nonPositiveTcpParams_areRejected() {
        assertEquals(ValidationError.TCP_PARAMS_RANGE, base().copy(timeout = 0).validate())
        assertEquals(ValidationError.TCP_PARAMS_RANGE, base().copy(tcpBufferSize = 0).validate())
        assertEquals(ValidationError.TCP_PARAMS_RANGE, base().copy(tcpResendTimems = -1).validate())
        assertEquals(ValidationError.TCP_PARAMS_RANGE, base().copy(tcpCompress = -1).validate())
        assertEquals(ValidationError.TCP_PARAMS_RANGE, base().copy(maxConn = -5).validate())
    }

    @Test
    fun hostCheckedFirstEvenWhenOtherFieldsAlsoBad() {
        val both = PingtunnelConfig(serverHost = "", key = -1L)
        assertEquals(ValidationError.HOST_REQUIRED, both.validate())
    }
}
