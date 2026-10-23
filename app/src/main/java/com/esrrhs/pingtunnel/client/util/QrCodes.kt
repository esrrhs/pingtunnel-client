package com.esrrhs.pingtunnel.client.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 二维码生成工具。 */
object QrCodes {

    /**
     * 文本是否在二维码容量内。QR Code 最大版本（40-L）只能容纳约 2953 字节，
     * 超长 key/encrypt 的配置会让 [QRCodeWriter.encode] 抛 WriterException；
     * 纯 JVM 逻辑，便于单测。
     */
    fun encodable(text: String): Boolean = encodeMatrix(text) != null

    /** 把文本编码为位图；size 为边长像素；内容超容量时返回 null（不抛异常）。 */
    fun toBitmap(text: String, size: Int = 800): Bitmap? {
        val matrix = encodeMatrix(text, size) ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(
                    x, y,
                    if (matrix.get(x, y)) Color.BLACK else Color.WHITE,
                )
            }
        }
        return bitmap
    }

    private fun encodeMatrix(text: String, size: Int = 800) = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    }.getOrNull()
}
