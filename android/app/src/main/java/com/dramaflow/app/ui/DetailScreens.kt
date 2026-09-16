package com.dramaflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dramaflow.app.AppViewModel
import com.dramaflow.app.ads.PangleConsent
import com.dramaflow.app.data.Drama
import com.dramaflow.app.data.TaskStatus
import com.dramaflow.app.data.WatchRecord
import com.dramaflow.app.ui.theme.Coral
import com.dramaflow.app.ui.theme.Mint
import com.dramaflow.app.ui.theme.TextSecondary

@Composable
fun SearchScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val results = remember(state.searchQuery) { viewModel.filteredDramas(state.searchQuery) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenTopBar("搜索短剧", viewModel::navigateBack)
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("输入剧名、题材或关键词") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { Text("搜索", modifier = Modifier.clickable { viewModel.submitSearch() }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.submitSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(16.dp)
            )
        }
        if (state.searchQuery.isBlank()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", style = MaterialTheme.typography.titleLarge)
                    if (state.searchHistory.isNotEmpty()) {
                        Text("清空", style = MaterialTheme.typography.labelLarge, color = TextSecondary, modifier = Modifier.clickable { viewModel.clearSearchHistory() })
                    }
                }
            }
            if (state.searchHistory.isEmpty()) {
                item { Text("暂无搜索记录", style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
            } else {
                items(state.searchHistory) { query ->
                    Surface(modifier = Modifier.fillMaxWidth().clickable { viewModel.useSearchHistory(query) }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Search, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(query, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            item { SectionTitle("热门推荐") }
            items(viewModel.dramas.take(4)) { drama -> DramaSearchRow(drama) { viewModel.openDrama(drama.id) } }
        } else {
            item { Text("搜索结果 · ${results.size} 部", style = MaterialTheme.typography.titleLarge) }
            if (results.isEmpty()) {
                item { EmptyState("未找到相关短剧", "试试更短的关键词，或看看热门推荐", "回到热门", { viewModel.updateQuery("") }) }
            } else {
                items(results) { drama -> DramaSearchRow(drama) { viewModel.openDrama(drama.id) } }
            }
        }
    }
}

@Composable
fun PlayerScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drama = viewModel.selectedDrama() ?: return
    val record = state.watchHistory.firstOrNull { it.dramaId == drama.id }
    val currentEpisode = record?.episode ?: 1
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
                    .background(Brush.verticalGradient(listOf(drama.accent.copy(alpha = .9f), Color(0xFFFFC98B))))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                ScreenTopBar("", viewModel::navigateBack, trailing = { IconButton(onClick = { viewModel.toggleFavorite(drama.id) }) { Icon(if (drama.id in state.favorites) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "收藏", tint = Color(0xFF2D211B)) } })
                Spacer(Modifier.weight(1f))
                Surface(color = Color.White.copy(alpha = 0.82f), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("第 $currentEpisode 集 · 正在播放", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(12.dp))
                CompactProgress((record?.progressPercent ?: 0) / 100f, color = Color.White)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(drama.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(5.dp))
                Text(drama.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip("${drama.episodes} 集")
                    drama.tags.forEach { MetaChip(it) }
                }
                Spacer(Modifier.height(18.dp))
                Button(onClick = viewModel::completeCurrentEpisode, modifier = Modifier.fillMaxWidth()) { Text("完成本集并播放下一集") }
                Spacer(Modifier.height(24.dp))
                SectionTitle("选集", "已看第 $currentEpisode 集")
                Spacer(Modifier.height(12.dp))
                EpisodeGrid(drama, currentEpisode, state.unlockedUntil[drama.id] ?: drama.lockedFrom - 1, viewModel)
            }
        }
    }
}

