package io.github.univers629.millet.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 折叠卡片：Miuix [Card] + [BasicComponent] 标题行，点击展开或收起卡片内的偏好项。
 * Miuix 没有现成的可折叠卡片组件，这里只组合库里的 Card / BasicComponent / HorizontalDivider，
 * 展开动画用与 Miuix 列表一致的无回弹 spring。
 */
@Composable
fun FoldablePreferenceCard(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "foldArrow",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = title,
            summary = summary,
            startAction = icon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(24.dp),
                        tint = colorScheme.onBackground,
                    )
                }
            },
            endActions = {
                Icon(
                    imageVector = MiuixIcons.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(20.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            },
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntSize.VisibilityThreshold),
                expandFrom = Alignment.Top,
            ) + fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = shrinkVertically(
                animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium, IntSize.VisibilityThreshold),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
        ) {
            Column {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                content()
            }
        }
    }
}
