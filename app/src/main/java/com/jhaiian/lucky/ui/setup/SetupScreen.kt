package com.jhaiian.lucky.ui.setup

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.base.LuckyActivity
import com.jhaiian.lucky.data.DetectedVolume
import com.jhaiian.lucky.data.LibraryFolders
import com.jhaiian.lucky.data.LibrarySource
import com.jhaiian.lucky.data.LibraryStore
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.VideoAccessLevel
import com.jhaiian.lucky.data.VideoFolder
import com.jhaiian.lucky.ui.components.LuckyActionButton
import com.jhaiian.lucky.ui.components.LuckyIconButton
import com.jhaiian.lucky.ui.components.luckyIconTile
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.findActivity
import com.jhaiian.lucky.ui.library.VideoAccessRequest
import com.jhaiian.lucky.ui.library.rememberVideoAccessRequest
import com.jhaiian.lucky.ui.settings.AccentOptions
import com.jhaiian.lucky.ui.settings.DEFAULT_INTENSITY
import com.jhaiian.lucky.ui.settings.MATERIAL_YOU_ACCENT
import com.jhaiian.lucky.ui.settings.ThemeOptions
import com.jhaiian.lucky.ui.settings.materialYouSupported
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import com.jhaiian.lucky.ui.theme.ThemeMode
import com.jhaiian.lucky.ui.theme.resolveLuckyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.BreakIterator

private enum class SetupStep { WELCOME, LOOK, LIBRARY, DONE }

private data class PageMetrics(val wide: Boolean, val heroHeight: Dp) {
    val align: TextAlign get() = if (wide) TextAlign.Start else TextAlign.Center
}

private val SetupAccents = listOf(
    MATERIAL_YOU_ACCENT, "purple", "indigo", "blue", "cyan", "teal", "emerald", "lime",
    "yellow", "gold", "orange", "coral", "red", "pink", "lavender"
)

private const val LookApplyDelayMillis = 240L

@Composable
fun SetupScreen(skipIntro: Boolean, onFinish: () -> Unit) {
    val colors = LocalLuckyColors.current
    val haptics = rememberHaptics()
    val steps = remember { SetupStep.entries.toList() }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var instant by remember { mutableStateOf(skipIntro) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val go: (Int) -> Unit = { target ->
        val next = target.coerceIn(0, steps.lastIndex)
        if (next != index) {
            haptics.click()
            instant = false
            index = next
        }
    }
    BackHandler(enabled = index > 0) { go(index - 1) }

    val hasSource = LibraryStore.hasSource
    val nextLabel = when (step) {
        SetupStep.WELCOME -> stringResource(R.string.setup_get_started)
        SetupStep.LIBRARY -> stringResource(if (hasSource) R.string.setup_next else R.string.setup_skip)
        SetupStep.DONE -> stringResource(R.string.setup_start_watching)
        else -> stringResource(R.string.setup_next)
    }
    val nextPrimary = step != SetupStep.LIBRARY || hasSource
    val onNext: () -> Unit = {
        if (step == SetupStep.DONE) {
            haptics.click()
            onFinish()
        } else {
            go(index + 1)
        }
    }

    CompositionLocalProvider(LocalSetupInstant provides instant) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                val heroHeight = if (maxHeight < 560.dp) 190.dp else (maxHeight * 0.4f).coerceAtMost(400.dp)
                val metrics = PageMetrics(wide, heroHeight)
                Column(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = index,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 220f)) { full -> if (forward) full / 3 else -full / 3 } +
                                fadeIn(tween(420, delayMillis = 80)) +
                                scaleIn(tween(520, easing = FastOutSlowInEasing), initialScale = 0.94f)
                            val exit = slideOutHorizontally(tween(340)) { full -> if (forward) -full / 4 else full / 4 } +
                                fadeOut(tween(220)) +
                                scaleOut(tween(340), targetScale = 1.04f)
                            (enter togetherWith exit).using(SizeTransform(clip = false))
                        },
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        label = "setup_step"
                    ) { target ->
                        when (steps[target]) {
                            SetupStep.WELCOME -> WelcomePage(metrics)
                            SetupStep.LOOK -> LookPage(metrics)
                            SetupStep.LIBRARY -> LibraryPage(metrics)
                            SetupStep.DONE -> DonePage(metrics, hasSource)
                        }
                    }
                    SetupBottomBar(
                        step = step,
                        index = index,
                        count = steps.size,
                        nextLabel = nextLabel,
                        nextPrimary = nextPrimary,
                        onBack = { go(index - 1) },
                        onNext = onNext
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.rise(order: Int): Modifier {
    val instant = LocalSetupInstant.current
    val progress = remember { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(140L + order * 75L)
        progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 140f))
    }
    return this.graphicsLayer {
        val v = progress.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 36.dp.toPx()
    }
}

