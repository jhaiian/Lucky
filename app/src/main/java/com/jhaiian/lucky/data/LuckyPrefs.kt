package com.jhaiian.lucky.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object LuckyPrefs {
    const val THEME = "app_theme"
    const val ACCENT = "accent_color"
    const val SURFACE_INTENSITY = "surface_intensity"
    const val HIDE_STATUS_BAR = "hide_status_bar"
    const val HIDE_NAVIGATION_BAR = "hide_system_navigation"

    const val HARDWARE_DECODING = "prefer_hardware_decoding"
    const val SOFTWARE_FALLBACK = "software_fallback"
    const val RENDERER = "preferred_renderer"
    const val UPSCALER = "video_upscaler"
    const val DOWNSCALER = "video_downscaler"
    const val ANTIRING = "video_antiring"
    const val AUTO_QUALITY = "video_auto_quality"
    const val FAST_DECODE = "video_fast_decode"
    const val DEINTERLACE = "video_deinterlace"
    const val LEARNED_COST_PREFIX = "video_learned_cost_"
    const val SMOOTH_MOTION = "video_smooth_motion"
    const val AUDIO_NIGHT = "audio_night"
    const val AUDIO_DIALOGUE = "audio_dialogue"
    const val AUDIO_NORMALIZE = "audio_normalize"
    const val AUDIO_CHANNELS = "audio_channels"
    const val AUDIO_BALANCE = "audio_balance"
    const val AUDIO_OUTPUT = "audio_output"
    const val AUDIO_PASSTHROUGH = "audio_passthrough"
    const val AUDIO_PASSTHROUGH_CODEC_PREFIX = "audio_passthrough_"
    const val AUDIO_EQ_PRESET = "audio_eq_preset"
    const val AUDIO_EQ_BANDS = "audio_eq_bands"
    const val MOTION_QUALITY = "motion_quality"
    const val MOTION_TARGET = "motion_target"
    const val MOTION_TARGET_FPS = "motion_target_fps"
    const val MOTION_ANIME = "motion_anime"
    const val MOTION_LOW_FPS_ONLY = "motion_low_fps_only"
    const val MOTION_BATTERY_SAVER = "motion_battery_saver"
    const val MOTION_HEAT = "motion_heat"
    const val LEARNED_MOTION_PREFIX = "motion_learned_"
    const val REFRESH_MATCH = "video_refresh_match"
    const val RESUME_PLAYBACK = "resume_playback"
    const val AUTO_SUBTITLES = "subtitles_auto"
    const val DOUBLE_TAP_SEEK_SECONDS = "double_tap_seek_seconds"
    const val PREFERRED_AUDIO_LANGUAGE = "preferred_audio_language"
    const val HDR_OUTPUT = "hdr_output"
    const val WIDE_COLOR = "wide_color"
    const val ADAPTIVE_TONE_MAPPING = "adaptive_tone_mapping"
    const val HDR_BRIGHTNESS_BOOST = "hdr_brightness_boost"

    const val LIBRARY_FOLDERS = "library_folders"
    const val LIBRARY_SOURCE = "library_source"
    const val LIBRARY_CORNERS = "library_corners"
    const val LIBRARY_SHOW_DURATION = "library_show_duration"
    const val LIBRARY_SHOW_BADGES = "library_show_badges"
    const val LIBRARY_SHOW_SCROLLBAR = "show_scrollbar"
    const val LIBRARY_ALPHABET_INDEX = "library_alphabet_index"
    const val HOME_HIDDEN_SECTIONS = "home_hidden_sections"
    const val LIBRARY_SORT_ORDER = "library_sort_order"
    const val LIBRARY_SORT_DIRECTION = "library_sort_direction"
    const val LIBRARY_VIEW_MODE_PREFIX = "library_view_mode_"

    const val WATCH_HISTORY_ENABLED = "watch_history_enabled"

    const val PLAYER_DEFAULT_FIT = "player_default_fit"
    const val PLAYER_ORIENTATION = "player_orientation"
    const val PLAYER_DOUBLE_TAP = "player_double_tap"
    const val PLAYER_SWIPE_SEEK = "player_swipe_seek"
    const val PLAYER_SWIPE_BRIGHTNESS = "player_swipe_brightness"
    const val PLAYER_SWIPE_VOLUME = "player_swipe_volume"
    const val PLAYER_SWAP_SIDES = "player_swap_sides"
    const val PLAYER_PINCH_ZOOM = "player_pinch_zoom"
    const val PLAYER_HOLD_SPEED = "player_hold_speed"
    const val PLAYER_CONTROLS_TIMEOUT = "player_controls_timeout"
    const val PLAYER_SEEK_BUTTONS = "player_seek_buttons"
    const val PLAYER_SHOW_REMAINING = "player_show_remaining"
    const val PLAYER_SHOW_CLOCK = "player_show_clock"
    const val PLAYER_CONTROLS_SIZE = "player_controls_size"
    const val PLAYER_QUICK_BUTTONS = "player_quick_buttons"
    const val PLAYER_QUICK_MOTION = "player_quick_motion"
    const val PLAYER_QUICK_BACKGROUND = "player_quick_background"
    const val PLAYER_SEEK_STYLE = "player_seek_style"
    const val PLAYER_ICON_TINT = "player_icon_tint"
    const val PLAYER_ACCENT_TINT = "player_accent_tint"
    const val PLAYER_LAYOUT = "player_layout"
    const val PLAYER_BUTTON_STYLE = "player_button_style"
    const val PLAYER_PLAY_SHAPE = "player_play_shape"
    const val PLAYER_SCRIM = "player_scrim"
    const val PLAYER_TIME_LAYOUT = "player_time_layout"
    const val PLAYER_CONTROLS_MOTION = "player_controls_motion"
    const val PLAYER_ACCENT_GLOW = "player_accent_glow"
    const val PLAYER_QUICK_ORDER = "player_quick_order"
    const val PLAYER_TOP_BUTTONS = "player_top_buttons"
    const val PLAYER_PANEL_WIDTH = "player_panel_width"
    const val PLAYER_PANEL_HEIGHT = "player_panel_height"
    const val PLAYER_AUTOPLAY_NEXT = "player_autoplay_next"
    const val PLAYER_REPEAT = "player_repeat"
    const val PLAYER_AUTO_PIP = "player_auto_pip"
    const val PLAYER_BACKGROUND_PLAY = "player_background_play"
    const val PLAYER_REMEMBER_BRIGHTNESS = "player_remember_brightness"
    const val PLAYER_BRIGHTNESS = "player_brightness"
    const val PLAYER_REMEMBER_SPEED = "player_remember_speed"
    const val PLAYER_SPEED = "player_speed"
    const val PLAYER_REMEMBER_VOLUME = "player_remember_volume"
    const val PLAYER_VOLUME = "player_volume"
    const val PLAYER_VOLUME_BOOST = "player_volume_boost"
    const val SUBTITLE_SIZE = "subtitle_size"
    const val SUBTITLE_COLOR = "subtitle_color"
    const val SUBTITLE_EDGE = "subtitle_edge"
    const val SUBTITLE_BOLD = "subtitle_bold"
    const val SUBTITLE_POSITION = "subtitle_position"
    const val SUBTITLE_SYSTEM_STYLE = "subtitle_system_style"
    const val SUBTITLE_LIBASS = "subtitle_libass"

    const val HOME_SECTION_CONTINUE = "continue"
    const val HOME_SECTION_RECENT = "recently_added"

    fun get(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun hardwareDecoding(context: Context) = get(context).getBoolean(HARDWARE_DECODING, true)
    fun resumePlayback(context: Context) = get(context).getBoolean(RESUME_PLAYBACK, true)
    fun autoSubtitles(context: Context) = get(context).getBoolean(AUTO_SUBTITLES, true)
    fun doubleTapSeekSeconds(context: Context) = get(context).getInt(DOUBLE_TAP_SEEK_SECONDS, 10)
    fun watchHistoryEnabled(context: Context) = get(context).getBoolean(WATCH_HISTORY_ENABLED, true)
    fun corners(context: Context) = get(context).getString(LIBRARY_CORNERS, "rounded") ?: "rounded"
    fun showDuration(context: Context) = get(context).getBoolean(LIBRARY_SHOW_DURATION, true)
    fun showBadges(context: Context) = get(context).getBoolean(LIBRARY_SHOW_BADGES, true)
    fun showScrollbar(context: Context) = get(context).getBoolean(LIBRARY_SHOW_SCROLLBAR, true)
    fun alphabetIndex(context: Context) = get(context).getBoolean(LIBRARY_ALPHABET_INDEX, true)
    fun hiddenHomeSections(context: Context): Set<String> = get(context).getStringSet(HOME_HIDDEN_SECTIONS, emptySet()) ?: emptySet()

    fun cornerScale(corners: String): Float = when (corners) {
        "sharp" -> 0.25f
        "round" -> 1.7f
        else -> 1f
    }
}
