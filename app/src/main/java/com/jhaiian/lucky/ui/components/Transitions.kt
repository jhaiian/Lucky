package com.jhaiian.lucky.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

fun drillTransition(forward: Boolean): ContentTransform {
    val enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { width -> if (forward) width / 6 else -width / 6 } +
        fadeIn(tween(220, delayMillis = 60, easing = LinearOutSlowInEasing))
    val exit = slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { width -> if (forward) -width / 6 else width / 6 } +
        fadeOut(tween(140, easing = FastOutLinearInEasing))
    return (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else 0f }
}

fun tabTransition(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 70, easing = LinearOutSlowInEasing)) + scaleIn(tween(220, delayMillis = 70, easing = LinearOutSlowInEasing), initialScale = 0.96f)) togetherWith
        (fadeOut(tween(90, easing = FastOutLinearInEasing)) + scaleOut(tween(90, easing = FastOutLinearInEasing), targetScale = 0.96f))
