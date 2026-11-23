package com.jhaiian.lucky.ui.components

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.ui.theme.LocalLuckyColors

val LocalBottomContentPadding = compositionLocalOf { 0.dp }

class LuckyHaptics(private val view: android.view.View) {
    fun click() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun toggle(on: Boolean) {
        view.performHapticFeedback(if (on) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK)
    }
}

@Composable
fun rememberHaptics(): LuckyHaptics {
    val view = LocalView.current
    return remember(view) { LuckyHaptics(view) }
}

@Composable
fun Modifier.luckyIconTile(size: Dp): Modifier {
    val colors = LocalLuckyColors.current
    return this
        .size(size)
        .clip(RoundedCornerShape(30))
        .background(colors.primary.copy(alpha = 0.14f))
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = colors.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)
    )
}

@Composable
fun RowDivider(startInset: Dp = 68.dp) {
    val colors = LocalLuckyColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(1.dp)
            .background(colors.divider)
    )
}

@Composable
fun LuckyCard(modifier: Modifier = Modifier, horizontalPadding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalLuckyColors.current
    Column(
        modifier = modifier
            .padding(horizontal = horizontalPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.cardBackground),
        content = content
    )
}

@Composable
fun RowArrow() {
    val colors = LocalLuckyColors.current
    Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = colors.secondaryText
    )
}

@Composable
fun IconTileIcon(icon: ImageVector, size: Dp = 40.dp, iconSize: Dp = 20.dp) {
    val colors = LocalLuckyColors.current
    Box(Modifier.luckyIconTile(size), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = { RowArrow() }
) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTileIcon(icon)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun LuckySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val colors = LocalLuckyColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.primary,
            checkedBorderColor = colors.primary,
            uncheckedThumbColor = colors.secondaryText,
            uncheckedTrackColor = colors.surfaceVariant,
            uncheckedBorderColor = colors.divider,
            disabledCheckedTrackColor = colors.surfaceVariant,
            disabledUncheckedTrackColor = colors.surfaceVariant,
            disabledUncheckedBorderColor = colors.divider
        )
    )
}

@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val haptics = rememberHaptics()
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        onClick = {
            haptics.toggle(!checked)
            onCheckedChange(!checked)
        },
        trailing = { LuckySwitch(checked = checked, onCheckedChange = null, enabled = enabled) }
    )
}

@Composable
fun LuckyIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(40.dp)
                .scale(scale)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    val colors = LocalLuckyColors.current
    LuckyIconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.player_back), tint = colors.primary)
    }
}

@Composable
fun LuckyActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
    minHeight: Dp = 48.dp
) {
    val colors = LocalLuckyColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val background = when {
        !enabled -> colors.surfaceVariant
        primary -> colors.primary
        else -> colors.primary.copy(alpha = 0.14f)
    }
    val content = when {
        !enabled -> colors.secondaryText
        primary -> colors.onPrimary
        else -> colors.primary
    }
    Row(
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1)
    }
}

enum class DialogButtonRole { MAIN, SECONDARY, DESTRUCTIVE }

@Composable
fun LuckyTextButton(label: String, onClick: () -> Unit, role: DialogButtonRole = DialogButtonRole.MAIN, enabled: Boolean = true) {
    val colors = LocalLuckyColors.current
    val color = when (role) {
        DialogButtonRole.MAIN -> colors.primary
        DialogButtonRole.SECONDARY -> colors.secondaryText
        DialogButtonRole.DESTRUCTIVE -> colors.colorError
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (role == DialogButtonRole.MAIN) FontWeight.Bold else FontWeight.Medium,
            color = color.copy(alpha = if (enabled) 1f else 0.4f)
        )
    }
}

@Composable
fun LuckyDialog(visible: Boolean, onDismiss: () -> Unit, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .animateEnterExit(
                        enter = scaleIn(initialScale = 0.92f, animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) +
                            slideInVertically(
                                animationSpec = spring(
                                    dampingRatio = 0.82f,
                                    stiffness = Spring.StiffnessMediumLow,
                                    visibilityThreshold = IntOffset(1, 1)
                                )
                            ) { it / 10 },
                        exit = scaleOut(targetScale = 0.95f, animationSpec = tween(180)) + slideOutVertically(tween(180)) { it / 16 }
                    )
            ) {
                content(this@AnimatedVisibility)
            }
        }
    }
}