@Composable
private fun EpisodeGrid(drama: Drama, currentEpisode: Int, unlockedUntil: Int, viewModel: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        drama.episodes.coerceAtMost(28).let { max ->
            (1..max).toList().chunked(7).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { episode ->
                        val locked = episode >= drama.lockedFrom && episode > unlockedUntil
                        Surface(
                            modifier = Modifier.weight(1f).height(40.dp).clickable { viewModel.selectEpisode(drama, episode) },
                            color = when {
                                episode == currentEpisode -> MaterialTheme.colorScheme.primary
                                locked -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                            shape = RoundedCornerShape(11.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                if (locked) Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(13.dp), tint = TextSecondary)
                                else Text(episode.toString(), style = MaterialTheme.typography.labelMedium, color = if (episode == currentEpisode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(padding: PaddingValues, title: String, favoriteOnly: Boolean, viewModel: AppViewModel) {
    val library = if (favoriteOnly) viewModel.favoriteDramas().map { it to null } else viewModel.historyDramas().map { it.first to it.second }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ScreenTopBar(title, viewModel::navigateBack) }
        if (library.isEmpty()) {
            item {
                EmptyState(
                    if (favoriteOnly) "还没有收藏" else "还没有观看记录",
                    if (favoriteOnly) "喜欢的剧可以点心形图标收藏" else "挑一部短剧开始观看吧",
                    "去发现页", { viewModel.navigateBack() }
                )
            }
        } else {
            items(library) { (drama, record) ->
                LibraryRow(drama, record, favoriteOnly, onOpen = { viewModel.openDrama(drama.id) }, onToggleFavorite = { viewModel.toggleFavorite(drama.id) })
            }
        }
    }
}

@Composable
fun CashWalletScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val eligibility = state.withdrawalEligibility
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { ScreenTopBar("提现资格", viewModel::navigateBack) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E5)), elevation = CardDefaults.cardElevation(2.dp), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(22.dp)) {
                    Text("Available cash balance", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text(formatCash(state.user.cashCents), style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(13.dp))
                    Text(
                        if (eligibility.eligible) "已满足公开资格条件，可进入服务端申请流程"
                        else "当前未达标：${eligibility.nextAction}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (eligibility.eligible) Mint else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("公开资格条件", style = MaterialTheme.typography.titleMedium)
                    Text("最低 ${eligibility.minimumCoins} 金币 · 有效观看至少 3 集 · 完成今日签到 · 每日最多申请 1 次", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    if (eligibility.unmetRequirements.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(eligibility.unmetRequirements.joinToString("\n"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { Text("可申请档位", style = MaterialTheme.typography.titleLarge) }
        items(state.cashTiers) { tier ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tier.label, style = MaterialTheme.typography.titleLarge)
                        Text("需观看 ${tier.requiredEpisodes} 集 · Lv.${tier.requiredLevel}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                    if (tier.unlocked && eligibility.eligible) Button(onClick = { viewModel.requestDemoWithdrawal(tier.label) }) { Text("申请") }
                    else Text("暂不可申请", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                }
            }
        }
        item { WalletNotice() }
    }
}

@Composable
fun CoinWalletScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val eligibility = state.withdrawalEligibility
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { ScreenTopBar("金币兑换", viewModel::navigateBack) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(22.dp)) {
                    Text("我的金币", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text("${state.user.coins}", style = MaterialTheme.typography.headlineLarge)
                    Text("Minimum ${eligibility.minimumCoins} coins · exchange options are subject to server eligibility", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("当前资格", style = MaterialTheme.typography.titleMedium)
                    Text(eligibility.nextAction, style = MaterialTheme.typography.bodyMedium, color = if (eligibility.eligible) Mint else TextSecondary)
                    if (!eligibility.eligible) Text(eligibility.unmetRequirements.joinToString("\n"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Text("可兑换档位", style = MaterialTheme.typography.titleLarge) }
        items(listOf("500 金币", "1,000 金币", "2,000 金币")) { option ->
            Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.requestDemoWithdrawal(option) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (eligibility.eligible) "申请" else "查看条件", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { WalletNotice() }
    }
}

@Composable
fun SettingsScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val consentLabel = when (state.pangleConsent) {
        PangleConsent.GRANTED -> "已同意"
        PangleConsent.DECLINED -> "未同意"
        PangleConsent.UNKNOWN -> "尚未选择"
    }
    val adSubtitle = when {
        !state.pangleConfigured -> "尚未配置 App ID 与广告位；不会请求广告"
        state.pangleConsent == PangleConsent.GRANTED -> "已同意；开屏和内容自然断点插屏会按频控展示，激励广告仅在你主动解锁或领取双倍奖励时加载"
        else -> "$consentLabel；开屏、插屏、广告解锁和双倍奖励不可用"
    }
    val entries = listOf(
        "用户协议" to "查看服务条款",
        "隐私政策" to "了解数据使用方式",
        "联系我们" to "support@example.com",
        "版本信息" to "JoyeDrama 1.0.0"
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ScreenTopBar("设置", viewModel::navigateBack) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.requestPangleConsentChoice() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("广告隐私与 Pangle", style = MaterialTheme.typography.titleMedium)
                        Text(adSubtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                    Text("重新选择", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        items(entries) { (title, subtitle) ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DramaSearchRow(drama: Drama, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        DramaPoster(drama, Modifier.size(width = 72.dp, height = 104.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(drama.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(drama.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(drama.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryRow(drama: Drama, record: WatchRecord?, favoriteOnly: Boolean, onOpen: () -> Unit, onToggleFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DramaPoster(drama, Modifier.size(width = 72.dp, height = 104.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(drama.title, style = MaterialTheme.typography.titleMedium)
                Text(if (favoriteOnly) drama.subtitle else "看到第 ${record?.episode ?: 1} 集", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                if (record != null) {
                    Spacer(Modifier.height(10.dp))
                    CompactProgress(record.progressPercent / 100f)
                }
            }
            if (favoriteOnly) {
                IconButton(onClick = onToggleFavorite) { Icon(Icons.Outlined.Favorite, "取消收藏", tint = Coral) }
            }
        }
    }
}

@Composable
private fun WalletNotice() {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, tint = Mint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Text("本工程支持 Pangle 开屏、插屏与激励广告：开屏和插屏不发放奖励；激励广告仅在用户主动操作后才用于解锁或双倍奖励。真实提现、支付和可结算权益必须由服务端提供资格、渠道、风控、广告验签与审计结果。", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}
