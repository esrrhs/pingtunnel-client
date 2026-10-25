package com.esrrhs.pingtunnel.client.pingtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingtunnelConfigValidationTest {

    private fun base(
        host: String = "example.com",
        password: String = "secret-pass",
        cipher: String = PingtunnelConfig.CIPHER_DEFAULT,
    ) = PingtunnelConfig(
        serverHost = host,
        password = password,
        cipher = cipher,
    )

    @Test
    fun validPasswordAtBoundary_isAccepted() {
        assertNull(base(password = "a".repeat(PingtunnelConfig.MIN_PASSWORD_LEN)).validate())
    }

    @Test
    fun passwordBelowMinLength_isRejected() {
        assertEquals(
            ValidationError.PASSWORD_TOO_SHORT,
            base(password = "a".repeat(PingtunnelConfig.MIN_PASSWORD_LEN - 1)).validate(),
        )
    }

    @Test
    fun blankPasswordRejected_evenAtMinLengthBoundary() {
        assertEquals(ValidationError.PASSWORD_REQUIRED, base(password = "").validate())
    }

    @Test
    fun unknownCipherRejected() {
        // 外部导入（扫码/文件）可携带任意字符串，白名单外不应拼给 pingtunnel 子进程
        assertEquals(ValidationError.CIPHER_UNSUPPORTED, base(cipher = "rot13").validate())
    }

    @Test
    fun cipherOverrides_areAccepted() {
        assertNull(base(cipher = "aes256").validate())
        assertNull(base(cipher = "aes128").validate())
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
        val both = PingtunnelConfig(serverHost = "", password = "")
        assertEquals(ValidationError.HOST_REQUIRED, both.validate())
    }
}
