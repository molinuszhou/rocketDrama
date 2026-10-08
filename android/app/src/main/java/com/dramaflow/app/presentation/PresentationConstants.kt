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
 * 深色墨绿是产品操作与正向状态色；暖金仅用于余额、现金与已获得奖励，
 * 避免红色在西方语境中被误读为风险或错误。
 */
internal const val COLOR_BACKGROUND = 0xFF08120E.toInt()
internal const val COLOR_CARD = 0xFF101D17.toInt()
internal const val COLOR_SURFACE = 0xFF182820.toInt()
internal const val COLOR_ACCENT = 0xFF2FB66B.toInt()
internal const val COLOR_PURPLE = 0xFF83DFA2.toInt()
internal const val COLOR_INK = 0xFF07100B.toInt()
internal const val COLOR_PLUM = 0xFF143022.toInt()
internal const val COLOR_CORAL = 0xFF35C875.toInt()
internal const val COLOR_SUNSET = 0xFFFFCC69.toInt()
internal const val COLOR_AQUA = 0xFF55CDB3.toInt()
internal const val COLOR_MIST_BLUE = 0xFF0F1B16.toInt()
internal const val COLOR_WARM_PEACH = 0xFF202A1B.toInt()
internal const val COLOR_TEXT_PRIMARY = 0xFFF6F6F8.toInt()
internal const val COLOR_TEXT_MUTED = 0xFFA8A8B2.toInt()