@Composable
fun DialogCard(
    icon: ImageVector,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalLuckyColors.current
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp)
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.cardBackground)
            .border(1.dp, colors.divider.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
            .padding(bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 10.dp, top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTileIcon(icon, size = 34.dp, iconSize = 18.dp)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            LuckyIconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.secondaryText, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider.copy(alpha = 0.6f))
        )
        content()
    }
}

@Composable
fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = LocalLuckyColors.current
    val background by animateColorAsState(
        if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent,
        tween(220),
        label = "row"
    )
    val indicator by animateColorAsState(if (selected) colors.primary else Color.Transparent, tween(220), label = "indicator")
    val border by animateColorAsState(if (selected) colors.primary else colors.divider, tween(220), label = "border")
    val text by animateColorAsState(if (selected) colors.primary else colors.onSurface, tween(220), label = "text")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(indicator)
                .border(BorderStroke(1.6.dp, border), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) + fadeIn(tween(120)),
                exit = scaleOut(tween(140)) + fadeOut(tween(140))
            ) {
                Icon(Icons.Rounded.Check, null, tint = colors.onPrimary, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = text
            )
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
            }
        }
    }
}

@Composable
fun SegmentedChoice(options: List<Pair<ImageVector?, String>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .border(1.dp, colors.divider.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { index, (icon, label) ->
            val active = index == selected
            val background by animateColorAsState(if (active) colors.primary.copy(alpha = 0.14f) else Color.Transparent, tween(220), label = "seg")
            val tint by animateColorAsState(if (active) colors.primary else colors.secondaryText, tween(220), label = "segText")
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(background)
                    .clickable(role = Role.RadioButton) { onSelect(index) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun Modifier.luckyChoice(selected: Boolean, chip: Boolean = false, unselected: Color = LocalLuckyColors.current.surfaceVariant): Modifier {
    val colors = LocalLuckyColors.current
    val shape = RoundedCornerShape(if (chip) 12.dp else 16.dp)
    val clipped = this.clip(shape)
    return if (selected) clipped.background(colors.primary.copy(alpha = 0.14f)) else clipped.background(unselected)
}

@Composable
fun ChoiceTiles(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .luckyChoice(active)
                    .clickable(role = Role.RadioButton) { onSelect(index) }
                    .padding(vertical = 14.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) colors.primary else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BoxScope.LoadingShimmer() {
    val colors = LocalLuckyColors.current
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing), RepeatMode.Restart),
        label = "progress"
    )
    val base = colors.surfaceVariant
    val highlight = colors.onSurface.copy(alpha = 0.09f)
    Box(
        Modifier
            .matchParentSize()
            .drawBehind {
                drawRect(base)
                val start = -size.width + progress * size.width * 2f
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(start, 0f),
                        end = Offset(start + size.width, size.height)
                    )
                )
            }
    )
}

@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    val colors = LocalLuckyColors.current
    if (onBack == null) {
        Row(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 28.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
    } else {
        Row(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onBack)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            actions()
        }
    }
}

@Composable
fun SubPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val colors = LocalLuckyColors.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = top, bottom = LocalBottomContentPadding.current + 24.dp)
    ) {
        item(key = "header") { PageHeader(title, onBack, actions) }
        content()
    }
}

fun LazyListScope.section(key: String, title: String?, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    item(key = key) {
        Column(
            Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp)
        ) {
            if (title != null) SectionHeader(title)
            LuckyCard(content = content)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalLuckyColors.current.secondaryText,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: @Composable (() -> Unit)? = null) {
    val colors = LocalLuckyColors.current
    Column(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = colors.secondaryText, modifier = Modifier.size(72.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (action != null) {
            Spacer(Modifier.height(28.dp))
            action()
        }
    }
}
