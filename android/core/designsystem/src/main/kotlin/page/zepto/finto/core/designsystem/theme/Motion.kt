package page.zepto.finto.core.designsystem.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** Material 3 motion tokens: emphasized easing for things entering, standard for the rest. */
object FintoMotion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 450

    /** Between peer destinations (tabs): the old fades out, the new fades and settles in. */
    fun fadeThroughIn(): EnterTransition =
        fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate)) +
            scaleIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate), initialScale = 0.96f)

    fun fadeThroughOut(): ExitTransition = fadeOut(tween(90, easing = EmphasizedAccelerate))

    /** Into a child (drill-down): the new screen slides in from the end over the parent. */
    fun sharedAxisIn(forward: Boolean): EnterTransition =
        slideInHorizontally(tween(MEDIUM, easing = EmphasizedDecelerate)) { width -> (if (forward) width else -width) / 5 } +
            fadeIn(tween(MEDIUM, delayMillis = 60, easing = EmphasizedDecelerate))

    fun sharedAxisOut(forward: Boolean): ExitTransition =
        slideOutHorizontally(tween(MEDIUM, easing = EmphasizedAccelerate)) { width -> (if (forward) -width else width) / 5 } +
            fadeOut(tween(120, easing = EmphasizedAccelerate))

    /** Loading → content → error, in place. */
    fun <S> AnimatedContentTransitionScope<S>.crossfade(): ContentTransform =
        fadeIn(tween(220, delayMillis = 60, easing = Standard)) togetherWith fadeOut(tween(120, easing = Standard))
}