@Composable
private fun SetupPage(
    metrics: PageMetrics,
    hero: @Composable (Modifier) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scroll = rememberScrollState()
    if (metrics.wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                hero(Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(24.dp))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(vertical = 24.dp),
                    content = content
                )
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
        ) {
            hero(
                Modifier
                    .fillMaxWidth()
                    .height(metrics.heroHeight)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                content = content
            )
        }
    }
}

@Composable
private fun PageHeader(title: String, body: String, metrics: PageMetrics) {
    val colors = LocalLuckyColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.ExtraBold,
        color = colors.onSurface,
        textAlign = metrics.align,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .rise(0)
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = colors.secondaryText,
        textAlign = metrics.align,
        modifier = Modifier
            .fillMaxWidth()
            .rise(1)
    )
}

@Composable
private fun SectionLabel(text: String, metrics: PageMetrics, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = colors.primary,
        textAlign = metrics.align,
        modifier = modifier.fillMaxWidth()
    )
}

private fun graphemes(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val parts = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        parts.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return parts
}

@Composable
private fun RevealTitle(text: String, centered: Boolean) {
    val colors = LocalLuckyColors.current
    val instant = LocalSetupInstant.current
    val glyphs = remember(text) { graphemes(text) }
    Row(
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = text
                heading()
            }
    ) {
        glyphs.forEachIndexed { i, glyph ->
            val progress = remember { Animatable(if (instant) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (progress.value >= 1f) return@LaunchedEffect
                delay(260L + i * 95L)
                progress.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 170f))
            }
            Text(
                text = glyph,
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = colors.onSurface,
                modifier = Modifier.graphicsLayer {
                    val v = progress.value
                    alpha = v.coerceIn(0f, 1f)
                    translationY = (1f - v) * 48.dp.toPx()
                }
            )
        }
    }
}

@Composable
private fun AcronymLine(text: String, metrics: PageMetrics, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val styled = remember(text, colors.primary, colors.onSurface) {
        buildAnnotatedString {
            text.split(' ').filter { it.isNotEmpty() }.forEachIndexed { i, word ->
                if (i > 0) append(' ')
                if (word.first().isUpperCase()) {
                    withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.ExtraBold)) {
                        append(word.substring(0, 1))
                    }
                    withStyle(SpanStyle(color = colors.onSurface)) {
                        append(word.substring(1))
                    }
                } else {
                    withStyle(SpanStyle(color = colors.onSurface)) {
                        append(word)
                    }
                }
            }
        }
    }
    Text(
        text = styled,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        textAlign = metrics.align,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.cardBackground)
            .padding(12.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.luckyIconTile(42.dp)) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText
            )
        }
    }
}

