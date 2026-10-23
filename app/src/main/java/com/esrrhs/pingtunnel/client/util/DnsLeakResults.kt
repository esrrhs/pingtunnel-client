package com.esrrhs.pingtunnel.client.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** DNS 泄漏检测到的一台解析器。 */
data class DnsResolver(
    val ip: String,
    val country: String,
    val asnName: String,
)

/**
 * bash.ws DNS leak API 返回 JSON 的纯解析逻辑，便于单测。
 * 元素形如 {"type":"dns","ip":"8.8.8.8","country_name":"US","asn_name":"Google"}，
 * 另有 type=ip（出口 IP）/ conclusion 的元素。
 */
object DnsLeakResults {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<DnsResolver> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            // 字段逐个安全强转：bash.ws 是外部输入，单个字段类型不符（对象/数组）
            // 只跳过该元素，绝不让一个畸形条目让整份 DNS 解析失败。
            if (obj.stringOrNull("type") != "dns") return@mapNotNull null
            val ip = obj.stringOrNull("ip")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            DnsResolver(
                ip = ip,
                country = obj.stringOrNull("country_name").orEmpty(),
                asnName = obj.stringOrNull("asn_name").orEmpty(),
            )
        }
    }

    /** 字段不存在、为 null 或不是字符串标量（对象/数组）时返回 null，绝不抛异常。 */
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
