package com.dramaflow.app.data

import android.content.Context

class SearchHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("dramaflow.search", Context.MODE_PRIVATE)

    fun load(): List<String> = preferences.getString(KEY_HISTORY, "")
        .orEmpty()
        .split(SEPARATOR)
        .map(String::trim)
        .filter(String::isNotBlank)

    fun add(query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isBlank()) return load()
        val updated = (listOf(normalized) + load().filterNot { it.equals(normalized, true) }).take(MAX_ITEMS)
        save(updated)
        return updated
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(items: List<String>) {
        preferences.edit().putString(KEY_HISTORY, items.joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val SEPARATOR = "\u001F"
        const val MAX_ITEMS = 20
    }
}
