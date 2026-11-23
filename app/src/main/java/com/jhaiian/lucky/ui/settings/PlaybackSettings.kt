package com.jhaiian.lucky.ui.settings

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.jhaiian.lucky.ui.theme.LocalLuckyColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.ShutterSpeed
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.data.TrackLanguage
import com.jhaiian.lucky.engine.ArtifactProtection
import com.jhaiian.lucky.engine.Deinterlace
import com.jhaiian.lucky.engine.Downscaler
import com.jhaiian.lucky.engine.HdrOutput
import com.jhaiian.lucky.engine.MotionQuality
import com.jhaiian.lucky.engine.MotionTarget
import com.jhaiian.lucky.engine.RefreshMatch
import com.jhaiian.lucky.engine.RendererPreference
import com.jhaiian.lucky.engine.SceneSensitivity
import com.jhaiian.lucky.engine.SmoothMotion
import com.jhaiian.lucky.engine.ScaleFilter
import com.jhaiian.lucky.engine.ScaleTier
import com.jhaiian.lucky.engine.Upscaler
import com.jhaiian.lucky.ui.components.ChoiceRow
import com.jhaiian.lucky.ui.components.ChoiceTiles
import com.jhaiian.lucky.ui.components.RowDivider
import com.jhaiian.lucky.ui.components.SegmentedChoice
import com.jhaiian.lucky.ui.components.SettingsRow
import com.jhaiian.lucky.ui.components.SettingsSwitchRow
import com.jhaiian.lucky.ui.components.SubPage
import com.jhaiian.lucky.ui.components.rememberHaptics
import com.jhaiian.lucky.ui.components.section
import com.jhaiian.lucky.ui.findActivity
import com.jhaiian.lucky.ui.player.displayCaps
import com.jhaiian.lucky.ui.player.maxRefreshRate
import com.jhaiian.lucky.ui.player.thermalSupported
import java.util.Locale
import kotlin.math.roundToInt

internal val SeekStepOptions = listOf(5, 10, 15, 30)

internal fun scaleFilterLabel(filter: ScaleFilter): Int = when (filter) {
    ScaleFilter.BILINEAR -> R.string.scaler_bilinear
    ScaleFilter.NEAREST -> R.string.scaler_nearest
    ScaleFilter.HERMITE -> R.string.scaler_hermite
    ScaleFilter.GAUSSIAN -> R.string.scaler_gaussian
    ScaleFilter.MITCHELL -> R.string.scaler_mitchell
    ScaleFilter.ROBIDOUX -> R.string.scaler_robidoux
    ScaleFilter.BICUBIC -> R.string.scaler_bicubic
    ScaleFilter.ROBIDOUX_SHARP -> R.string.scaler_robidoux_sharp
    ScaleFilter.SPLINE16 -> R.string.scaler_spline16
    ScaleFilter.SPLINE36 -> R.string.scaler_spline36
    ScaleFilter.SPLINE64 -> R.string.scaler_spline64
    ScaleFilter.LANCZOS2 -> R.string.scaler_lanczos2
    ScaleFilter.LANCZOS3 -> R.string.scaler_lanczos3
    ScaleFilter.LANCZOS4 -> R.string.scaler_lanczos4
    ScaleFilter.GINSENG -> R.string.scaler_ginseng
    ScaleFilter.AREA -> R.string.scaler_area
    ScaleFilter.TRIANGLE -> R.string.scaler_triangle
    ScaleFilter.EWA_ROBIDOUX -> R.string.scaler_ewa_robidoux
    ScaleFilter.EWA_LANCZOS -> R.string.scaler_ewa_lanczos
    ScaleFilter.EWA_LANCZOS_SHARP -> R.string.scaler_ewa_lanczos_sharp
    ScaleFilter.EWA_LANCZOS4 -> R.string.scaler_ewa_lanczos4
}