@Composable
private fun WelcomePage(metrics: PageMetrics) {
    val colors = LocalLuckyColors.current
    SetupPage(metrics, hero = { LuckyMarkHero(it) }) {
        Text(
            text = stringResource(R.string.setup_welcome_eyebrow),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
            textAlign = metrics.align,
            modifier = Modifier
                .fillMaxWidth()
                .rise(0)
        )
        RevealTitle(stringResource(R.string.app_name), centered = !metrics.wide)
        Spacer(Modifier.height(4.dp))
        AcronymLine(stringResource(R.string.settings_about_tagline), metrics, Modifier.rise(3))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondaryText,
            textAlign = metrics.align,
            modifier = Modifier
                .fillMaxWidth()
                .rise(4)
        )
        Spacer(Modifier.height(22.dp))
        FeatureRow(Icons.Rounded.HighQuality, stringResource(R.string.setup_feature_picture_title), stringResource(R.string.setup_feature_picture), Modifier.rise(5))
        FeatureRow(Icons.Rounded.AutoAwesomeMotion, stringResource(R.string.setup_feature_motion_title), stringResource(R.string.setup_feature_motion), Modifier.rise(6))
        FeatureRow(Icons.Rounded.GraphicEq, stringResource(R.string.setup_feature_sound_title), stringResource(R.string.setup_feature_sound), Modifier.rise(7))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookPage(metrics: PageMetrics) {
    val context = LocalContext.current
    val colors = LocalLuckyColors.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() as? LuckyActivity }
    val prefs = remember(context) { LuckyPrefs.get(context) }
    var theme by remember { mutableStateOf(prefs.getString(LuckyPrefs.THEME, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM) }
    var accent by remember { mutableStateOf(prefs.getString(LuckyPrefs.ACCENT, MATERIAL_YOU_ACCENT) ?: MATERIAL_YOU_ACCENT) }
    val intensity = remember { prefs.getString(LuckyPrefs.SURFACE_INTENSITY, DEFAULT_INTENSITY) ?: DEFAULT_INTENSITY }
    val mode = if (colors.isLight) ThemeMode.LIGHT else ThemeMode.DARK
    val accents = remember { SetupAccents.filter { it != MATERIAL_YOU_ACCENT || materialYouSupported } }
    val swatches = remember(mode, intensity) {
        accents.associateWith { key ->
            runCatching { resolveLuckyTheme(context, mode, key, intensity).primary }.getOrDefault(Color.Gray)
        }
    }
    SetupPage(metrics, hero = { PlayerPreviewHero(it) }) {
        PageHeader(stringResource(R.string.setup_look_title), stringResource(R.string.setup_look_body), metrics)
        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.settings_section_theme), metrics, Modifier.rise(2))
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .rise(3)
        ) {
            ThemeOptions.forEach { option ->
                val icon = when (option.key) {
                    ThemeMode.LIGHT -> Icons.Rounded.LightMode
                    ThemeMode.DARK -> Icons.Rounded.DarkMode
                    else -> Icons.Rounded.BrightnessAuto
                }
                ThemeCard(
                    icon = icon,
                    label = stringResource(option.label),
                    selected = theme == option.key,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (theme != option.key) {
                            haptics.tick()
                            theme = option.key
                            scope.launch {
                                delay(LookApplyDelayMillis)
                                activity?.captureAndRecreate(option.key)
                            }
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        SectionLabel(stringResource(R.string.settings_accent), metrics, Modifier.rise(4))
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, if (metrics.wide) Alignment.Start else Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .rise(5)
        ) {
            accents.forEach { key ->
                val label = AccentOptions.firstOrNull { it.key == key }?.label ?: return@forEach
                AccentSwatch(
                    color = swatches[key] ?: Color.Gray,
                    selected = accent == key,
                    wallpaper = key == MATERIAL_YOU_ACCENT,
                    label = stringResource(label),
                    onClick = {
                        if (accent != key) {
                            haptics.tick()
                            accent = key
                            scope.launch {
                                delay(LookApplyDelayMillis)
                                activity?.captureAndApplyAccentColor(key)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalLuckyColors.current
    val container by animateColorAsState(
        if (selected) colors.primary.copy(alpha = 0.14f) else colors.cardBackground,
        label = "theme_card_bg"
    )
    val stroke by animateColorAsState(
        if (selected) colors.primary else colors.divider,
        label = "theme_card_stroke"
    )
    val lift by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 300f), label = "theme_card_lift")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .graphicsLayer {
                scaleX = 1f + 0.03f * lift
                scaleY = 1f + 0.03f * lift
            }
            .clip(RoundedCornerShape(20.dp))
            .border(if (selected) 2.dp else 1.dp, stroke, RoundedCornerShape(20.dp))
            .background(container)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.secondaryText,
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer { rotationZ = -20f * (1f - lift) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) colors.onSurface else colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    selected: Boolean,
    wallpaper: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 320f), label = "swatch_ring")
    val onColor = if (color.luminance() > 0.5f) Color.Black else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .drawBehind {
                val outer = size.minDimension / 2f
                drawCircle(
                    color = color.copy(alpha = ring.coerceIn(0f, 1f)),
                    radius = outer - 1.5.dp.toPx(),
                    style = Stroke(2.5.dp.toPx())
                )
                drawCircle(
                    color = color,
                    radius = outer * (0.86f - 0.14f * ring)
                )
            }
    ) {
        if (wallpaper) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = onColor.copy(alpha = (0.85f * (1f - ring)).coerceIn(0f, 1f)),
                modifier = Modifier.size(18.dp)
            )
        }
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = onColor,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = ring
                    scaleY = ring
                    alpha = ring.coerceIn(0f, 1f)
                    rotationZ = -90f * (1f - ring)
                }
        )
    }
}

