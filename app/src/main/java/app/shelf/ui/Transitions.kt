package app.shelf.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** Close to the iOS navigation curve: quick start, long gentle settle. */
private val PushEasing = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)
private const val PUSH_MS = 380

/**
 * Push / pop between two screens, like a navigation stack. Going forward, the new screen slides
 * in from the right over the old one, which drifts a little to the left and fades. Going back is
 * the reverse, with the leaving screen kept on top as it slides away.
 */
fun <S> AnimatedContentTransitionScope<S>.pushTransition(forward: Boolean): ContentTransform {
    val slide = tween<androidx.compose.ui.unit.IntOffset>(PUSH_MS, easing = PushEasing)
    val fade = tween<Float>(PUSH_MS, easing = PushEasing)
    return if (forward) {
        (slideInHorizontally(slide) { it } + fadeIn(fade, initialAlpha = 0.6f)) togetherWith
            (slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade, targetAlpha = 0.4f))
    } else {
        ContentTransform(
            targetContentEnter = slideInHorizontally(slide) { -it / 4 } + fadeIn(fade, initialAlpha = 0.4f),
            initialContentExit = slideOutHorizontally(slide) { it } + fadeOut(fade, targetAlpha = 0.6f),
            targetContentZIndex = -1f,
        )
    }
}
