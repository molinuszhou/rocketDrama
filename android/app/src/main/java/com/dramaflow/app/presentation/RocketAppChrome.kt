package com.dramaflow.app.presentation

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dramaflow.app.R
import com.dramaflow.app.presentation.DramaAppActivity.MainTab

/** Fixed 64dp toolbar; root already fits system bars, so no second status inset is added. */
internal fun DramaAppActivity.createRocketHomeHeader(): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(16), dp(8), dp(8), 0)
    setBackgroundColor(COLOR_BACKGROUND)
    addView(ImageView(this@createRocketHomeHeader).apply {
        setImageResource(R.drawable.ic_rocket_brand)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = t("brand_name")
    }, LinearLayout.LayoutParams(dp(32), dp(32)))
    addView(TextView(this@createRocketHomeHeader).apply {
        text = t("brand_name")
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_PRIMARY)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, TypedValue.COMPLEX_UNIT_SP)
    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8); marginEnd = dp(4) })
    fun action(icon: Int, label: String, clicked: () -> Unit) = ImageView(this@createRocketHomeHeader).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(COLOR_TEXT_PRIMARY)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        contentDescription = label
        isClickable = true
        isFocusable = true
        setOnClickListener { clicked() }
    }
    addView(action(R.drawable.ic_search, t("search")) { showSearchPage() }, LinearLayout.LayoutParams(dp(48), dp(48)))
    addView(action(R.drawable.ic_nav_profile, t("profile")) {
        recordUiAction("NAVIGATION_TAB_SELECTED", mapOf("from_tab" to activeTab.name, "to_tab" to MainTab.PROFILE.name))
        activeTab = MainTab.PROFILE
        renderActiveTab()
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
}
