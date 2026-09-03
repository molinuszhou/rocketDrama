package com.dramaflow.app.data

import androidx.compose.ui.graphics.Color

/**
 * 首版使用内置演示数据，保证冷启动首屏可立即渲染。
 * 生产环境可将此接口替换为缓存优先的内容数据源，而不改变 UI 层。
 */
interface DramaRepository {
    fun allDramas(): List<Drama>
    fun tasks(): List<RewardTask>
    fun cashTiers(user: UserProfile): List<WalletTier>
}

class DemoDramaRepository : DramaRepository {
    private val dramas = listOf(
        Drama("d1", "月光下的契约", "豪门 · 复仇", "被迫签下契约的她，在月光下发现了真相。", 48, 7, Color(0xFF7C4DFF), "月", listOf("豪门", "逆袭")),
        Drama("d2", "她的第二次人生", "重生 · 甜宠", "重回命运转折点，她决定为自己而活。", 56, 9, Color(0xFFFF5C93), "她", listOf("重生", "甜宠")),
        Drama("d3", "暗夜心跳", "悬疑 · 都市", "一封匿名信，揭开三年前的秘密。", 42, 6, Color(0xFF00A6A6), "夜", listOf("悬疑", "都市")),
        Drama("d4", "春日来信", "治愈 · 爱情", "在陌生城市，他收到了来自过去的信。", 36, 5, Color(0xFFFFA552), "春", listOf("治愈", "爱情")),
        Drama("d5", "失控的心动", "先婚后爱", "一场误会，让两条平行线开始靠近。", 60, 10, Color(0xFFEA5E83), "心", listOf("先婚后爱", "热播")),
        Drama("d6", "猎场回响", "商战 · 成长", "重返猎场，他要夺回本该属于自己的一切。", 45, 8, Color(0xFF3E72E7), "猎", listOf("商战", "成长")),
        Drama("d7", "风起长安", "古风 · 权谋", "一纸诏书，牵动长安暗流。", 52, 8, Color(0xFFB77645), "长", listOf("古风", "权谋")),
        Drama("d8", "今天也想见你", "校园 · 青春", "毕业前的倒数日，他们终于说出心意。", 30, 5, Color(0xFF4CBDAA), "见", listOf("校园", "青春"))
    )

    override fun allDramas(): List<Drama> = dramas

    override fun tasks(): List<RewardTask> = listOf(
        RewardTask("checkin", "每日签到", "连续签到可获得更多奖励", 0, 1, 30, 5, TaskStatus.CLAIMABLE, true),
        RewardTask("episodes", "观看 3 集短剧", "播放超过 1 分钟才计入进度", 2, 3, 80, 8, TaskStatus.IN_PROGRESS),
        RewardTask("unlock", "解锁 1 集", "完成一次广告解锁", 1, 1, 50, 5, TaskStatus.CLAIMABLE, true),
        RewardTask("minutes", "累计观看 10 分钟", "继续观看即可完成", 7, 10, 60, 6, TaskStatus.IN_PROGRESS),
        RewardTask("notify", "开启通知提醒", "第一时间收到新剧通知", 0, 1, 40, 4, TaskStatus.IN_PROGRESS)
    )

    override fun cashTiers(user: UserProfile): List<WalletTier> = listOf(
        WalletTier("t1", "¥0.30", 30, 3, 1, user.watchedEpisodes >= 3 && user.level >= 1),
        WalletTier("t2", "¥1.00", 100, 10, 2, user.watchedEpisodes >= 10 && user.level >= 2),
        WalletTier("t3", "¥3.00", 300, 20, 3, user.watchedEpisodes >= 20 && user.level >= 3),
        WalletTier("t4", "¥10.00", 1000, 40, 5, user.watchedEpisodes >= 40 && user.level >= 5)
    )
}
