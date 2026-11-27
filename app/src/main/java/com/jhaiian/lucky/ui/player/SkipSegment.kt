package com.jhaiian.lucky.ui.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.engine.Chapter
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import java.util.Locale

enum class SkipKind(@StringRes val labelRes: Int) {
    RECAP(R.string.player_skip_recap),
    INTRO(R.string.player_skip_intro),
    CREDITS(R.string.player_skip_credits)
}

data class SkipSegment(val kind: SkipKind, val endMs: Long?, val last: Boolean)

private val recapWords = listOf("recap", "previously", "resumen", "anteriormente", "recapitulação", "ringkasan", "sebelumnya", "ранее", "в предыдущих", "前情提要", "前情回顾", "पिछली")
private val introWords = listOf("intro", "opening", "title sequence", "theme song", "introducción", "apertura", "abertura", "pembukaan", "вступление", "заставка", "опенинг", "片头", "オープニング", "शुरुआत")
private val creditsWords = listOf("credits", "ending", "outro", "créditos", "kredit", "титры", "эндинг", "片尾", "エンディング", "क्रेडिट")
private val introTokens = setOf("op", "oped")
private val creditsTokens = setOf("ed", "end")

fun skipKind(title: String?): SkipKind? {
    val text = title?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (text.isEmpty()) return null
    val token = text.filter { it.isLetter() }
    return when {
        recapWords.any { it in text } -> SkipKind.RECAP
        introWords.any { it in text } || token in introTokens -> SkipKind.INTRO
        creditsWords.any { it in text } || token in creditsTokens -> SkipKind.CREDITS
        else -> null
    }
}

fun skipSegmentAt(chapters: List<Chapter>, positionMs: Long, durationMs: Long?): SkipSegment? {
    if (chapters.size < 2) return null
    val index = chapters.indexOfLast { it.startMs <= positionMs }
    if (index < 0) return null
    val chapter = chapters[index]
    val kind = skipKind(chapter.title) ?: return null
    val nextStart = chapters.getOrNull(index + 1)?.startMs
    val end = nextStart ?: chapter.endMs ?: durationMs
    if (end != null) {
        val length = end - chapter.startMs
        if (length <= 0L || (kind != SkipKind.CREDITS && length > MAX_SKIPPABLE_MS)) return null
        if (end - positionMs < MIN_REMAINING_MS) return null
    }
    return SkipSegment(kind, nextStart, nextStart == null)
}

private const val MAX_SKIPPABLE_MS = 5 * 60_000L
private const val MIN_REMAINING_MS = 1_500L

@Composable
internal fun SkipSegmentButton(
    segment: SkipSegment?,
    raised: Boolean,
    hasNext: Boolean,
    onSkip: (SkipSegment) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuckyColors.current
    val usable = segment != null && (!segment.last || hasNext)
    val lastShown = remember { arrayOfNulls<SkipSegment>(1) }
    if (usable) lastShown[0] = segment
    val shown = lastShown[0]
    val bottom by animateDpAsState(if (raised) 132.dp else 28.dp, tween(220), label = "skipOffset")
    AnimatedVisibility(
        visible = usable,
        modifier = modifier.padding(end = 24.dp, bottom = bottom),
        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f)
    ) {
        if (shown != null) {
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .shadow(6.dp, shape)
                    .clip(shape)
                    .background(colors.primary)
                    .clickable(role = Role.Button) { onSkip(shown) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(if (shown.last) R.string.player_skip_next_video else shown.kind.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
