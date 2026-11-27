package com.jhaiian.lucky.ui.player

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.jhaiian.lucky.R
import com.jhaiian.lucky.data.LuckyPrefs
import com.jhaiian.lucky.engine.FitMode
import com.jhaiian.lucky.engine.HdrOutput
import com.jhaiian.lucky.engine.RefreshMatch
import com.jhaiian.lucky.engine.AudioChannels
import com.jhaiian.lucky.engine.AudioNormalize
import com.jhaiian.lucky.engine.AudioOutputOptions
import com.jhaiian.lucky.engine.Deinterlace
import com.jhaiian.lucky.engine.MotionQuality
import com.jhaiian.lucky.engine.MotionTarget
import com.jhaiian.lucky.engine.SmoothMotion
import com.jhaiian.lucky.ui.library.rememberPreferences

enum class OrientationMode(val key: String, val labelRes: Int) {
    VIDEO("video", R.string.player_orientation_video),
    SENSOR("sensor", R.string.player_orientation_sensor),
    LANDSCAPE("landscape", R.string.player_orientation_landscape),
    PORTRAIT("portrait", R.string.player_orientation_portrait),
    SYSTEM("system", R.string.player_orientation_system);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: VIDEO
    }
}

enum class DoubleTapAction(val key: String, val labelRes: Int) {
    SEEK("seek", R.string.player_double_tap_seek),
    PLAY_PAUSE("play_pause", R.string.player_double_tap_play_pause),
    OFF("off", R.string.player_option_off);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: SEEK
    }
}

enum class ControlsSize(val key: String, val labelRes: Int, val scale: Float) {
    SMALL("small", R.string.player_size_small, 0.82f),
    NORMAL("normal", R.string.player_size_normal, 1f),
    LARGE("large", R.string.player_size_large, 1.2f);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: NORMAL
    }
}

enum class RepeatMode(val key: String, val labelRes: Int) {
    OFF("off", R.string.player_repeat_off),
    ONE("one", R.string.player_repeat_one),
    ALL("all", R.string.player_repeat_all);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: OFF
    }
}

enum class QuickButton(val key: String, val labelRes: Int) {
    LOCK("lock", R.string.player_button_lock),
    ASPECT("aspect", R.string.player_button_aspect),
    ROTATE("rotate", R.string.player_button_rotate),
    SPEED("speed", R.string.player_button_speed),
    REPEAT("repeat", R.string.player_button_repeat),
    MIRROR("mirror", R.string.player_button_mirror),
    PIP("pip", R.string.player_button_pip),
    BACKGROUND("background", R.string.player_button_background),
    MOTION("motion", R.string.player_button_motion),
    LOOP("loop", R.string.player_button_ab_repeat),
    SCREENSHOT("screenshot", R.string.player_button_screenshot);

    companion object {
        val defaults: Set<String> = setOf(LOCK.key, ASPECT.key, ROTATE.key, SPEED.key, PIP.key, BACKGROUND.key, MOTION.key)

        fun read(prefs: SharedPreferences): Set<String> {
            val stored = prefs.getStringSet(LuckyPrefs.PLAYER_QUICK_BUTTONS, null)?.toSet() ?: return defaults
            val withMotion = if (prefs.getBoolean(LuckyPrefs.PLAYER_QUICK_MOTION, false)) stored else stored + MOTION.key
            return if (prefs.getBoolean(LuckyPrefs.PLAYER_QUICK_BACKGROUND, false)) withMotion else withMotion + BACKGROUND.key
        }

        fun parseOrder(value: String?): List<QuickButton> {
            val stored = value.orEmpty().split(',').mapNotNull { key -> entries.firstOrNull { it.key == key.trim() } }.distinct().toMutableList()
            if (BACKGROUND !in stored && PIP in stored) stored.add(stored.indexOf(PIP) + 1, BACKGROUND)
            return stored + entries.filter { it !in stored }
        }

        fun encodeOrder(order: List<QuickButton>): String = order.joinToString(",") { it.key }

        fun order(prefs: SharedPreferences): List<QuickButton> = parseOrder(prefs.getString(LuckyPrefs.PLAYER_QUICK_ORDER, null))
    }
}

enum class TopButton(val key: String, val labelRes: Int) {
    CHAPTERS("chapters", R.string.player_chapters),
    SUBTITLES("subtitles", R.string.track_subtitle),
    AUDIO("audio", R.string.track_audio),
    STATS("stats", R.string.player_stats);