internal fun scaleFilterDetail(filter: ScaleFilter): Int = when (filter) {
    ScaleFilter.BILINEAR -> R.string.scaler_bilinear_detail
    ScaleFilter.NEAREST -> R.string.scaler_nearest_detail
    ScaleFilter.HERMITE -> R.string.scaler_hermite_detail
    ScaleFilter.GAUSSIAN -> R.string.scaler_gaussian_detail
    ScaleFilter.MITCHELL -> R.string.scaler_mitchell_detail
    ScaleFilter.ROBIDOUX -> R.string.scaler_robidoux_detail
    ScaleFilter.BICUBIC -> R.string.scaler_bicubic_detail
    ScaleFilter.ROBIDOUX_SHARP -> R.string.scaler_robidoux_sharp_detail
    ScaleFilter.SPLINE16 -> R.string.scaler_spline16_detail
    ScaleFilter.SPLINE36 -> R.string.scaler_spline36_detail
    ScaleFilter.SPLINE64 -> R.string.scaler_spline64_detail
    ScaleFilter.LANCZOS2 -> R.string.scaler_lanczos2_detail
    ScaleFilter.LANCZOS3 -> R.string.scaler_lanczos3_detail
    ScaleFilter.LANCZOS4 -> R.string.scaler_lanczos4_detail
    ScaleFilter.GINSENG -> R.string.scaler_ginseng_detail
    ScaleFilter.AREA -> R.string.scaler_area_detail
    ScaleFilter.TRIANGLE -> R.string.scaler_triangle_detail
    ScaleFilter.EWA_ROBIDOUX -> R.string.scaler_ewa_robidoux_detail
    ScaleFilter.EWA_LANCZOS -> R.string.scaler_ewa_lanczos_detail
    ScaleFilter.EWA_LANCZOS_SHARP -> R.string.scaler_ewa_lanczos_sharp_detail
    ScaleFilter.EWA_LANCZOS4 -> R.string.scaler_ewa_lanczos4_detail
}

internal fun upscalerLabel(upscaler: Upscaler): Int = scaleFilterLabel(upscaler.filter)

internal fun downscalerLabel(downscaler: Downscaler): Int = scaleFilterLabel(downscaler.filter)

internal val AntiringLevels = listOf(0, 25, 50, 75, 100)

internal fun deinterlaceLabel(option: Deinterlace): Int = when (option) {
    Deinterlace.OFF -> R.string.deinterlace_off
    Deinterlace.AUTO -> R.string.deinterlace_auto
    Deinterlace.ON -> R.string.deinterlace_on
}

internal fun deinterlaceDetail(option: Deinterlace): Int = when (option) {
    Deinterlace.OFF -> R.string.deinterlace_off_detail
    Deinterlace.AUTO -> R.string.deinterlace_auto_detail
    Deinterlace.ON -> R.string.deinterlace_on_detail
}

internal fun smoothMotionLabel(option: SmoothMotion): Int = when (option) {
    SmoothMotion.OFF -> R.string.smooth_off
    SmoothMotion.SHARP -> R.string.smooth_sharp
    SmoothMotion.SMOOTH -> R.string.smooth_smooth
    SmoothMotion.INTERPOLATE -> R.string.smooth_interpolate
}

internal fun smoothMotionDetail(option: SmoothMotion): Int = when (option) {
    SmoothMotion.OFF -> R.string.smooth_off_detail
    SmoothMotion.SHARP -> R.string.smooth_sharp_detail
    SmoothMotion.SMOOTH -> R.string.smooth_smooth_detail
    SmoothMotion.INTERPOLATE -> R.string.smooth_interpolate_detail
}

internal fun motionQualityLabel(option: MotionQuality): Int = when (option) {
    MotionQuality.AUTO -> R.string.motion_quality_auto
    MotionQuality.FAST -> R.string.motion_quality_fast
    MotionQuality.BALANCED -> R.string.motion_quality_balanced
    MotionQuality.HIGH -> R.string.motion_quality_high
}

internal fun motionQualityDetail(option: MotionQuality): Int = when (option) {
    MotionQuality.AUTO -> R.string.motion_quality_auto_detail
    MotionQuality.FAST -> R.string.motion_quality_fast_detail
    MotionQuality.BALANCED -> R.string.motion_quality_balanced_detail
    MotionQuality.HIGH -> R.string.motion_quality_high_detail
}

@Composable
internal fun motionTargetLabel(option: MotionTarget, fps: Int): String = when (option) {
    MotionTarget.SCREEN -> stringResource(R.string.motion_target_screen)
    MotionTarget.DOUBLE -> stringResource(R.string.motion_target_double)
    MotionTarget.CUSTOM -> stringResource(R.string.motion_target_fps, fps)
}

