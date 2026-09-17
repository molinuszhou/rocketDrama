package com.dramaflow.app.presentation

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 应用内轻量对话框系统。内容、按钮与圆角遵循 JoyeDrama 的暖白/珊瑚视觉体系，
 * 避免在页面中混入平台默认的灰色 AlertDialog 外观。
 */
internal fun DramaAppActivity.showBrandedChoiceDialog(
    title: String,
    message: String,
    primaryLabel: String = t("done"),
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    cancelable: Boolean = true,
): AlertDialog {
    val dialog = AlertDialog.Builder(this).create()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(22), dp(22), dp(18))
        background = gradientDrawable(intArrayOf(0xFFFFF7F1.toInt(), Color.WHITE), 26)
    }
    card.addView(TextView(this).apply {
        text = title
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_PRIMARY)
    })
    if (message.isNotBlank()) {
        card.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(COLOR_TEXT_MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(20))
        })
    }
    val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    if (secondaryLabel != null) {
        actions.addView(brandedDialogButton(secondaryLabel, false) {
            dialog.dismiss()
            onSecondary?.invoke()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
    }
    actions.addView(brandedDialogButton(primaryLabel, true) {
        dialog.dismiss()
        onPrimary?.invoke()
    }, LinearLayout.LayoutParams(0, dp(48), 1f))
    card.addView(actions)
    dialog.setView(card)
    dialog.setCanceledOnTouchOutside(cancelable)
    dialog.setCancelable(cancelable)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    return dialog
}

internal fun DramaAppActivity.showBrandedOptionsDialog(
    title: String,
    items: List<String>,
    selectedIndex: Int = -1,
    onSelected: (Int) -> Unit,
): AlertDialog {
    val dialog = AlertDialog.Builder(this).create()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(22), dp(22), dp(16))
        background = gradientDrawable(intArrayOf(0xFFFFF7F1.toInt(), Color.WHITE), 26)
    }
    card.addView(TextView(this).apply {
        text = title
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_PRIMARY)
        setPadding(0, 0, 0, dp(10))
    })
    items.forEachIndexed { index, item ->
        card.addView(TextView(this).apply {
            text = item
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(if (index == selectedIndex) Color.WHITE else COLOR_TEXT_PRIMARY)
            background = roundedDrawable(if (index == selectedIndex) COLOR_CORAL else 0xFFFFF4EF.toInt(), 16)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                onSelected(index)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            if (index > 0) topMargin = dp(7)
        })
    }
    card.addView(brandedDialogButton(t("cancel"), false) { dialog.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
    dialog.setView(card)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    return dialog
}

internal fun DramaAppActivity.showBrandedInputDialog(
    title: String,
    message: String,
    input: EditText,
    primaryLabel: String,
    onPrimary: (String) -> Unit,
    secondaryLabel: String? = t("cancel"),
    onSecondary: (() -> Unit)? = null,
): AlertDialog {
    val dialog = AlertDialog.Builder(this).create()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(22), dp(22), dp(18))
        background = gradientDrawable(intArrayOf(0xFFFFF7F1.toInt(), Color.WHITE), 26)
    }
    card.addView(TextView(this).apply { text = title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
    if (message.isNotBlank()) card.addView(TextView(this).apply { text = message; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), 0, dp(13)) })
    input.background = roundedDrawable(0xFFF9F2EE.toInt(), 14)
    input.setPadding(dp(14), dp(12), dp(14), dp(12))
    card.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
    if (secondaryLabel != null) actions.addView(brandedDialogButton(secondaryLabel, false) { dialog.dismiss(); onSecondary?.invoke() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
    actions.addView(brandedDialogButton(primaryLabel, true) { onPrimary(input.text.toString().trim()) }, LinearLayout.LayoutParams(0, dp(48), 1f))
    card.addView(actions)
    dialog.setView(card)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    return dialog
}

private fun DramaAppActivity.brandedDialogButton(label: String, emphasized: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 14f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(if (emphasized) Color.WHITE else COLOR_TEXT_PRIMARY)
    background = roundedDrawable(if (emphasized) COLOR_CORAL else 0xFFFFEFE9.toInt(), 16)
    isClickable = true
    setOnClickListener { onClick() }
}
