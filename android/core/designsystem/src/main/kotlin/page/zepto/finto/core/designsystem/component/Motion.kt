package page.zepto.finto.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import page.zepto.finto.core.designsystem.theme.FintoMotion
import page.zepto.finto.core.model.Money
import page.zepto.finto.core.model.MoneyFormat
import kotlin.math.roundToLong

/**
 * A headline figure that counts to its new value instead of jumping. Screen
 * readers get the final value only, never the intermediate frames.
 */
@Composable
fun AnimatedMoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    whole: Boolean = true,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    /** Count up from zero on first appearance (the hero), or show the value at once. */
    countUpOnEnter: Boolean = true,
) {
    val animated = remember { Animatable(if (countUpOnEnter) 0f else money.amount.toFloat()) }
    LaunchedEffect(money.amount) {
        animated.animateTo(money.amount.toFloat(), tween(700, easing = FintoMotion.EmphasizedDecelerate))
    }
    val shown = money.copy(amount = animated.value.roundToLong())
    val final = if (whole) MoneyFormat.formatWhole(money) else MoneyFormat.format(money)
    MoneyText(
        money = shown,
        modifier = modifier.clearAndSetSemantics { contentDescription = final },
        style = style,
        whole = whole,
        color = color,
        fontWeight = fontWeight,
    )
}

/** A soft sweep across placeholder shapes while the first load is in flight. */
fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-progress",
    )
    drawWithContent {
        val x = size.width * progress
        drawRect(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(x - size.width / 2, 0f),
                end = Offset(x + size.width / 2, size.height),
            ),
        )
    }
}

/** A placeholder block for skeleton layouts. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .shimmer(),
    )
}

/** Placeholder rows for a list's first load: a leading dot, two lines, a figure. */
@Composable
fun ListSkeleton(modifier: Modifier = Modifier, rows: Int = 6) {
    androidx.compose.foundation.layout.Column(modifier) {
        repeat(rows) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                SkeletonBlock(Modifier.size(36.dp), height = 36.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.6f), height = 14.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.35f), height = 12.dp)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                SkeletonBlock(Modifier.width(64.dp), height = 14.dp)
            }
        }
    }
}