@Composable
private fun FrameRateSlider(fps: Int, maxFps: Int, active: Boolean, onChange: (Int) -> Unit) {
    val colors = LocalLuckyColors.current
    val range = MotionTarget.MIN_FPS..maxFps
    val value = fps.coerceIn(range)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.motion_target_custom),
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) colors.primary else colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.motion_target_fps, value),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) colors.primary else colors.secondaryText
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = if (active) colors.primary else colors.secondaryText,
                activeTrackColor = if (active) colors.primary else colors.secondaryText.copy(alpha = 0.5f),
                inactiveTrackColor = colors.primary.copy(alpha = 0.2f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

private fun protectionLabel(option: ArtifactProtection): Int = when (option) {
    ArtifactProtection.OFF -> R.string.motion_level_off
    ArtifactProtection.LOW -> R.string.motion_level_low
    ArtifactProtection.MEDIUM -> R.string.motion_level_medium
    ArtifactProtection.STRONG -> R.string.motion_level_strong
}

private fun sceneLabel(option: SceneSensitivity): Int = when (option) {
    SceneSensitivity.LOW -> R.string.motion_level_low
    SceneSensitivity.MEDIUM -> R.string.motion_level_medium
    SceneSensitivity.HIGH -> R.string.motion_level_high
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        color = LocalLuckyColors.current.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
    )
}

internal fun hdrOutputLabel(option: HdrOutput): Int = when (option) {
    HdrOutput.AUTO -> R.string.hdr_output_auto
    HdrOutput.ALWAYS -> R.string.hdr_output_always
    HdrOutput.OFF -> R.string.hdr_output_off
}

internal fun hdrOutputDetail(option: HdrOutput, hdrScreen: Boolean): Int = when (option) {
    HdrOutput.AUTO -> if (hdrScreen) R.string.hdr_output_auto_detail_supported else R.string.hdr_output_auto_detail_unsupported
    HdrOutput.ALWAYS -> R.string.hdr_output_always_detail
    HdrOutput.OFF -> R.string.hdr_output_off_detail
}

internal fun scaleTierLabel(tier: ScaleTier): Int = when (tier) {
    ScaleTier.LIGHT -> R.string.scaler_tier_light
    ScaleTier.MEDIUM -> R.string.scaler_tier_medium
    ScaleTier.HEAVY -> R.string.scaler_tier_heavy
    ScaleTier.VERY_HEAVY -> R.string.scaler_tier_very_heavy
}

@Composable
private fun scalerDetail(filter: ScaleFilter, recommended: Boolean, reads: String): String {
    val description = stringResource(scaleFilterDetail(filter))
    val detail = stringResource(R.string.scaler_detail, reads, description)
    return if (recommended) stringResource(R.string.scaler_recommended, detail) else detail
}

@Composable
private fun upscalerReads(option: Upscaler): String =
    if (option.round) {
        pluralStringResource(R.plurals.scaler_reads_round, option.reads, option.reads)
    } else {
        pluralStringResource(R.plurals.scaler_reads, option.reads, option.reads)
    }

