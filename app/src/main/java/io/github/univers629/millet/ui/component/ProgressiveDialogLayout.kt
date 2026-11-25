package io.github.univers629.millet.ui.component

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.univers629.millet.ui.theme.LocalEnableProgressiveBlur
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.isRenderEffectSupported
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对话框内容骨架：标题栏 + 可滚动正文 + 底栏。
 *
 * 设置里「渐进式模糊」开启时，正文滚到标题栏或底栏下方的部分会被 Miuix
 * [progressiveTextureBlur] 渐进模糊（标题栏自上而下、底栏自下而上渐隐），与 GlassTopAppBar 的效果一致；
 * 关闭时退回普通的「标题 / 滚动正文 / 底栏」三段布局，正文在两栏之间裁切。
 *
 * 放在 GlassDialog 的 content 里使用，不要再给 GlassDialog 传 title。
 */
@Composable
fun ProgressiveDialogLayout(
    title: String?,
    modifier: Modifier = Modifier,
    maxBodyHeight: Dp = 460.dp,
    headerPadding: PaddingValues = PaddingValues(bottom = 12.dp),
    footerPadding: PaddingValues = PaddingValues(top = 16.dp),
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progressive = LocalEnableProgressiveBlur.current &&
        isRenderEffectSupported() && isRuntimeShaderSupported()
    val titleColor = MiuixTheme.colorScheme.onSurface
    val titleSize = MiuixTheme.textStyles.title4.fontSize
    val scrollState = rememberScrollState()

    if (!progressive) {
        Column(modifier = modifier.fillMaxWidth().heightIn(max = maxBodyHeight)) {
            if (title != null) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth().padding(headerPadding),
                    fontSize = titleSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = titleColor,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState),
                content = content,
            )
            if (footer != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(footerPadding), content = footer)
            }
        }
        return
    }

    val bodyBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val blurRadius = with(density) { GlassTopAppBarDefaults.ProgressiveBlurRadius.toPx() }
    var headerHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxBodyHeight)
                // 正文清晰层在标题栏/底栏下方渐隐，只留下栏内的渐进模糊副本（与 GlassTopAppBar 的 band 渐隐同理）。
                // 遮罩放在 layerBackdrop 外层：模糊采样拿到的是未遮罩的正文。
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val h = size.height
                    if (h <= 0f) return@drawWithContent
                    val top = (headerHeight / h).coerceIn(0f, 0.5f)
                    val bottom = (footerHeight / h).coerceIn(0f, 0.5f)
                    val scrolledTop = scrollState.value > 0
                    val scrolledBottom = scrollState.value < scrollState.maxValue
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to if (scrolledTop) Color.Transparent else Color.Black,
                            top to Color.Black,
                            (1f - bottom) to Color.Black,
                            1f to if (scrolledBottom) Color.Transparent else Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .layerBackdrop(bodyBackdrop)
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
                    .edgeBlur(bodyBackdrop, ProgressiveBlur.Top, blurRadius)
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
                    .edgeBlur(bodyBackdrop, ProgressiveBlur.Bottom, blurRadius)
                    .padding(footerPadding),
                content = footer,
            )
        }
    }
}

private fun Modifier.edgeBlur(
    backdrop: LayerBackdrop,
    gradient: ProgressiveBlur,
    blurRadius: Float,
): Modifier = this.progressiveTextureBlur(
    backdrop = backdrop,
    shape = RectangleShape,
    blurRadius = blurRadius,
    gradient = gradient,
)
