package com.esrrhs.pingtunnel.client.util

import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 全部配置的 JSON 文件格式（导入/导出）。 */
object ProfilesFile {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val listSerializer = ListSerializer(Profile.serializer())

    fun encode(profiles: List<Profile>): String =
        json.encodeToString(listSerializer, profiles)

    /** 解析文件为配置列表；兼容单配置对象文件。 */
    fun decode(text: String): List<Profile> = runCatching {
        json.decodeFromString(listSerializer, text)
    }.recoverCatching {
        listOf(json.decodeFromString(Profile.serializer(), text))
    }.getOrDefault(emptyList())
}