@Composable
private fun TierHeader(tier: ScaleTier) {
    val colors = LocalLuckyColors.current
    Text(
        text = stringResource(scaleTierLabel(tier)),
        color = colors.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun audioLanguageLabel(option: String, appLocale: Locale): String = when (option) {
    TrackLanguage.FILE_DEFAULT -> stringResource(R.string.audio_language_file_default)
    TrackLanguage.APP -> stringResource(R.string.audio_language_app, TrackLanguage.displayName(appLocale.language, appLocale))
    else -> TrackLanguage.displayName(option, appLocale)
}

@Composable
internal fun PlaybackSettings(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val hardware = rememberBooleanPref(LuckyPrefs.HARDWARE_DECODING, true)
    val softwareFallback = rememberBooleanPref(LuckyPrefs.SOFTWARE_FALLBACK, true)
    val renderer = rememberStringPref(LuckyPrefs.RENDERER, RendererPreference.OPENGL.name)
    val upscaler = rememberStringPref(LuckyPrefs.UPSCALER, Upscaler.BILINEAR.name)
    val downscaler = rememberStringPref(LuckyPrefs.DOWNSCALER, Downscaler.BILINEAR.name)
    val upscalingTitle = stringResource(R.string.settings_upscaling)
    val downscalingTitle = stringResource(R.string.settings_downscaling)
    val antiring = rememberIntPref(LuckyPrefs.ANTIRING, 50)
    val autoQuality = rememberBooleanPref(LuckyPrefs.AUTO_QUALITY, true)
    val fastDecode = rememberBooleanPref(LuckyPrefs.FAST_DECODE, true)
    val deinterlace = rememberStringPref(LuckyPrefs.DEINTERLACE, Deinterlace.AUTO.name)
    val deinterlaceTitle = stringResource(R.string.settings_deinterlace)
    val deinterlaceNote = stringResource(R.string.settings_deinterlace_note)
    val performanceTitle = stringResource(R.string.settings_section_performance)
    val smoothMotion = rememberStringPref(LuckyPrefs.SMOOTH_MOTION, SmoothMotion.OFF.name)
    val refreshMatch = rememberStringPref(LuckyPrefs.REFRESH_MATCH, RefreshMatch.SEAMLESS.name)
    val motionTitle = stringResource(R.string.settings_section_motion)
    val motionNote = stringResource(R.string.settings_motion_note)
    val motionQuality = rememberStringPref(LuckyPrefs.MOTION_QUALITY, MotionQuality.AUTO.name)
    val motionTarget = rememberStringPref(LuckyPrefs.MOTION_TARGET, MotionTarget.SCREEN.name)
    val motionTargetFps = rememberIntPref(
        LuckyPrefs.MOTION_TARGET_FPS,
        MotionTarget.legacyFps(LuckyPrefs.get(LocalContext.current).getString(LuckyPrefs.MOTION_TARGET, null)) ?: MotionTarget.DEFAULT_FPS
    )
    val motionProtection = rememberStringPref(LuckyPrefs.MOTION_PROTECTION, ArtifactProtection.MEDIUM.name)
    val motionScene = rememberStringPref(LuckyPrefs.MOTION_SCENE, SceneSensitivity.MEDIUM.name)
    val motionAnime = rememberBooleanPref(LuckyPrefs.MOTION_ANIME, false)
    val motionLowFps = rememberBooleanPref(LuckyPrefs.MOTION_LOW_FPS_ONLY, false)
    val motionBattery = rememberBooleanPref(LuckyPrefs.MOTION_BATTERY_SAVER, true)
    val motionHeat = rememberBooleanPref(LuckyPrefs.MOTION_HEAT, true)
    var showQualityDialog by remember { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }
    val learnedResetText = stringResource(R.string.settings_reset_learned_done)
    val antiringTitle = stringResource(R.string.settings_antiring)
    val antiringNote = stringResource(R.string.settings_antiring_note)
    val antiringOff = stringResource(R.string.antiring_off)
    val antiringLabels = remember(antiringOff) { AntiringLevels.map { if (it == 0) antiringOff else "$it%" } }
    val downscalingNote = stringResource(R.string.settings_downscaling_note)
    val upscalingNote = stringResource(R.string.settings_upscaling_note)
    val scalingTitle = stringResource(R.string.settings_section_scaling)
    var showUpscalerDialog by remember { mutableStateOf(false) }
    var showDownscalerDialog by remember { mutableStateOf(false) }
    val hdrOutput = rememberStringPref(LuckyPrefs.HDR_OUTPUT, HdrOutput.AUTO.name)
    val adaptiveToneMapping = rememberBooleanPref(LuckyPrefs.ADAPTIVE_TONE_MAPPING, true)
    val wideColor = rememberBooleanPref(LuckyPrefs.WIDE_COLOR, true)
    val hdrBoost = rememberBooleanPref(LuckyPrefs.HDR_BRIGHTNESS_BOOST, true)
    val hdrTitle = stringResource(R.string.settings_hdr_color)
    val hdrNote = stringResource(R.string.settings_hdr_color_note)
    val resume = rememberBooleanPref(LuckyPrefs.RESUME_PLAYBACK, true)
    val subtitles = rememberBooleanPref(LuckyPrefs.AUTO_SUBTITLES, true)
    val seekSeconds = rememberIntPref(LuckyPrefs.DOUBLE_TAP_SEEK_SECONDS, 10)
    var showSeekDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val audioLanguage = rememberStringPref(LuckyPrefs.PREFERRED_AUDIO_LANGUAGE, TrackLanguage.FILE_DEFAULT)
    val context = LocalContext.current
    val appLocale = remember(context) { TrackLanguage.appLocale(context) }
    val screen = remember(context) { displayCaps(context.findActivity()) }
    val languageOptions = remember(appLocale) {
        TrackLanguage.choices.sortedWith(compareBy(java.text.Collator.getInstance(appLocale)) { TrackLanguage.displayName(it, appLocale) })
    }

    val pageTitle = stringResource(R.string.settings_playback)
    val decodingTitle = stringResource(R.string.settings_section_decoding)
    val decodingNote = stringResource(R.string.settings_decoding_note)
    val renderingTitle = stringResource(R.string.settings_section_rendering)
    val renderingNote = stringResource(R.string.settings_rendering_note)

    Box(modifier.fillMaxSize()) {
        SubPage(title = pageTitle, onBack = onBack) {
            section(key = "playback_decoding", title = decodingTitle, note = decodingNote) {
                SegmentedChoice(
                    options = listOf(
                        Icons.Rounded.Memory to stringResource(R.string.decoder_hardware),
                        Icons.Rounded.Code to stringResource(R.string.decoder_software)
                    ),
                    selected = if (hardware.value) 0 else 1,
                    onSelect = { index ->
                        val wantHardware = index == 0
                        if (wantHardware != hardware.value) {
                            haptics.tick()
                            hardware.update(wantHardware)
                        }
                    },
                    modifier = Modifier.padding(12.dp)
                )
                if (hardware.value) {
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.SettingsBackupRestore,
                        title = stringResource(R.string.settings_software_fallback),
                        subtitle = stringResource(
                            if (softwareFallback.value) R.string.settings_software_fallback_on else R.string.settings_software_fallback_off
                        ),
                        checked = softwareFallback.value,
                        onCheckedChange = { softwareFallback.update(it) }
                    )
                }
            }
            section(key = "playback_deinterlace", title = deinterlaceTitle, note = deinterlaceNote) {
                val currentDeinterlace = Deinterlace.parse(deinterlace.value)
                Deinterlace.entries.forEach { option ->
                    ChoiceRow(
                        label = stringResource(deinterlaceLabel(option)),
                        detail = stringResource(deinterlaceDetail(option)),
                        selected = option == currentDeinterlace,
                        onClick = {
                            if (option != currentDeinterlace) {
                                haptics.tick()
                                deinterlace.update(option.name)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
            section(key = "playback_rendering", title = renderingTitle, note = renderingNote) {
                val current = RendererPreference.parse(renderer.value)
                SegmentedChoice(
                    options = listOf(
                        null to stringResource(R.string.renderer_opengl),
                        null to stringResource(R.string.renderer_vulkan)
                    ),
                    selected = if (current == RendererPreference.VULKAN) 1 else 0,
                    onSelect = { index ->
                        val choice = if (index == 1) RendererPreference.VULKAN else RendererPreference.OPENGL
                        if (choice != current) {
                            haptics.tick()
                            renderer.update(choice.name)
                        }
                    },
                    modifier = Modifier.padding(12.dp)
                )
            }
            section(key = "playback_scaling", title = scalingTitle) {
                val currentUp = Upscaler.parse(upscaler.value)
                val currentDown = Downscaler.parse(downscaler.value)
                SettingsRow(
                    icon = Icons.Rounded.ZoomIn,
                    title = upscalingTitle,
                    subtitle = stringResource(R.string.scaler_detail, stringResource(upscalerLabel(currentUp)), upscalerReads(currentUp)),
                    onClick = { showUpscalerDialog = true }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.ZoomOut,
                    title = downscalingTitle,
                    subtitle = stringResource(
                        R.string.scaler_detail,
                        stringResource(downscalerLabel(currentDown)),
                        pluralStringResource(R.plurals.scaler_reads_down, currentDown.reads, currentDown.reads)
                    ),
                    onClick = { showDownscalerDialog = true }
                )
            }
            section(key = "playback_antiring", title = antiringTitle, note = antiringNote) {
                ChoiceTiles(
                    options = antiringLabels,
                    selected = AntiringLevels.indexOf(antiring.value).let { if (it < 0) 2 else it },
                    onSelect = { index ->
                        haptics.tick()
                        antiring.update(AntiringLevels[index])
                    },
                    modifier = Modifier.padding(12.dp)
                )
            }
            section(key = "playback_performance", title = performanceTitle) {
                SettingsSwitchRow(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.settings_auto_quality),
                    subtitle = stringResource(R.string.settings_auto_quality_summary),
                    checked = autoQuality.value,
                    onCheckedChange = { autoQuality.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Bolt,
                    title = stringResource(R.string.settings_fast_decode),
                    subtitle = stringResource(R.string.settings_fast_decode_summary),
                    checked = fastDecode.value,
                    onCheckedChange = { fastDecode.update(it) }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.RestartAlt,
                    title = stringResource(R.string.settings_reset_learned),
                    subtitle = stringResource(R.string.settings_reset_learned_summary),
                    onClick = {
                        haptics.tick()
                        val editor = LuckyPrefs.get(context).edit()
                        RendererPreference.entries.forEach { editor.remove(LuckyPrefs.LEARNED_COST_PREFIX + it.name) }
                        editor.apply()
                        Toast.makeText(context, learnedResetText, Toast.LENGTH_SHORT).show()
                    }
                )
            }
            section(key = "playback_motion", title = motionTitle, note = motionNote) {
                val currentSmooth = SmoothMotion.parse(smoothMotion.value)
                SmoothMotion.entries.forEach { option ->
                    ChoiceRow(
                        label = stringResource(smoothMotionLabel(option)),
                        detail = stringResource(smoothMotionDetail(option)),
                        selected = option == currentSmooth,
                        onClick = {
                            if (option != currentSmooth) {
                                haptics.tick()
                                smoothMotion.update(option.name)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                if (currentSmooth == SmoothMotion.INTERPOLATE) {
                    RowDivider()
                    SubHeader(stringResource(R.string.motion_section))
                    SettingsRow(
                        icon = Icons.Rounded.AutoAwesomeMotion,
                        title = stringResource(R.string.motion_quality),
                        subtitle = stringResource(motionQualityLabel(MotionQuality.parse(motionQuality.value))),
                        onClick = { showQualityDialog = true }
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Rounded.ShutterSpeed,
                        title = stringResource(R.string.motion_target),
                        subtitle = motionTargetLabel(MotionTarget.parse(motionTarget.value), motionTargetFps.value),
                        onClick = { showTargetDialog = true }
                    )
                    RowDivider()
                    SubHeader(stringResource(R.string.motion_protection))
                    Text(
                        text = stringResource(R.string.motion_protection_note),
                        color = LocalLuckyColors.current.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    val protection = ArtifactProtection.parse(motionProtection.value)
                    ChoiceTiles(
                        options = ArtifactProtection.entries.map { stringResource(protectionLabel(it)) },
                        selected = protection.ordinal,
                        onSelect = { index ->
                            haptics.tick()
                            motionProtection.update(ArtifactProtection.entries[index].name)
                        },
                        modifier = Modifier.padding(4.dp)
                    )
                    SubHeader(stringResource(R.string.motion_scene))
                    Text(
                        text = stringResource(R.string.motion_scene_note),
                        color = LocalLuckyColors.current.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    val scene = SceneSensitivity.parse(motionScene.value)
                    ChoiceTiles(
                        options = SceneSensitivity.entries.map { stringResource(sceneLabel(it)) },
                        selected = scene.ordinal,
                        onSelect = { index ->
                            haptics.tick()
                            motionScene.update(SceneSensitivity.entries[index].name)
                        },
                        modifier = Modifier.padding(4.dp)
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Animation,
                        title = stringResource(R.string.motion_anime),
                        subtitle = stringResource(R.string.motion_anime_summary),
                        checked = motionAnime.value,
                        onCheckedChange = { motionAnime.update(it) }
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.SlowMotionVideo,
                        title = stringResource(R.string.motion_low_fps),
                        subtitle = stringResource(R.string.motion_low_fps_summary),
                        checked = motionLowFps.value,
                        onCheckedChange = { motionLowFps.update(it) }
                    )
                    RowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Rounded.BatterySaver,
                        title = stringResource(R.string.motion_battery),
                        subtitle = stringResource(R.string.motion_battery_summary),
                        checked = motionBattery.value,
                        onCheckedChange = { motionBattery.update(it) }
                    )
                    if (thermalSupported) {
                        RowDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Thermostat,
                            title = stringResource(R.string.motion_heat),
                            subtitle = stringResource(R.string.motion_heat_summary),
                            checked = motionHeat.value,
                            onCheckedChange = { motionHeat.update(it) }
                        )
                    }
                }
                RowDivider()
                SubHeader(stringResource(R.string.settings_match_refresh))
                val currentMatch = RefreshMatch.parse(refreshMatch.value)
                RefreshMatch.entries.forEach { option ->
                    ChoiceRow(
                        label = stringResource(if (option == RefreshMatch.ALWAYS) R.string.match_always else R.string.match_seamless),
                        detail = stringResource(if (option == RefreshMatch.ALWAYS) R.string.match_always_detail else R.string.match_seamless_detail),
                        selected = option == currentMatch,
                        onClick = {
                            if (option != currentMatch) {
                                haptics.tick()
                                refreshMatch.update(option.name)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
            section(key = "playback_hdr", title = hdrTitle, note = hdrNote) {
                val current = HdrOutput.parse(hdrOutput.value)
                HdrOutput.entries.forEach { option ->
                    ChoiceRow(
                        label = stringResource(hdrOutputLabel(option)),
                        detail = stringResource(hdrOutputDetail(option, screen.hdr10)),
                        selected = option == current,
                        onClick = {
                            if (option != current) {
                                haptics.tick()
                                hdrOutput.update(option.name)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.settings_adaptive_tone_mapping),
                    subtitle = stringResource(R.string.settings_adaptive_tone_mapping_summary),
                    checked = adaptiveToneMapping.value,
                    onCheckedChange = { adaptiveToneMapping.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_wide_color),
                    subtitle = stringResource(
                        if (screen.wideGamut) R.string.settings_wide_color_summary else R.string.settings_wide_color_unsupported
                    ),
                    checked = wideColor.value && screen.wideGamut,
                    onCheckedChange = { wideColor.update(it) },
                    enabled = screen.wideGamut
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.BrightnessHigh,
                    title = stringResource(R.string.settings_hdr_boost),
                    subtitle = stringResource(R.string.settings_hdr_boost_summary),
                    checked = hdrBoost.value,
                    onCheckedChange = { hdrBoost.update(it) }
                )
            }
            section(key = "playback_behavior", title = pageTitle) {
                SettingsSwitchRow(
                    icon = Icons.Rounded.Restore,
                    title = stringResource(R.string.settings_resume),
                    subtitle = stringResource(R.string.settings_resume_summary),
                    checked = resume.value,
                    onCheckedChange = { resume.update(it) }
                )
                RowDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.Subtitles,
                    title = stringResource(R.string.settings_auto_subtitles),
                    subtitle = stringResource(R.string.settings_auto_subtitles_summary),
                    checked = subtitles.value,
                    onCheckedChange = { subtitles.update(it) }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.TouchApp,
                    title = stringResource(R.string.settings_double_tap),
                    subtitle = pluralStringResource(R.plurals.settings_seek_seconds, seekSeconds.value, seekSeconds.value),
                    onClick = { showSeekDialog = true }
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Translate,
                    title = stringResource(R.string.settings_audio_language),
                    subtitle = audioLanguageLabel(audioLanguage.value, appLocale),
                    onClick = { showLanguageDialog = true }
                )
            }
        }

        SettingsDialog(
            visible = showQualityDialog,
            icon = Icons.Rounded.AutoAwesomeMotion,
            title = stringResource(R.string.motion_quality),
            header = stringResource(R.string.motion_quality_hint),
            onDismiss = { showQualityDialog = false }
        ) {
            val current = MotionQuality.parse(motionQuality.value)
            MotionQuality.entries.forEach { option ->
                ChoiceRow(
                    label = stringResource(motionQualityLabel(option)),
                    detail = stringResource(motionQualityDetail(option)),
                    selected = option == current,
                    onClick = {
                        if (option != current) {
                            haptics.tick()
                            motionQuality.update(option.name)
                        }
                        showQualityDialog = false
                    }
                )
            }
        }

        SettingsDialog(
            visible = showTargetDialog,
            icon = Icons.Rounded.ShutterSpeed,
            title = stringResource(R.string.motion_target),
            header = stringResource(R.string.motion_target_hint),
            onDismiss = { showTargetDialog = false }
        ) {
            val current = MotionTarget.parse(motionTarget.value)
            val maxFps = remember(context) { maxRefreshRate(context.findActivity()) }
            listOf(MotionTarget.SCREEN, MotionTarget.DOUBLE).forEach { option ->
                ChoiceRow(
                    label = motionTargetLabel(option, motionTargetFps.value),
                    selected = option == current,
                    onClick = {
                        if (option != current) {
                            haptics.tick()
                            motionTarget.update(option.name)
                        }
                        showTargetDialog = false
                    }
                )
            }
            FrameRateSlider(
                fps = motionTargetFps.value,
                maxFps = maxFps,
                active = current == MotionTarget.CUSTOM,
                onChange = { fps ->
                    if (fps != motionTargetFps.value) {
                        haptics.tick()
                        motionTargetFps.update(fps)
                    }
                    if (current != MotionTarget.CUSTOM) motionTarget.update(MotionTarget.CUSTOM.name)
                }
            )
        }

        SettingsDialog(
            visible = showUpscalerDialog,
            icon = Icons.Rounded.ZoomIn,
            title = upscalingTitle,
            header = upscalingNote,
            onDismiss = { showUpscalerDialog = false }
        ) {
            val current = Upscaler.parse(upscaler.value)
            Upscaler.entries.forEachIndexed { index, option ->
                if (index == 0 || Upscaler.entries[index - 1].tier != option.tier) TierHeader(option.tier)
                ChoiceRow(
                    label = stringResource(upscalerLabel(option)),
                    detail = scalerDetail(option.filter, option == Upscaler.BICUBIC, upscalerReads(option)),
                    selected = option == current,
                    onClick = {
                        if (option != current) {
                            haptics.tick()
                            upscaler.update(option.name)
                        }
                        showUpscalerDialog = false
                    }
                )
            }
        }

        SettingsDialog(
            visible = showDownscalerDialog,
            icon = Icons.Rounded.ZoomOut,
            title = downscalingTitle,
            header = downscalingNote,
            onDismiss = { showDownscalerDialog = false }
        ) {
            val current = Downscaler.parse(downscaler.value)
            Downscaler.entries.forEachIndexed { index, option ->
                if (index == 0 || Downscaler.entries[index - 1].tier != option.tier) TierHeader(option.tier)
                ChoiceRow(
                    label = stringResource(downscalerLabel(option)),
                    detail = scalerDetail(
                        option.filter,
                        option == Downscaler.AREA,
                        pluralStringResource(R.plurals.scaler_reads_down, option.reads, option.reads)
                    ),
                    selected = option == current,
                    onClick = {
                        if (option != current) {
                            haptics.tick()
                            downscaler.update(option.name)
                        }
                        showDownscalerDialog = false
                    }
                )
            }
        }

        SettingsDialog(
            visible = showLanguageDialog,
            icon = Icons.Rounded.Translate,
            title = stringResource(R.string.settings_audio_language),
            header = stringResource(R.string.settings_audio_language_hint),
            onDismiss = { showLanguageDialog = false }
        ) {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                (listOf(TrackLanguage.FILE_DEFAULT, TrackLanguage.APP) + languageOptions).forEach { option ->
                    ChoiceRow(
                        label = audioLanguageLabel(option, appLocale),
                        selected = option == audioLanguage.value,
                        onClick = {
                            haptics.tick()
                            audioLanguage.update(option)
                            showLanguageDialog = false
                        }
                    )
                }
            }
        }

        SettingsDialog(
            visible = showSeekDialog,
            icon = Icons.Rounded.TouchApp,
            title = stringResource(R.string.settings_double_tap),
            header = stringResource(R.string.settings_double_tap_hint),
            onDismiss = { showSeekDialog = false }
        ) {
            SeekStepOptions.forEach { seconds ->
                ChoiceRow(
                    label = pluralStringResource(R.plurals.settings_seek_seconds, seconds, seconds),
                    selected = seconds == seekSeconds.value,
                    onClick = {
                        haptics.tick()
                        seekSeconds.update(seconds)
                        showSeekDialog = false
                    }
                )
            }
        }
    }
}
