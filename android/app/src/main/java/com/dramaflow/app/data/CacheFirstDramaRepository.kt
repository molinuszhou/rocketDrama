package com.dramaflow.app.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内容目录的缓存优先实现。
 *
 * 当前没有授权短剧服务时使用内置目录，并将其缓存为可离线恢复的首屏数据；
 * 后续接入 ShortDramaContentGateway 后，只需调用 replaceCatalog(remoteItems)，
 * UI 和现有的 DramaRepository 调用方无需改动。
 */
class CacheFirstDramaRepository(
    context: Context,
    private val fallback: DramaRepository = DemoDramaRepository()
) : DramaRepository {
    private val snapshotStore = DramaCatalogSnapshotStore(context)
    private var cachedCatalog: List<Drama> = snapshotStore.load().orEmpty()

    override fun allDramas(): List<Drama> {
        if (cachedCatalog.isNotEmpty()) return cachedCatalog
        return fallback.allDramas().also { snapshot ->
            cachedCatalog = snapshot
            snapshotStore.save(snapshot)
        }
    }

    override fun tasks(): List<RewardTask> = fallback.tasks()

    override fun cashTiers(user: UserProfile): List<WalletTier> = fallback.cashTiers(user)

    fun replaceCatalog(remoteCatalog: List<Drama>) {
        if (remoteCatalog.isEmpty()) return
        cachedCatalog = remoteCatalog
        snapshotStore.save(remoteCatalog)
    }

    fun clearCatalogForTest() {
        cachedCatalog = emptyList()
        snapshotStore.clear()
    }
}

private class DramaCatalogSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<Drama>? {
        val raw = preferences.getString(KEY_CATALOG, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        Drama(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            subtitle = item.getString("subtitle"),
                            description = item.getString("description"),
                            episodes = item.getInt("episodes"),
                            lockedFrom = item.getInt("lockedFrom"),
                            accent = Color(item.getInt("accent")),
                            coverSymbol = item.getString("coverSymbol"),
                            tags = item.getJSONArray("tags").toStringList()
                        )
                    )
                }
            }
        }.getOrNull()
    }

    fun save(catalog: List<Drama>) {
        val array = JSONArray()
        catalog.forEach { drama ->
            array.put(
                JSONObject()
                    .put("id", drama.id)
                    .put("title", drama.title)
                    .put("subtitle", drama.subtitle)
                    .put("description", drama.description)
                    .put("episodes", drama.episodes)
                    .put("lockedFrom", drama.lockedFrom)
                    .put("accent", drama.accent.toArgb())
                    .put("coverSymbol", drama.coverSymbol)
                    .put("tags", JSONArray(drama.tags))
            )
        }
        preferences.edit().putString(KEY_CATALOG, array.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_CATALOG).apply()
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }

    private companion object {
        const val PREFERENCES = "dramaflow.catalog"
        const val KEY_CATALOG = "catalog_snapshot"
    }
}
