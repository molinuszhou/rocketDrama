package com.dramaflow.app.presentation

/** Pure geometry, shared by the real calendar and lightweight JVM regression checks. */
internal object RocketUiLayoutPolicy {
    fun calendarCircleDp(fontScale: Float): Int = (32f * fontScale.coerceAtLeast(1f)).toInt().coerceAtMost(56)
    fun calendarCellDp(fontScale: Float): Int = maxOf(52, calendarCircleDp(fontScale) + 12)
    fun calendarNeedsScroll(contentWidthDp: Float, fontScale: Float): Boolean =
        fontScale > 1.2f || contentWidthDp / 7f < calendarCircleDp(fontScale) + 4f
}
