package com.esrrhs.pingtunnel.client.util

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
        fun fromCode(code: String?): AppLanguage {
            val c = code?.trim() ?: ""
            return entries.firstOrNull { it.code.equals(c, ignoreCase = true) } ?: SYSTEM
        }

        /**
         * 应用指定的语言。
         */
        fun applyLanguage(context: Context, languageCode: String) {
            val lang = fromCode(languageCode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                if (localeManager != null) {
                    val localeList = if (lang.code.isEmpty()) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList.forLanguageTags(lang.code)
                    }
                    localeManager.applicationLocales = localeList
                    return
                }
            }
            // Fallback: 使用 AppCompatDelegate.setApplicationLocales
            val locales = if (lang.code.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang.code)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
