package com.esrrhs.pingtunnel.client.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 固定黑色系：AMOLED 纯黑背景 + 近黑层级表面
private val BlackColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80CBC4),
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF1F1F1F),
)

@Composable
fun PingtunnelClientTheme(
    // App 固定深色黑色系，忽略系统明暗设置
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Android 12+ 保留壁纸动态取色，但强制暗色并把表面覆盖为黑色层级
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context).copy(
            background = BlackColors.background,
            surface = BlackColors.surface,
            surfaceContainerLowest = BlackColors.surfaceContainerLowest,
            surfaceContainerLow = BlackColors.surfaceContainerLow,
            surfaceContainer = BlackColors.surfaceContainer,
            surfaceContainerHigh = BlackColors.surfaceContainerHigh,
            surfaceContainerHighest = BlackColors.surfaceContainerHighest,
        )
    } else {
        BlackColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
