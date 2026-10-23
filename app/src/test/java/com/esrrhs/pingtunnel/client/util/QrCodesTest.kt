package com.esrrhs.pingtunnel.client.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅调用纯 JVM 的 encodable（zxing core 无 Android 依赖）；
 * toBitmap 涉及 android.graphics.Bitmap，不在 JVM 单测覆盖。
 *
 * QR v40-L 容量随编码模式变化：数字 7089、字母数字 4296、字节 2953。
 * 真实分享文本是 pingtunnel:// + base64（含小写字母），zxing 只能走字节模式。
 */
class QrCodesTest {

    private fun base64Like(length: Int): String {
        val alphabet = "aB3xZ9qR7mK2pS8"
        return buildString(length) {
            repeat(length) { append(alphabet[it % alphabet.length]) }
        }
    }

    @Test
    fun typicalSharePayload_isEncodable() {
        // 典型 pingtunnel:// base64 配置（数百字节）
        assertTrue(QrCodes.encodable("pingtunnel://" + base64Like(500)))
    }

    @Test
    fun emptyText_isRejected() {
        // zxing 不接受空内容（真实 UI 也不可达：分享文本恒带 pingtunnel:// 前缀）
        assertFalse(QrCodes.encodable(""))
    }

    @Test
    fun byteModeJustWithinCapacity_isEncodable() {
        // 含小写字母强制字节模式，2000 < 2953
        assertTrue(QrCodes.encodable(base64Like(2000)))
    }

    @Test
    fun base64PayloadBeyondByteModeCapacity_isNotEncodable() {
        // 模拟超长 key 的 base64 分享文本：3000 > 字节模式上限 2953
        assertFalse(QrCodes.encodable("pingtunnel://" + base64Like(3000)))
    }

    @Test
    fun beyondAlphanumericCapacity_isNotEncodable() {
        // 纯大写字母走 ALPHANUMERIC 模式，4500 > 其上限 4296
        assertFalse(QrCodes.encodable("A".repeat(4500)))
    }
}
