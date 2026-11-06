package page.zepto.finto.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import page.zepto.finto.core.designsystem.theme.FintoMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Finto mark from the web app's favicon: the F, a plus, and a corner. */
val FintoMarkVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "FintoMark",
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 64f,
        viewportHeight = 64f,
    ).apply {
        addPath(
            pathData = addPathNodes("M10,0 h44 a10,10 0 0 1 10,10 v44 a10,10 0 0 1 -10,10 h-44 a10,10 0 0 1 -10,-10 v-44 a10,10 0 0 1 10,-10z"),
            fill = SolidColor(Color(0xFF0D0E10)),
        )
        path(fill = SolidColor(Color(0xFFE9EAEC))) {
            moveTo(17f, 14f); horizontalLineToRelative(31f); verticalLineToRelative(8f)
            horizontalLineTo(27f); verticalLineToRelative(9f); horizontalLineToRelative(17f)
            verticalLineToRelative(8f); horizontalLineTo(27f); verticalLineToRelative(18f)
            horizontalLineTo(17f); close()
        }
        path(stroke = SolidColor(Color(0xFF5AA9E6)), strokeLineWidth = 3f, strokeLineCap = StrokeCap.Butt) {
            moveTo(48f, 8f); verticalLineToRelative(12f); moveTo(42f, 14f); horizontalLineToRelative(12f)
        }
        path(stroke = SolidColor(Color(0xFF4EC98B)), strokeLineWidth = 3f) {
            moveTo(43f, 49f); horizontalLineToRelative(9f); verticalLineToRelative(9f)
        }
    }.build()
}

@Composable
fun FintoMark(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Image(FintoMarkVector, contentDescription = null, modifier = modifier.size(size))
}

private val MarkInk = Color(0xFFE9EAEC)
private val MarkBlue = Color(0xFF5AA9E6)
private val MarkGreen = Color(0xFF4EC98B)

/**
 * The mark without its tile, drawn in layers so the plus and the corner can
 * move: they fly in after the F, then the plus drifts and turns around the F's
 * shoulder while the corner breathes. A tap spins the plus. With [animate]
 * off (screenshots, reduced motion) everything sits still in place.
 */
@Composable
fun AnimatedFintoMark(modifier: Modifier = Modifier, size: Dp = 128.dp, animate: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Entrance: 0 → 1 for the F, then the plus and the corner.
    val letter = remember { Animatable(if (animate) 0f else 1f) }
    val plusIn = remember { Animatable(if (animate) 0f else 1f) }
    val cornerIn = remember { Animatable(if (animate) 0f else 1f) }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        launch { letter.animateTo(1f, tween(520, easing = FintoMotion.EmphasizedDecelerate)) }
        launch { delay(260); plusIn.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        launch { delay(420); cornerIn.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) }
    }
    val idle = rememberInfiniteTransition(label = "mark")
    // A slow loop around the shoulder of the F, never the same twice in a row.
    val orbit by idle.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "orbit",
    )
    val turn by idle.animateFloat(
        initialValue = 0f,
        targetValue = 90f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 4_200
                0f at 0
                0f at 2_600 using FintoMotion.Standard
                90f at 3_400
                90f at 4_200
            },
        ),
        label = "turn",
    )
    val breathe by idle.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = FintoMotion.Standard), RepeatMode.Reverse),
        label = "breathe",
    )
    val moving = animate && letter.value >= 1f

    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Finto" }
            .pointerInput(Unit) {
                detectTapGestures {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    scope.launch {
                        spin.snapTo(0f)
                        spin.animateTo(180f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
                    }
                }
            },
    ) {
        // The mark's own 64-unit grid, cropped to the glyphs (x 17–54, y 8–58)
        // and centred on them, with room for the plus and corner to move.
        val unit = this.size.minDimension / 48f
        fun x(v: Float) = (v - 11.5f) * unit
        fun y(v: Float) = (v - 9f) * unit
        val stroke = 3f * unit

        withTransform({ translate(top = (1f - letter.value) * 6f * unit) }) {
            val f = Path().apply {
                moveTo(x(17f), y(14f)); lineTo(x(48f), y(14f)); lineTo(x(48f), y(22f)); lineTo(x(27f), y(22f))
                lineTo(x(27f), y(31f)); lineTo(x(44f), y(31f)); lineTo(x(44f), y(39f)); lineTo(x(27f), y(39f))
                lineTo(x(27f), y(57f)); lineTo(x(17f), y(57f)); close()
            }
            drawPath(f, MarkInk.copy(alpha = letter.value))
        }

        // The plus: flies in from up and right while turning, then wanders.
        val wander = if (moving) Offset(cos(orbit) * 2.2f, sin(orbit * 2f) * 1.4f) else Offset.Zero
        val arrive = 1f - plusIn.value
        val plusCentre = Offset(x(48f + arrive * 10f + wander.x), y(14f - arrive * 10f + wander.y))
        rotate(degrees = -120f * arrive + (if (moving) turn else 0f) + spin.value, pivot = plusCentre) {
            val alpha = plusIn.value.coerceIn(0f, 1f)
            drawLine(MarkBlue.copy(alpha = alpha), plusCentre + Offset(0f, -6f * unit), plusCentre + Offset(0f, 6f * unit), stroke)
            drawLine(MarkBlue.copy(alpha = alpha), plusCentre + Offset(-6f * unit, 0f), plusCentre + Offset(6f * unit, 0f), stroke)
        }

        // The corner: slides in from the lower right and breathes in and out.
        val out = (1f - cornerIn.value) * 8f + (if (moving) breathe * 1.6f else 0f)
        val corner = Path().apply {
            moveTo(x(43f + out), y(49f + out)); lineTo(x(52f + out), y(49f + out)); lineTo(x(52f + out), y(58f + out))
        }
        drawPath(corner, MarkGreen.copy(alpha = cornerIn.value.coerceIn(0f, 1f)), style = Stroke(stroke))
    }
}