    companion object {
        val defaults: Set<String> = entries.map { it.key }.toSet()

        fun read(prefs: SharedPreferences): Set<String> =
            prefs.getStringSet(LuckyPrefs.PLAYER_TOP_BUTTONS, null)?.toSet() ?: defaults
    }
}

enum class ControlsLayout(val key: String, val labelRes: Int) {
    CENTER("center", R.string.player_layout_center),
    DOCKED("docked", R.string.player_layout_docked);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: CENTER
    }
}

enum class ButtonStyle(val key: String, val labelRes: Int) {
    PLAIN("plain", R.string.player_button_style_plain),
    BUBBLE("bubble", R.string.player_button_style_bubble),
    GLASS("glass", R.string.player_button_style_glass);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: GLASS
    }
}

enum class PlayShape(val key: String, val labelRes: Int) {
    ROUNDED("rounded", R.string.player_play_shape_rounded),
    CIRCLE("circle", R.string.player_play_shape_circle),
    RING("ring", R.string.player_play_shape_ring),
    BARE("bare", R.string.player_play_shape_bare);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: ROUNDED
    }
}

enum class ScrimLevel(val key: String, val labelRes: Int, val strength: Float) {
    NONE("none", R.string.player_scrim_none, 0f),
    LIGHT("light", R.string.player_scrim_light, 0.6f),
    MEDIUM("medium", R.string.player_scrim_medium, 1f),
    STRONG("strong", R.string.player_scrim_strong, 1.3f);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: MEDIUM
    }
}

enum class TimeLayout(val key: String, val labelRes: Int) {
    SPLIT("split", R.string.player_time_split),
    INLINE("inline", R.string.player_time_inline),
    ABOVE("above", R.string.player_time_above);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: SPLIT
    }
}

enum class ControlsMotion(val key: String, val labelRes: Int) {
    SLIDE("slide", R.string.player_motion_style_slide),
    FADE("fade", R.string.player_motion_style_fade),
    POP("pop", R.string.player_motion_style_pop);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: SLIDE
    }
}

enum class SeekStyle(val key: String, val labelRes: Int) {
    SLIDER("slider", R.string.seek_style_slider),
    THIN("thin", R.string.seek_style_thin),
    BAR("bar", R.string.seek_style_bar),
    SQUIGGLE("squiggle", R.string.seek_style_squiggle),
    DOTS("dots", R.string.seek_style_dots),
    CAPSULE("capsule", R.string.seek_style_capsule),
    GLOW("glow", R.string.seek_style_glow);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: SLIDER
    }
}

enum class PlayerTint(val key: String, val labelRes: Int, val color: Color?) {
    WHITE("white", R.string.player_tint_white, Color.White),
    THEME("theme", R.string.player_tint_theme, null),
    AMBER("amber", R.string.player_tint_amber, Color(0xFFFFC857)),
    ROSE("rose", R.string.player_tint_rose, Color(0xFFFF7A9C)),
    SKY("sky", R.string.player_tint_sky, Color(0xFF6EC8FF)),
    MINT("mint", R.string.player_tint_mint, Color(0xFF6EE7B7)),
    LAVENDER("lavender", R.string.player_tint_lavender, Color(0xFFB9A6FF)),
    CORAL("coral", R.string.player_tint_coral, Color(0xFFFF8A65));

    fun resolve(theme: Color): Color = color ?: theme

    companion object {
        val accents: List<PlayerTint> = entries.filter { it != WHITE }

        fun parse(value: String?, fallback: PlayerTint) = entries.firstOrNull { it.key == value } ?: fallback
    }
}

enum class SubtitleColor(val key: String, val labelRes: Int, val color: Color) {
    WHITE("white", R.string.subtitle_color_white, Color.White),
    YELLOW("yellow", R.string.subtitle_color_yellow, Color(0xFFFFE14D)),
    CYAN("cyan", R.string.subtitle_color_cyan, Color(0xFF7FE7FF)),
    GREEN("green", R.string.subtitle_color_green, Color(0xFF9CF59C)),
    PINK("pink", R.string.subtitle_color_pink, Color(0xFFFFA8D8));

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: WHITE
    }
}

enum class SubtitleEdge(val key: String, val labelRes: Int) {
    OUTLINE("outline", R.string.subtitle_edge_outline),
    SHADOW("shadow", R.string.subtitle_edge_shadow),
    BOX("box", R.string.subtitle_edge_box),
    NONE("none", R.string.subtitle_edge_none);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: OUTLINE
    }
}

