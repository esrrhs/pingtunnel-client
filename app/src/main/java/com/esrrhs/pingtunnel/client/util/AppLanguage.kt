package com.esrrhs.pingtunnel.client.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import com.esrrhs.pingtunnel.client.R
import java.util.Locale

/**
 * 应用支持的语言定义，与国家/地区语言翻译对应。
 */
enum class AppLanguage(
    val code: String,
    @StringRes val titleRes: Int,
) {
    SYSTEM("", R.string.language_follow_system),
    ZH("zh", R.string.language_zh),
    EN("en", R.string.language_en),
    FA("fa", R.string.language_fa),
    RU("ru", R.string.language_ru),
    TR("tr", R.string.language_tr),
    MY("my", R.string.language_my),
    UR("ur", R.string.language_ur),
    VI("vi", R.string.language_vi),
    AR("ar", R.string.language_ar),
    HI("hi", R.string.language_hi),
    ;

    companion object {

        /**
         * API 33 以下没有 [LocaleManager]，语言只能靠 `attachBaseContext` 时覆盖
         * Configuration 实现；那条路径需要在 Activity/Service 创建前同步读到偏好，
         * DataStore 是异步的，所以这里另存一份只读镜像。真正的用户可见状态仍是
         * [com.esrrhs.pingtunnel.client.data.AppSettings.appLanguage]。
         */
        private const val PREFS = "app_locale"
        private const val KEY_TAG = "tag"

        fun fromCode(code: String?): AppLanguage {
            val c = code?.trim() ?: ""
            return entries.firstOrNull { it.code.equals(c, ignoreCase = true) } ?: SYSTEM
        }

        /** 同步读出的语言标签；空串表示跟随系统。 */
        private fun storedTag(context: Context): String =
            prefs(context).getString(KEY_TAG, "").orEmpty()

        private fun prefs(context: Context) =
            (context.applicationContext ?: context)
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /**
         * 供 `Activity`/`Service` 的 `attachBaseContext` 调用：API 33+ 由系统按
         * applicationLocales 注入，直接返回原 Context；以下版本用镜像偏好覆盖
         * Configuration（含 RTL 方向）。
         */
        fun wrapIfNeeded(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = storedTag(base)
            if (tag.isEmpty()) return base
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }

        /**
         * 应用指定的语言。API 33+ 交给 [LocaleManager]（会同步系统设置里的
         * 「应用语言」并自动重建界面）；以下版本写入镜像偏好后重建当前界面。
         */
        fun applyLanguage(context: Context, languageCode: String) {
            prefs(context).edit().putString(KEY_TAG, languageCode).apply()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                if (localeManager != null) {
                    val localeList = if (languageCode.isEmpty()) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList.forLanguageTags(languageCode)
                    }
                    localeManager.applicationLocales = localeList
                    return
                }
            }
            findActivity(context)?.recreate()
        }

        private fun findActivity(context: Context): Activity? {
            var ctx: Context? = context
            while (ctx != null && ctx !is Activity) {
                ctx = (ctx as? ContextWrapper)?.baseContext
            }
            return ctx as? Activity
        }
    }
}
