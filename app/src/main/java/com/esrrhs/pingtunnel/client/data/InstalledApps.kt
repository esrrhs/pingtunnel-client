package com.esrrhs.pingtunnel.client.data

import android.content.Context
import android.content.Intent

/** 可代理的已安装 App。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * 列出带桌面图标的已安装 App（用于分应用代理勾选）。
 * 走 MAIN/LAUNCHER 查询，配合 manifest 的 queries，不需要 QUERY_ALL_PACKAGES。
 */
object InstalledApps {

    fun list(context: Context, excludeSelf: Boolean = true): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return pm.queryIntentActivities(launcher, 0).mapNotNull { resolve ->
            val pkg = resolve.activityInfo?.packageName ?: return@mapNotNull null
            if (!seen.add(pkg)) return@mapNotNull null
            if (excludeSelf && pkg == context.packageName) return@mapNotNull null
            val label = runCatching {
                resolve.activityInfo.applicationInfo.loadLabel(pm).toString()
            }.getOrDefault(pkg)
            InstalledApp(packageName = pkg, label = label.ifBlank { pkg })
        }.sortedBy { it.label.lowercase() }
    }
}
