package com.dramaflow.app.presentation

/** Product-wide presentation constants. Feature modules consume these without a supplier-specific namespace. */
internal const val MAX_CATALOG_GENRES = 6
internal const val SHELF_ITEM_LIMIT = 6
internal const val FREE_EPISODES = 3
internal const val PREFERENCES = "joyedrama.short_drama"
internal const val PLAYBACK_HISTORY_PREFERENCES = "joyedrama.playback_history"
internal const val INSTALLATION_KEY = "anonymous_installation_id"
internal const val STATE_PLAYER_VISIBLE = "state_player_visible"
internal const val STATE_DRAMA_ID = "state_drama_id"
internal const val STATE_EPISODE_INDEX = "state_episode_index"
internal const val STATE_POSITION_SECONDS = "state_position_seconds"
internal const val AD_RETURN_DRAMA_ID = "ad_return_drama_id"
internal const val AD_RETURN_EPISODE_INDEX = "ad_return_episode_index"
internal const val AD_RETURN_POSITION_SECONDS = "ad_return_position_seconds"
internal const val AD_RETURN_SAVED_AT_MILLIS = "ad_return_saved_at_millis"
internal const val AD_RETURN_CHECKPOINT_MAX_AGE_MILLIS = 10 * 60 * 1_000L
internal const val TAG = "JoyeDramaPlayer"
/**
 * JoyeDrama 的主视觉是雨夜暖光：深棕黑承接内容，琥珀与暖金提示进度、余额和导航。
 * 仅“领取 / 加速”等可操作奖励使用发光绿色，避免绿色吞没短剧内容的情绪氛围。
 */
internal const val COLOR_BACKGROUND = 0xFF120D09.toInt()
internal const val COLOR_CARD = 0xD4211712.toInt()
internal const val COLOR_SURFACE = 0xCC2D2017.toInt()
internal const val COLOR_ACCENT = 0xFFFFA429.toInt()
internal const val COLOR_PURPLE = 0xFFFFD58A.toInt()
internal const val COLOR_INK = 0xFF0C0806.toInt()
internal const val COLOR_PLUM = 0xFF332015.toInt()
internal const val COLOR_CORAL = 0xFFFF8D25.toInt()
internal const val COLOR_SUNSET = 0xFFFFD27A.toInt()
internal const val COLOR_AQUA = 0xFFFFB35A.toInt()
internal const val COLOR_MIST_BLUE = 0xFF1B120C.toInt()
internal const val COLOR_WARM_PEACH = 0xFF3B2517.toInt()
internal const val COLOR_ACTION_GREEN = 0xFF35D979.toInt()
internal const val COLOR_ACTION_GREEN_GLOW = 0xFF9AF4BB.toInt()
internal const val COLOR_TEXT_PRIMARY = 0xFFF6F6F8.toInt()
internal const val COLOR_TEXT_MUTED = 0xFFC5B7A9.toInt()
