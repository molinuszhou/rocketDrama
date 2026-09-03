package com.dramaflow.app.ads

import android.content.Context
import kotlin.math.ceil

/**
 * 针对激励广告的客户端保护层。它不替代服务端风控，而是在 SDK 调用前防止误触、
 * 并发请求与异常高频展示，降低用户体验和渠道合规风险。
 */
class RewardedAdPolicy(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    sealed interface Decision {
        data object Allowed : Decision
        data class Blocked(val message: String) : Decision
    }

    fun canStart(now: Long = System.currentTimeMillis()): Decision {
        val lastShownAt = preferences.getLong(KEY_LAST_SHOWN_AT, 0L)
        if (lastShownAt == 0L) return Decision.Allowed

        val elapsed = now - lastShownAt
        if (elapsed < MIN_INTERVAL_MS) {
            val seconds = ceil((MIN_INTERVAL_MS - elapsed) / 1000.0).toInt().coerceAtLeast(1)
            return Decision.Blocked("操作过于频繁，请 $seconds 秒后再试")
        }
        return Decision.Allowed
    }

    fun recordShown(now: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(KEY_LAST_SHOWN_AT, now).apply()
    }

    fun resetForTest() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES = "dramaflow.rewarded_ad_policy"
        const val KEY_LAST_SHOWN_AT = "last_shown_at"
        const val MIN_INTERVAL_MS = 30_000L
    }
}