@Composable
private fun LibraryPage(metrics: PageMetrics) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val internalLabel = stringResource(R.string.storage_internal)
    val externalLabel = stringResource(R.string.storage_external)
    val source = LibraryStore.source
    val volumes = remember { LibraryFolders.detectVolumes(context, internalLabel, externalLabel).filter { it.intent != null } }
    val accessRequest = rememberVideoAccessRequest { level ->
        if (level != VideoAccessLevel.NONE) haptics.click()
    }

    fun addTree(uri: Uri?) {
        if (uri == null || LibraryStore.folders.any { it.uri == uri }) return
        LibraryStore.updateFolders(context, LibraryStore.folders + LibraryFolders.describe(context, uri, internalLabel, externalLabel))
        haptics.click()
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { addTree(it) }
    val volumeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) addTree(result.data?.data)
    }
    val pick: (LibrarySource) -> Unit = { target ->
        if (LibraryStore.source != target) {
            haptics.tick()
            LibraryStore.applySource(context, target)
        }
    }

    SetupPage(metrics, hero = { LibraryScanHero(LibraryStore.hasSource, it) }) {
        PageHeader(stringResource(R.string.setup_library_title), stringResource(R.string.setup_library_body), metrics)
        Spacer(Modifier.height(22.dp))
        SourceCard(
            icon = Icons.Rounded.VideoLibrary,
            title = stringResource(R.string.library_source_auto),
            body = stringResource(R.string.library_source_auto_note),
            selected = source == LibrarySource.AUTO,
            onClick = { pick(LibrarySource.AUTO) },
            modifier = Modifier.rise(2)
        )
        Spacer(Modifier.height(10.dp))
        SourceCard(
            icon = Icons.Rounded.FolderOpen,
            title = stringResource(R.string.library_source_folders),
            body = stringResource(R.string.library_source_folders_note),
            selected = source == LibrarySource.FOLDERS,
            onClick = { pick(LibrarySource.FOLDERS) },
            modifier = Modifier.rise(3)
        )
        Spacer(Modifier.height(18.dp))
        AnimatedContent(
            targetState = source,
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it / 6 }) togetherWith
                    fadeOut(tween(140))
            },
            modifier = Modifier
                .fillMaxWidth()
                .rise(4),
            label = "library_source_detail"
        ) { current ->
            Column(Modifier.fillMaxWidth()) {
                if (current == LibrarySource.AUTO) {
                    AccessSection(metrics, LibraryStore.access, accessRequest)
                } else {
                    FolderSection(
                        metrics = metrics,
                        folders = LibraryStore.folders,
                        volumes = volumes,
                        onBrowse = { runCatching { treeLauncher.launch(null) } },
                        onVolume = { volume ->
                            val intent = volume.intent
                            if (intent != null) {
                                runCatching { volumeLauncher.launch(intent) }.onFailure { runCatching { treeLauncher.launch(null) } }
                            } else {
                                runCatching { treeLauncher.launch(null) }
                            }
                        },
                        onRemove = { folder ->
                            haptics.tick()
                            LibraryStore.updateFolders(context, LibraryStore.folders.filterNot { it.uri == folder.uri })
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLuckyColors.current
    val container by animateColorAsState(
        if (selected) colors.primary.copy(alpha = 0.14f) else colors.cardBackground,
        label = "source_card_bg"
    )
    val stroke by animateColorAsState(
        if (selected) colors.primary else colors.divider,
        label = "source_card_stroke"
    )
    val mark by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 320f), label = "source_card_mark")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(if (selected) 2.dp else 1.dp, stroke, RoundedCornerShape(20.dp))
            .background(container)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(30))
                .background(if (selected) colors.primary else colors.primary.copy(alpha = 0.14f))
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onPrimary else colors.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) colors.primary else colors.divider, CircleShape)
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        scaleX = mark
                        scaleY = mark
                    }
                    .clip(CircleShape)
                    .background(colors.primary)
            )
        }
    }
}