enum class SubtitlePosition(val key: String, val labelRes: Int, val margin: Float) {
    LOW("low", R.string.subtitle_position_low, 0.025f),
    NORMAL("normal", R.string.subtitle_position_normal, 0.06f),
    HIGH("high", R.string.subtitle_position_high, 0.14f);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: NORMAL
    }
}

val FitModeLabels = mapOf(
    FitMode.FIT to R.string.player_fit_fit,
    FitMode.FILL to R.string.player_fit_fill,
    FitMode.STRETCH to R.string.player_fit_stretch,
    FitMode.ORIGINAL to R.string.player_fit_original
)

val SubtitleSizes = listOf(75, 100, 125, 150, 200)
val HoldSpeeds = listOf(0f, 1.5f, 2f, 3f)
val ControlsTimeouts = listOf(2, 3, 4, 6, 10, 0)
val SpeedPresets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
val SleepPresets = listOf(15, 30, 45, 60, 90)

class AspectPreset(val label: String, val ratio: Float)

val AspectPresets = listOf(
    AspectPreset("16:9", 16f / 9f),
    AspectPreset("4:3", 4f / 3f),
    AspectPreset("21:9", 21f / 9f),
    AspectPreset("18:9", 2f),
    AspectPreset("2.35:1", 2.35f),
    AspectPreset("1:1", 1f),
    AspectPreset("9:16", 9f / 16f)
)

@Immutable
data class PlayerLook(
    val layout: ControlsLayout,
    val buttonStyle: ButtonStyle,
    val playShape: PlayShape,
    val scrim: ScrimLevel,
    val timeLayout: TimeLayout,
    val motion: ControlsMotion,
    val glow: Boolean,
    val seekStyle: SeekStyle,
    val iconTint: PlayerTint,
    val accentTint: PlayerTint,
    val controlsSize: ControlsSize
) {
    companion object {
        val lookKeys: List<String> = listOf(
            LuckyPrefs.PLAYER_LAYOUT,
            LuckyPrefs.PLAYER_BUTTON_STYLE,
            LuckyPrefs.PLAYER_PLAY_SHAPE,
            LuckyPrefs.PLAYER_SCRIM,
            LuckyPrefs.PLAYER_TIME_LAYOUT,
            LuckyPrefs.PLAYER_CONTROLS_MOTION,
            LuckyPrefs.PLAYER_ACCENT_GLOW,
            LuckyPrefs.PLAYER_SEEK_STYLE,
            LuckyPrefs.PLAYER_ICON_TINT,
            LuckyPrefs.PLAYER_ACCENT_TINT,
            LuckyPrefs.PLAYER_CONTROLS_SIZE
        )

        fun from(prefs: SharedPreferences): PlayerLook = PlayerLook(
            layout = ControlsLayout.parse(prefs.getString(LuckyPrefs.PLAYER_LAYOUT, null)),
            buttonStyle = ButtonStyle.parse(prefs.getString(LuckyPrefs.PLAYER_BUTTON_STYLE, null)),
            playShape = PlayShape.parse(prefs.getString(LuckyPrefs.PLAYER_PLAY_SHAPE, null)),
            scrim = ScrimLevel.parse(prefs.getString(LuckyPrefs.PLAYER_SCRIM, null)),
            timeLayout = TimeLayout.parse(prefs.getString(LuckyPrefs.PLAYER_TIME_LAYOUT, null)),
            motion = ControlsMotion.parse(prefs.getString(LuckyPrefs.PLAYER_CONTROLS_MOTION, null)),
            glow = prefs.getBoolean(LuckyPrefs.PLAYER_ACCENT_GLOW, true),
            seekStyle = SeekStyle.parse(prefs.getString(LuckyPrefs.PLAYER_SEEK_STYLE, null)),
            iconTint = PlayerTint.parse(prefs.getString(LuckyPrefs.PLAYER_ICON_TINT, null), PlayerTint.WHITE),
            accentTint = PlayerTint.parse(prefs.getString(LuckyPrefs.PLAYER_ACCENT_TINT, null), PlayerTint.THEME).takeIf { it != PlayerTint.WHITE } ?: PlayerTint.THEME,
            controlsSize = ControlsSize.parse(prefs.getString(LuckyPrefs.PLAYER_CONTROLS_SIZE, null))
        )
    }
}

data class SubtitleLook(
    val sizePercent: Int,
    val color: SubtitleColor,
    val edge: SubtitleEdge,
    val bold: Boolean,
    val position: SubtitlePosition,
    val followSystem: Boolean
)

