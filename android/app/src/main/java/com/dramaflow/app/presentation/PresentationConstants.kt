package com.dramaflow.app.presentation

/** Product-wide presentation constants. Feature modules consume these without a supplier-specific namespace. */
internal const val MAX_CATALOG_GENRES = 6
internal const val SHELF_ITEM_LIMIT = 6
internal const val FREE_EPISODES = 3
internal const val BOTTOM_NAV_HEIGHT_DP = 82
// Mirrors the current production first damping-tier policy (2,900,000 reward progress units).
// Debug builds deliberately bypass this threshold to make the disclosure easy to review.
internal const val FIRST_DAMPING_TIER_NOTICE_THRESHOLD_UNITS = 2_900_000L
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
internal const val AD_RETURN_EXTERNAL_SAVED_AT_MILLIS = "ad_return_external_saved_at_millis"
internal const val AD_RETURN_CHECKPOINT_MAX_AGE_MILLIS = 10 * 60 * 1_000L
internal const val TAG = "JoyeDramaPlayer"
internal const val COLOR_BACKGROUND = 0xFF000000.toInt()
internal const val COLOR_CARD = 0xFF131316.toInt()
internal const val COLOR_SURFACE = 0xFF1D1D22.toInt()
// Positive text uses the vivid neon green from the approved reference rather than muted mint variants.
internal const val COLOR_ACCENT = 0xFF00F035.toInt()
internal const val COLOR_PURPLE = 0xFF00F035.toInt()
// The only intentional warm-yellow accent is the selected item in the bottom tab bar.
internal const val COLOR_TAB_ACTIVE = 0xFFFFC947.toInt()
internal const val COLOR_INK = 0xFF0D0D0F.toInt()
internal const val COLOR_PLUM = 0xFF231A2C.toInt()
internal const val COLOR_CORAL = 0xFF00F035.toInt()
internal const val COLOR_SUNSET = 0xFF00F035.toInt()
// Positive cash received/available amounts use the same vivid green as the reference amount text.
internal const val COLOR_REWARD_GAIN = 0xFF00F035.toInt()
// Reward Centre actions intentionally transition from vivid cyan-green to lime, echoing the reference's
// high-energy claim controls while keeping warm yellow out of the surrounding UI.
internal const val COLOR_REWARD_ACTION_START = 0xFF00F035.toInt()
internal const val COLOR_REWARD_ACTION_END = 0xFFD4FF3F.toInt()
internal const val COLOR_REWARD_ACTION_TEXT = 0xFF092719.toInt()
// Shared with the floating cash wheel so wallet and bonus-cash labels use the same clear green.
internal const val COLOR_FLOATING_GOLD = 0xFF00F035.toInt()
// Crown Road uses the ordinary dark card family with only a restrained warm-yellow bias.
internal const val COLOR_CROWN_CURRENT_SURFACE = 0xFF25231C.toInt()
internal const val COLOR_CROWN_CASH = 0xFFD6A34A.toInt()
// Today in the check-in strip uses the midpoint between the Reward Centre's green and yellow-green action gradient.
internal const val COLOR_CHECKIN_TODAY_SURFACE = 0xFF6AF83A.toInt()
// Home drama metadata is deliberately quiet so posters, rather than coloured labels, lead the page.
internal const val COLOR_HOME_EPISODE_GOLD = 0xFF31363B.toInt()
internal const val COLOR_AQUA = 0xFF56CEC1.toInt()
internal const val COLOR_MIST_BLUE = 0xFF10151B.toInt()
internal const val COLOR_WARM_PEACH = 0xFF211815.toInt()
internal const val COLOR_TEXT_PRIMARY = 0xFFF6F6F8.toInt()
internal const val COLOR_TEXT_MUTED = 0xFFA8A8B2.toInt()