@Composable
private fun AccessSection(metrics: PageMetrics, access: VideoAccessLevel, request: VideoAccessRequest) {
    val colors = LocalLuckyColors.current
    AnimatedContent(
        targetState = access,
        transitionSpec = {
            (fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.5f, stiffness = 260f), initialScale = 0.85f)) togetherWith
                (fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f))
        },
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth(),
        label = "setup_access"
    ) { level ->
        if (level == VideoAccessLevel.NONE) {
            Column(Modifier.fillMaxWidth()) {
                LuckyActionButton(
                    label = stringResource(if (request.blocked) R.string.library_access_open_settings else R.string.library_access_allow),
                    icon = Icons.Rounded.LockOpen,
                    minHeight = 52.dp,
                    onClick = { request.request() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(if (request.blocked) R.string.library_access_blocked_summary else R.string.library_access_none_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    textAlign = metrics.align,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            val partial = level == VideoAccessLevel.PARTIAL
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (partial) R.string.library_access_partial_title else R.string.library_access_full_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = stringResource(if (partial) R.string.library_access_partial_summary else R.string.library_access_full_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryText
                    )
                }
                if (partial) {
                    Text(
                        text = stringResource(R.string.library_access_change_selection),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button) { request.request() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderSection(
    metrics: PageMetrics,
    folders: List<VideoFolder>,
    volumes: List<DetectedVolume>,
    onBrowse: () -> Unit,
    onVolume: (DetectedVolume) -> Unit,
    onRemove: (VideoFolder) -> Unit
) {
    val colors = LocalLuckyColors.current
    LuckyActionButton(
        label = stringResource(R.string.folder_picker_browse_button),
        icon = Icons.Rounded.CreateNewFolder,
        minHeight = 52.dp,
        onClick = onBrowse,
        modifier = Modifier.fillMaxWidth()
    )
    volumes.forEach { volume ->
        key(volume.id) {
            Spacer(Modifier.height(10.dp))
            LuckyActionButton(
                label = stringResource(R.string.setup_library_use_volume, volume.label),
                icon = Icons.Rounded.SdCard,
                primary = false,
                minHeight = 52.dp,
                onClick = { onVolume(volume) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    val count = folders.size
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            (fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.5f, stiffness = 300f), initialScale = 0.8f)) togetherWith fadeOut(tween(140))
        },
        modifier = Modifier.fillMaxWidth(),
        label = "setup_folder_count"
    ) { total ->
        Text(
            text = if (total == 0) {
                stringResource(R.string.folder_picker_empty_hint)
            } else {
                pluralStringResource(R.plurals.setup_library_ready, total, total)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (total == 0) colors.secondaryText else colors.primary,
            textAlign = metrics.align,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(10.dp))
    folders.forEach { folder ->
        key(folder.uri.toString()) {
            FolderCard(folder = folder, onRemove = { onRemove(folder) }, modifier = Modifier.rise(0))
        }
    }
}

@Composable
private fun FolderCard(folder: VideoFolder, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.cardBackground)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.luckyIconTile(40.dp)) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = folder.locationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LuckyIconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.folder_picker_remove), tint = colors.secondaryText)
        }
    }
}

@Composable
private fun DonePage(metrics: PageMetrics, hasSource: Boolean) {
    SetupPage(metrics, hero = { ClapperHero(it) }) {
        PageHeader(
            stringResource(R.string.setup_done_title),
            stringResource(if (hasSource) R.string.setup_done_body_ready else R.string.setup_done_body_empty),
            metrics
        )
    }
}

@Composable
private fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val colors = LocalLuckyColors.current
    val description = stringResource(R.string.setup_step_counter, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics { contentDescription = description }
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(
                if (i == current) 26.dp else 8.dp,
                spring(dampingRatio = 0.6f, stiffness = 320f),
                label = "dot_width"
            )
            val tint by animateColorAsState(
                when {
                    i == current -> colors.primary
                    i < current -> colors.primary.copy(alpha = 0.45f)
                    else -> colors.secondaryText.copy(alpha = 0.3f)
                },
                label = "dot_color"
            )
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(tint)
            )
        }
    }
}

@Composable
private fun SetupBottomBar(
    step: SetupStep,
    index: Int,
    count: Int,
    nextLabel: String,
    nextPrimary: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val stacked = step == SetupStep.WELCOME || step == SetupStep.DONE
    val nextIcon = if (step == SetupStep.DONE) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.ArrowForward
    AnimatedContent(
        targetState = stacked,
        transitionSpec = { fadeIn(tween(300, delayMillis = 120)) togetherWith fadeOut(tween(160)) },
        modifier = Modifier.fillMaxWidth(),
        label = "setup_bar"
    ) { isStacked ->
        if (isStacked) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                StepDots(count, index)
                Spacer(Modifier.height(16.dp))
                LuckyActionButton(
                    label = nextLabel,
                    icon = nextIcon,
                    minHeight = 56.dp,
                    onClick = onNext,
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth()
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    LuckyActionButton(
                        label = stringResource(R.string.setup_back),
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        primary = false,
                        onClick = onBack
                    )
                }
                StepDots(count, index, Modifier.padding(horizontal = 12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    AnimatedContent(
                        targetState = nextLabel to nextPrimary,
                        transitionSpec = {
                            (fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 320f), initialScale = 0.88f)) togetherWith fadeOut(tween(120))
                        },
                        contentAlignment = Alignment.CenterEnd,
                        label = "setup_next"
                    ) { (label, primary) ->
                        LuckyActionButton(
                            label = label,
                            icon = nextIcon,
                            primary = primary,
                            onClick = onNext
                        )
                    }
                }
            }
        }
    }
}