data class PlayerConfig(
    val defaultFit: FitMode,
    val orientation: OrientationMode,
    val doubleTap: DoubleTapAction,
    val seekStepMs: Long,
    val swipeSeek: Boolean,
    val swipeBrightness: Boolean,
    val swipeVolume: Boolean,
    val swapSides: Boolean,
    val pinchZoom: Boolean,
    val holdSpeed: Float,
    val controlsTimeoutSeconds: Int,
    val seekButtons: Boolean,
    val showRemaining: Boolean,
    val showClock: Boolean,
    val quickButtons: Set<String>,
    val quickOrder: List<QuickButton>,
    val topButtons: Set<String>,
    val autoplayNext: Boolean,
    val repeat: RepeatMode,
    val autoPip: Boolean,
    val backgroundPlay: Boolean,
    val rememberBrightness: Boolean,
    val rememberSpeed: Boolean,
    val rememberVolume: Boolean,
    val hdrOutput: HdrOutput,
    val wideColor: Boolean,
    val adaptiveToneMapping: Boolean,
    val hdrBoost: Boolean,
    val antiring: Int,
    val autoQuality: Boolean,
    val fastDecode: Boolean,
    val softwareFallback: Boolean,
    val deinterlace: Deinterlace,
    val smoothMotion: SmoothMotion,
    val motionQuality: MotionQuality,
    val motionTarget: MotionTarget,
    val motionTargetFps: Int,
    val motionAnime: Boolean,
    val motionLowFpsOnly: Boolean,
    val motionBatterySaver: Boolean,
    val motionHeat: Boolean,
    val refreshMatch: RefreshMatch,
    val audioNight: Boolean,
    val audioDialogue: Boolean,
    val audioChannels: AudioChannels,
    val audioOutput: AudioOutputOptions,
    val audioBalance: Float,
    val audioNormalize: Boolean,
    val equalizer: Equalizer,
    val subtitles: SubtitleLook,
    val textSubtitlesLibass: Boolean,
    val look: PlayerLook
) {
    companion object {
        fun from(prefs: SharedPreferences): PlayerConfig = PlayerConfig(
            defaultFit = FitMode.parse(prefs.getString(LuckyPrefs.PLAYER_DEFAULT_FIT, null)),
            orientation = OrientationMode.parse(prefs.getString(LuckyPrefs.PLAYER_ORIENTATION, null)),
            doubleTap = DoubleTapAction.parse(prefs.getString(LuckyPrefs.PLAYER_DOUBLE_TAP, null)),
            seekStepMs = prefs.getInt(LuckyPrefs.DOUBLE_TAP_SEEK_SECONDS, 10).coerceIn(1, 120) * 1000L,
            swipeSeek = prefs.getBoolean(LuckyPrefs.PLAYER_SWIPE_SEEK, true),
            swipeBrightness = prefs.getBoolean(LuckyPrefs.PLAYER_SWIPE_BRIGHTNESS, true),
            swipeVolume = prefs.getBoolean(LuckyPrefs.PLAYER_SWIPE_VOLUME, true),
            swapSides = prefs.getBoolean(LuckyPrefs.PLAYER_SWAP_SIDES, false),
            pinchZoom = prefs.getBoolean(LuckyPrefs.PLAYER_PINCH_ZOOM, true),
            holdSpeed = prefs.getFloat(LuckyPrefs.PLAYER_HOLD_SPEED, 2f),
            controlsTimeoutSeconds = prefs.getInt(LuckyPrefs.PLAYER_CONTROLS_TIMEOUT, 4),
            seekButtons = prefs.getBoolean(LuckyPrefs.PLAYER_SEEK_BUTTONS, true),
            showRemaining = prefs.getBoolean(LuckyPrefs.PLAYER_SHOW_REMAINING, false),
            showClock = prefs.getBoolean(LuckyPrefs.PLAYER_SHOW_CLOCK, false),
            quickButtons = QuickButton.read(prefs),
            quickOrder = QuickButton.order(prefs),
            topButtons = TopButton.read(prefs),
            autoplayNext = prefs.getBoolean(LuckyPrefs.PLAYER_AUTOPLAY_NEXT, true),
            repeat = RepeatMode.parse(prefs.getString(LuckyPrefs.PLAYER_REPEAT, null)),
            autoPip = prefs.getBoolean(LuckyPrefs.PLAYER_AUTO_PIP, true),
            backgroundPlay = prefs.getBoolean(LuckyPrefs.PLAYER_BACKGROUND_PLAY, false),
            rememberBrightness = prefs.getBoolean(LuckyPrefs.PLAYER_REMEMBER_BRIGHTNESS, false),
            rememberSpeed = prefs.getBoolean(LuckyPrefs.PLAYER_REMEMBER_SPEED, false),
            rememberVolume = prefs.getBoolean(LuckyPrefs.PLAYER_REMEMBER_VOLUME, true),
            hdrOutput = HdrOutput.parse(prefs.getString(LuckyPrefs.HDR_OUTPUT, null)),
            wideColor = prefs.getBoolean(LuckyPrefs.WIDE_COLOR, true),
            adaptiveToneMapping = prefs.getBoolean(LuckyPrefs.ADAPTIVE_TONE_MAPPING, true),
            hdrBoost = prefs.getBoolean(LuckyPrefs.HDR_BRIGHTNESS_BOOST, true),
            antiring = prefs.getInt(LuckyPrefs.ANTIRING, 50).coerceIn(0, 100),
            autoQuality = prefs.getBoolean(LuckyPrefs.AUTO_QUALITY, true),
            fastDecode = prefs.getBoolean(LuckyPrefs.FAST_DECODE, true),
            softwareFallback = prefs.getBoolean(LuckyPrefs.SOFTWARE_FALLBACK, true),
            deinterlace = Deinterlace.parse(prefs.getString(LuckyPrefs.DEINTERLACE, null)),
            smoothMotion = SmoothMotion.parse(prefs.getString(LuckyPrefs.SMOOTH_MOTION, null)),
            motionQuality = MotionQuality.parse(prefs.getString(LuckyPrefs.MOTION_QUALITY, null)),
            motionTarget = MotionTarget.parse(prefs.getString(LuckyPrefs.MOTION_TARGET, null)),
            motionTargetFps = prefs.getInt(
                LuckyPrefs.MOTION_TARGET_FPS,
                MotionTarget.legacyFps(prefs.getString(LuckyPrefs.MOTION_TARGET, null)) ?: MotionTarget.DEFAULT_FPS
            ),
            motionAnime = prefs.getBoolean(LuckyPrefs.MOTION_ANIME, false),
            motionLowFpsOnly = prefs.getBoolean(LuckyPrefs.MOTION_LOW_FPS_ONLY, false),
            motionBatterySaver = prefs.getBoolean(LuckyPrefs.MOTION_BATTERY_SAVER, true),
            motionHeat = prefs.getBoolean(LuckyPrefs.MOTION_HEAT, true),
            refreshMatch = RefreshMatch.parse(prefs.getString(LuckyPrefs.REFRESH_MATCH, null)),
            audioNight = prefs.getBoolean(LuckyPrefs.AUDIO_NIGHT, false),
            audioDialogue = prefs.getBoolean(LuckyPrefs.AUDIO_DIALOGUE, false),
            audioChannels = AudioChannels.parse(prefs.getString(LuckyPrefs.AUDIO_CHANNELS, null)),
            audioOutput = readAudioOutput(prefs),
            audioBalance = prefs.getFloat(LuckyPrefs.AUDIO_BALANCE, 0f).coerceIn(-1f, 1f),
            audioNormalize = AudioNormalize.parse(prefs.getString(LuckyPrefs.AUDIO_NORMALIZE, null)) == AudioNormalize.NORMALIZE,
            equalizer = readEqualizer(prefs),
            subtitles = SubtitleLook(
                sizePercent = prefs.getInt(LuckyPrefs.SUBTITLE_SIZE, 100),
                color = SubtitleColor.parse(prefs.getString(LuckyPrefs.SUBTITLE_COLOR, null)),
                edge = SubtitleEdge.parse(prefs.getString(LuckyPrefs.SUBTITLE_EDGE, null)),
                bold = prefs.getBoolean(LuckyPrefs.SUBTITLE_BOLD, false),
                position = SubtitlePosition.parse(prefs.getString(LuckyPrefs.SUBTITLE_POSITION, null)),
                followSystem = prefs.getBoolean(LuckyPrefs.SUBTITLE_SYSTEM_STYLE, false)
            ),
            textSubtitlesLibass = prefs.getBoolean(LuckyPrefs.SUBTITLE_LIBASS, true),
            look = PlayerLook.from(prefs)
        )
    }
}

@Composable
fun rememberPlayerConfig(): PlayerConfig = PlayerConfig.from(rememberPreferences())
