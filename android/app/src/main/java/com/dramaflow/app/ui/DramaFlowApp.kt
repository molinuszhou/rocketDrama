package com.dramaflow.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dramaflow.app.AppViewModel
import com.dramaflow.app.ads.PangleConsent
import com.dramaflow.app.data.AppRoute
import com.dramaflow.app.data.MainTab
import com.dramaflow.app.ui.theme.Coral
import com.dramaflow.app.util.findActivity

@Composable
fun DramaFlowApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val activity = LocalContext.current.findActivity()

    ReportDrawnWhen { state.initialized }

    LaunchedEffect(state.snackMessage) {
        state.snackMessage?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeSnack()
        }
    }

    BackHandler(enabled = state.route != AppRoute.MAIN) { viewModel.navigateBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (state.route == AppRoute.MAIN) {
                BottomNavigation(selected = state.selectedTab, onSelect = viewModel::selectTab)
            }
        }
    ) { padding ->
        AppDestination(
            padding = padding,
            route = state.route,
            selectedTab = state.selectedTab,
            viewModel = viewModel
        )
    }

    if (state.pangleConsent == PangleConsent.UNKNOWN) {
        AlertDialog(
            onDismissRequest = { viewModel.recordPangleConsent(false) },
            title = { Text("广告隐私说明") },
            text = {
                Text(
                    "当你主动选择“广告解锁”或“看广告双倍奖励”时，应用会使用 Pangle 激励广告。" +
                        "同意后，Pangle SDK 仅在需要展示广告时初始化；拒绝不会影响普通内容浏览。"
                )
            },
            dismissButton = { Button(onClick = { viewModel.recordPangleConsent(false) }) { Text("暂不同意") } },
            confirmButton = { Button(onClick = { viewModel.recordPangleConsent(true) }) { Text("同意并继续") } }
        )
    }

    if (state.showUnlockDialog) {
        val drama = viewModel.selectedDrama()
        AlertDialog(
            onDismissRequest = viewModel::dismissUnlock,
            title = { Text("解锁第 ${drama?.lockedFrom ?: ""} 集") },
            text = {
                Text(
                    if (state.pangleConfigured) {
                        "完整观看 Pangle 激励广告后，本集会在广告奖励回调确认后解锁。"
                    } else {
                        "Pangle App ID 和激励广告位 ID 尚未配置，暂不能进行广告解锁。"
                    }
                )
            },
            dismissButton = { Button(onClick = viewModel::dismissUnlock) { Text("暂不解锁") } },
            confirmButton = {
                Button(
                    onClick = { viewModel.watchAdToUnlock(activity) },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    enabled = state.pangleConfigured && state.pangleConsent == PangleConsent.GRANTED
                ) { Text("看广告解锁") }
            }
        )
    }

    if (state.showCheckInDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCheckIn,
            title = { Text("签到成功") },
            text = { Text("已获得 30 coins。明天继续签到可提升连续天数。") },
            confirmButton = { Button(onClick = viewModel::dismissCheckIn) { Text("收下奖励") } }
        )
    }

    if (state.showRewardDialog) {
        val reward = state.pendingRewardTaskId?.let { id -> state.tasks.firstOrNull { it.id == id } }
        AlertDialog(
            onDismissRequest = viewModel::dismissReward,
            title = { Text("领取任务奖励") },
            text = {
                Text(
                    if (state.pangleConfigured) {
                        "${reward?.title ?: "任务"}可获得 ${reward?.rewardCoins ?: 0} 金币。可普通领取，或完整观看 Pangle 激励广告领取双倍奖励。"
                    } else {
                        "${reward?.title ?: "任务"}可普通领取。Pangle 广告位尚未配置，双倍奖励暂不可用。"
                    }
                )
            },
            dismissButton = { Button(onClick = { reward?.let { viewModel.claimTask(it.id) } }) { Text("普通领取") } },
            confirmButton = {
                Button(
                    onClick = { reward?.let { viewModel.watchAdForDoubleTaskReward(activity, it.id) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    enabled = reward != null && state.pangleConfigured && state.pangleConsent == PangleConsent.GRANTED
                ) { Text("看广告双倍") }
            }
        )
    }
}

@Composable
private fun AppDestination(
    padding: PaddingValues,
    route: AppRoute,
    selectedTab: MainTab,
    viewModel: AppViewModel
) {
    when (route) {
        AppRoute.MAIN -> when (selectedTab) {
            MainTab.HOME -> HomeScreen(padding, viewModel)
            MainTab.DISCOVER -> DiscoverScreen(padding, viewModel)
            MainTab.TASKS -> TaskScreen(padding, viewModel)
            MainTab.PROFILE -> ProfileScreen(padding, viewModel)
        }
        AppRoute.SEARCH -> SearchScreen(padding, viewModel)
        AppRoute.PLAYER -> PlayerScreen(padding, viewModel)
        AppRoute.HISTORY -> LibraryScreen(padding, "观看历史", false, viewModel)
        AppRoute.FAVORITES -> LibraryScreen(padding, "我的收藏", true, viewModel)
        AppRoute.CASH_WALLET -> CashWalletScreen(padding, viewModel)
        AppRoute.COIN_WALLET -> CoinWalletScreen(padding, viewModel)
        AppRoute.SETTINGS -> SettingsScreen(padding, viewModel)
    }
}

@Composable
private fun BottomNavigation(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp
    ) {
        val destinations = listOf(
            Triple(MainTab.HOME, "首页", Icons.Outlined.Home),
            Triple(MainTab.DISCOVER, "发现", Icons.Outlined.AutoAwesome),
            Triple(MainTab.TASKS, "任务", Icons.Outlined.TaskAlt),
            Triple(MainTab.PROFILE, "我的", Icons.Outlined.AccountCircle)
        )
        destinations.forEach { (tab, label, icon) ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { androidx.compose.material3.Icon(icon, contentDescription = label) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
