package com.esrrhs.pingtunnel.client.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 固定黑色系：AMOLED 纯黑背景 + 近黑层级表面
// 品牌主色取「信号琥珀」，与 SPP 客户端的浅蓝/青绿区分开
private val BlackColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF4A2E00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFFF8A65),
    onSecondary = Color(0xFF2A1200),
    secondaryContainer = Color(0xFF5C2E12),
    onSecondaryContainer = Color(0xFFFFD9C7),
    background = Color.Black,
    onBackground = Color(0xFFF2E8DC),
    surface = Color.Black,
    onSurface = Color(0xFFF2E8DC),
    surfaceVariant = Color(0xFF2A241C),
    onSurfaceVariant = Color(0xFFB9AE9E),
    outline = Color(0xFF7A6E5F),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF1F1F1F),
)

@Composable
fun PingtunnelClientTheme(
    // App 固定深色黑色系，且默认使用自有品牌色，不跟随壁纸动态取色
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 显式开启动态取色时仍强制暗色并把表面覆盖为黑色层级
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
