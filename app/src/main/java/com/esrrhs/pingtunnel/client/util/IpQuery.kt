package com.esrrhs.pingtunnel.client.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** IP 归属地信息。 */
data class IpGeoInfo(
    val ip: String,
    val countryCode: String?,
    val country: String?,
    val region: String?,
    val city: String?,
    val isp: String?,
) {
    /** 由 ISO 国家码生成旗帜 emoji；无法识别时返回空串。 */
    val flagEmoji: String
        get() = countryCode?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?.uppercase()
            ?.let { code ->
                String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) +
                    String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
            }
            ?: ""

    /** 国家/省/市 拼接出的归属地行。 */
    val locationLine: String
        get() = listOfNotNull(country, region, city)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
}

/**
 * 查询当前出口 IP 及其归属地。
 *
 * [queryDirect] 走设备真实网络；[queryViaSocks] 经本地 SOCKS5 隧道，
 * 用于在已连接时查看 VPN 出口 IP。服务优先 ip-api.com（中文归属地），
 * 失败时回退 ipwho.is（支持 HTTPS）。
 */
object IpQuery {

    private const val TIMEOUT_MS = 6000

    fun queryDirect(language: String): IpGeoInfo? =
        fetch(Proxy.NO_PROXY, language)

    fun queryViaSocks(socksPort: Int, language: String): IpGeoInfo? =
        fetch(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)), language)

    private fun fetch(proxy: Proxy, language: String): IpGeoInfo? {
        queryIpApi(proxy, language)?.let { return it }
        return queryIpWhoIs(proxy, language)
    }

    /** ip-api.com：免费档仅 HTTP，支持 lang=zh-CN，45 次/分。 */
    private fun queryIpApi(proxy: Proxy, language: String): IpGeoInfo? = runCatching {
        val url = "http://ip-api.com/json/?lang=$language&" +
            "fields=status,message,country,countryCode,regionName,city,isp,org,query"
        val json = httpGetJson(url, proxy) ?: return@runCatching null
        if (json.optString("status") != "success") return@runCatching null
        val ip = json.optString("query").ifBlank { return@runCatching null }
        IpGeoInfo(
            ip = ip,
            countryCode = json.optString("countryCode").ifBlank { null },
            country = json.optString("country").ifBlank { null },
            region = json.optString("regionName").ifBlank { null },
            city = json.optString("city").ifBlank { null },
            isp = firstNonBlank(json.optString("isp"), json.optString("org")),
        )
    }.getOrNull()

    /** ipwho.is：HTTPS 回退源。 */
    private fun queryIpWhoIs(proxy: Proxy, language: String): IpGeoInfo? = runCatching {
        val json = httpGetJson("https://ipwho.is/?lang=$language", proxy)
            ?: return@runCatching null
        if (!json.optBoolean("success", false)) return@runCatching null
        val ip = json.optString("ip").ifBlank { return@runCatching null }
        val conn = json.optJSONObject("connection")
        IpGeoInfo(
            ip = ip,
            countryCode = json.optString("country_code").ifBlank { null },
            country = json.optString("country").ifBlank { null },
            region = json.optString("region").ifBlank { null },
            city = json.optString("city").ifBlank { null },
            isp = firstNonBlank(
                conn?.optString("isp").orEmpty(),
                conn?.optString("org").orEmpty(),
            ),
        )
    }.getOrNull()

    private fun httpGetJson(urlText: String, proxy: Proxy): JSONObject? {
        val conn = (URL(urlText).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "curl/8.0")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() }
}
