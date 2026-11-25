package io.github.univers629.millet.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对话框内容骨架：标题栏 + 可滚动正文 + 底栏。
 *
 * 标题栏与底栏固定，正文滚动到两栏下方时用与弹窗同色的渐变遮罩渐隐，
 * 不使用背景采样，因此对话框不需要毛玻璃表面也不会出现采样黑块。
 */
@Composable
fun ProgressiveDialogLayout(
    title: String?,
    modifier: Modifier = Modifier,
    maxBodyHeight: Dp = 460.dp,
    headerPadding: PaddingValues = PaddingValues(bottom = 12.dp),
    footerPadding: PaddingValues = PaddingValues(top = 16.dp),
    surfaceColor: Color = MiuixTheme.colorScheme.background,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val titleColor = MiuixTheme.colorScheme.onSurface
    val titleSize = MiuixTheme.textStyles.title4.fontSize
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var headerHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }

    Box(modifier = modifier.fillMaxWidth().heightIn(max = maxBodyHeight)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(
                    top = with(density) { headerHeight.toDp() },
                    bottom = with(density) { footerHeight.toDp() },
                ),
            content = content,
        )
        if (title != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { headerHeight = it.height }
                    .background(
                        Brush.verticalGradient(
                            listOf(surfaceColor, surfaceColor, surfaceColor.copy(alpha = 0f)),
                        ),
                    )
                    .padding(headerPadding),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = titleSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = titleColor,
                )
            }
        }
        if (footer != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { footerHeight = it.height }
                    .background(
                        Brush.verticalGradient(
                            listOf(surfaceColor.copy(alpha = 0f), surfaceColor, surfaceColor),
                        ),
                    )
                    .padding(footerPadding),
                content = footer,
            )
        }
    }
}
