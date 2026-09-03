package com.dramaflow.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dramaflow.app.AppViewModel
import com.dramaflow.app.partner.PartnerDramaActivity
import com.dramaflow.app.data.AppRoute
import com.dramaflow.app.data.Drama
import com.dramaflow.app.data.DramaCategory
import com.dramaflow.app.data.TaskStatus
import com.dramaflow.app.data.WatchRecord
import com.dramaflow.app.ui.theme.Coral
import com.dramaflow.app.ui.theme.Mint
import com.dramaflow.app.ui.theme.TextSecondary

@Composable
fun HomeScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var category by rememberSaveable { mutableStateOf(DramaCategory.FEATURED) }
    val grouped = remember(viewModel.dramas) { viewModel.dramas.chunked(4) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("晚上好", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text("发现一部好短剧", style = MaterialTheme.typography.headlineMedium)
                }
                Surface(
                    modifier = Modifier.clickable { viewModel.navigate(AppRoute.CASH_WALLET) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AccountBalanceWallet, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp))
                        Text(formatCash(state.user.cashCents), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.navigate(AppRoute.SEARCH) },
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, tint = TextSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text("搜索剧名、题材或关键词", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    context.startActivity(Intent(context, PartnerDramaActivity::class.java))
                },
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dramaverse 短剧专区", style = MaterialTheme.typography.titleMedium)
                        Text("合作方授权内容 · 官方播放器", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                    Icon(Icons.Outlined.ArrowForward, contentDescription = "进入合作方短剧专区")
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DramaCategory.entries) { item ->
                    val selected = item == category
                    Surface(
                        modifier = Modifier.clickable { category = item },
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(item.label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (selected) MaterialTheme.colorScheme.onPrimary else TextSecondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item {
            val featured = viewModel.dramas.first()
            Surface(
                modifier = Modifier.fillMaxWidth().height(172.dp).clickable { viewModel.openDrama(featured.id) },
                color = featured.accent,
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    MetaChip("本周热播")
                    Column {
                        Text(featured.title, style = MaterialTheme.typography.headlineMedium)
                        Text("${featured.subtitle} · ${featured.episodes} 集已更新", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (state.watchHistory.isNotEmpty()) {
            item { SectionTitle("继续观看", "全部历史") { viewModel.navigate(AppRoute.HISTORY) } }
            item {
                val record = state.watchHistory.first()
                val drama = viewModel.findDrama(record.dramaId)
                if (drama != null) ContinueWatchingCard(drama, record) { viewModel.openDrama(drama.id) }
            }
        }
        item { SectionTitle("猜你喜欢", "换一换") {} }
        items(grouped.flatten()) { drama ->
            DramaListRow(drama = drama, onClick = { viewModel.openDrama(drama.id) })
        }
    }
}

@Composable
fun DiscoverScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val pagerState = rememberPagerState(pageCount = { viewModel.dramas.size })
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(10.dp),
        pageSpacing = 10.dp
    ) { index ->
        val drama = viewModel.dramas[index]
        DiscoverStory(drama = drama, onOpen = { viewModel.openDrama(drama.id) }, onWatch = { viewModel.recordWatch(drama.id, 1, 10) })
    }
}

@Composable
private fun DiscoverStory(drama: Drama, onOpen: () -> Unit, onWatch: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(drama.accent, Color(0xFF110D18))))
            .clickable { onWatch() }
            .padding(22.dp)
    ) {
        Row(modifier = Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = Color.Black.copy(alpha = 0.23f), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Star, null, modifier = Modifier.size(15.dp), tint = Color(0xFFFFD166))
                    Spacer(Modifier.width(4.dp))
                    Text("68%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(modifier = Modifier.align(Alignment.BottomStart)) {
            Text("${drama.episodes} 集短剧", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(5.dp))
            Text(drama.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(drama.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF21172D))) { Text("立即观看") }
            Spacer(Modifier.height(8.dp))
            Text("上滑切换下一部 · 演示内容", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun TaskScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("任务中心", style = MaterialTheme.typography.headlineMedium)
            Text("奖励、上限和阶段宝箱均按公示规则计算", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每日基础金币", style = MaterialTheme.typography.titleLarge)
                            Text("${state.baseCoinsEarnedToday}/${state.dailyBaseCoinLimit} 金币 · 每日重置", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                        Text("${state.dailyBaseCoinLimit - state.baseCoinsEarnedToday}", color = Mint, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(10.dp))
                    CompactProgress(state.baseCoinsEarnedToday.toFloat() / state.dailyBaseCoinLimit, color = Mint)
                    Spacer(Modifier.height(8.dp))
                    Text("剩余可领取 ${state.dailyBaseCoinLimit - state.baseCoinsEarnedToday} 基础金币；阶段宝箱奖励不计入该上限。", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("今日阶段进度", style = MaterialTheme.typography.titleLarge)
                    Text("每领取一次有效任务奖励获得 1 个阶段点。当前 ${state.rewardPoints}/10 点。", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    CompactProgress(state.rewardPoints / 10f, color = Coral)
                }
            }
        }
        item { SectionTitle("阶段宝箱", "奖励金额固定公开") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.milestones) { milestone ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.width(138.dp).padding(14.dp)) {
                            Text("${milestone.requiredPoints} 阶段点", style = MaterialTheme.typography.titleMedium)
                            Text("固定 +${milestone.rewardCoins} 金币", style = MaterialTheme.typography.bodyMedium, color = Mint)
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { viewModel.claimMilestone(milestone.id) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !milestone.claimed && state.rewardPoints >= milestone.requiredPoints
                            ) { Text(if (milestone.claimed) "已领取" else "领取") }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每日签到", style = MaterialTheme.typography.titleLarge)
                            Text("已连续签到 ${state.user.checkInStreak} 天 · 固定 +30 金币", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                        Text("+30", color = Mint, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = viewModel::signIn, modifier = Modifier.fillMaxWidth(), enabled = !state.user.signedInToday) {
                        Text(if (state.user.signedInToday) "今日已签到" else "立即签到")
                    }
                }
            }
        }
        item { SectionTitle("每日任务", "普通奖励或广告双倍金额均会提前显示") }
        items(state.tasks) { task ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(3.dp))
                        Text(task.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        CompactProgress(task.progress.toFloat() / task.target)
                        Spacer(Modifier.height(4.dp))
                        Text("${task.progress}/${task.target} · 固定 +${task.rewardCoins} 金币", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                    Spacer(Modifier.width(14.dp))
                    when (task.status) {
                        TaskStatus.CLAIMABLE -> Button(onClick = { viewModel.openTaskReward(task.id) }) { Text("领取") }
                        TaskStatus.CLAIMED -> Text("已领取", style = MaterialTheme.typography.labelLarge, color = Mint)
                        TaskStatus.IN_PROGRESS -> Text("+${task.rewardCoins}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(padding: PaddingValues, viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(54.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(18.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("D", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Drama 体验官", style = MaterialTheme.typography.titleLarge)
                    Text("Lv.${state.user.level} · 已观看 ${state.user.watchedEpisodes} 集", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.navigate(AppRoute.CASH_WALLET) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("资格余额（原型）", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(formatCash(state.user.cashCents), style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("查看提现资格", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        item { SectionTitle("我的内容") }
        item { ProfileMenuItem(Icons.Outlined.History, "观看历史", "继续上次未看完的剧") { viewModel.navigate(AppRoute.HISTORY) } }
        item { ProfileMenuItem(Icons.Outlined.FavoriteBorder, "我的收藏", "收藏喜欢的短剧") { viewModel.navigate(AppRoute.FAVORITES) } }
        item { ProfileMenuItem(Icons.Outlined.AccountBalanceWallet, "金币与阶段奖励", "${state.user.coins} 金币 · 查看公开资格条件") { viewModel.navigate(AppRoute.COIN_WALLET) } }
        item { ProfileMenuItem(Icons.Outlined.Settings, "设置", "协议、隐私与版本信息") { viewModel.navigate(AppRoute.SETTINGS) } }
    }
}

@Composable
private fun ContinueWatchingCard(drama: Drama, record: WatchRecord, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DramaPoster(drama, Modifier.size(width = 68.dp, height = 94.dp), showPlay = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(drama.title, style = MaterialTheme.typography.titleMedium)
                Text("看到第 ${record.episode} 集", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(10.dp))
                CompactProgress(record.progressPercent / 100f)
            }
        }
    }
}

@Composable
private fun DramaListRow(drama: Drama, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        DramaPoster(drama, Modifier.size(width = 86.dp, height = 122.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(drama.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(drama.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(drama.description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text("${drama.episodes} 集 · ${drama.tags.joinToString(" · ")}", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
    }
}

@Composable
private fun ProfileMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Icon(Icons.Outlined.ArrowForward, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}
