package com.esrrhs.pingtunnel.client.util

import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * 单配置分享编码：配置 JSON → base64 → 带前缀文本（二维码/剪贴板/文本分享通用）。
 */
object ProfileShare {

    const val SCHEME = "pingtunnel://"

    private val json = Json { ignoreUnknownKeys = true }
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun encode(profile: Profile): String {
        val raw = json.encodeToString(Profile.serializer(), profile)
        return SCHEME + encoder.encodeToString(raw.toByteArray())
    }

    /** 解析分享文本；格式不符或内容损坏时返回 null。 */
    fun decode(text: String): Profile? = runCatching {
        if (!text.startsWith(SCHEME)) return null
        val raw = decoder.decode(text.removePrefix(SCHEME))
        json.decodeFromString(Profile.serializer(), String(raw))
    }.getOrNull()
}
