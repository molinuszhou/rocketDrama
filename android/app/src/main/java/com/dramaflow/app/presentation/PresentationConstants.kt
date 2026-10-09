package com.dramaflow.app.presentation

/** Product-wide presentation constants. Feature modules consume these without a supplier-specific namespace. */
internal const val MAX_CATALOG_GENRES = 6
internal const val SHELF_ITEM_LIMIT = 6
internal const val FREE_EPISODES = 3
internal const val BOTTOM_NAV_HEIGHT_DP = 82
internal const val PREFERENCES = "joyedrama.short_drama"
internal const val PLAYBACK_HISTORY_PREFERENCES = "joyedrama.playback_history"
internal const val INSTALLATION_KEY = "anonymous_installation_id"
internal const val SESSION_TOKEN_KEY = "rewards_session_token"
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
internal const val COLOR_BACKGROUND = 0xFF000000.toInt()
internal const val COLOR_CARD = 0xFF131316.toInt()
internal const val COLOR_SURFACE = 0xFF1D1D22.toInt()
internal const val COLOR_ACCENT = 0xFFFF704D.toInt()
internal const val COLOR_PURPLE = 0xFFFFBE62.toInt()
internal const val COLOR_INK = 0xFF0D0D0F.toInt()
internal const val COLOR_PLUM = 0xFF231A2C.toInt()
internal const val COLOR_CORAL = 0xFFFF704D.toInt()
internal const val COLOR_SUNSET = 0xFFFFA94D.toInt()
// Shared with the floating cash wheel so wallet and bonus-cash labels read as one asset family.
internal const val COLOR_FLOATING_GOLD = 0xFFFFD35C.toInt()
// Home drama metadata is deliberately warmer and more yellow than the coral category selector.
internal const val COLOR_HOME_EPISODE_GOLD = 0xFFE3A84A.toInt()
internal const val COLOR_AQUA = 0xFF56CEC1.toInt()
internal const val COLOR_MIST_BLUE = 0xFF10151B.toInt()
internal const val COLOR_WARM_PEACH = 0xFF211815.toInt()
internal const val COLOR_TEXT_PRIMARY = 0xFFF6F6F8.toInt()
internal const val COLOR_TEXT_MUTED = 0xFFA8A8B2.toInt()
