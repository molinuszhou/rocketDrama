# Pangle `40034` 激励广告故障结论

## 官方结论

Pangle 官方错误码文档将 `40034` 定义为 **Incorrect Bidding Ad Placement**：竞价广告位不能用于非竞价环境，非竞价广告位也不能用于竞价环境。[1]

SDK FAQ 重申，竞价（bidding）与非竞价（non-bidding）广告位必须在各自对应环境使用，不能混用。[2]

## 当前根因

JoyeDrama 当前直接调用 Pangle Android SDK 的 `PAGRewardedAd.loadAd()`、`PAGInterstitialAd.loadAd()` 和 `PAGAppOpenAd.loadAd()`。这些是**非竞价直连请求**，而当前本地配置误使用了合作方参数表中标记为 **Server Bidding** 的竖版广告位，因此激励广告返回 `40034`。

## 直连 SDK 测试应使用的广告位类型

下表只记录广告位属性和选择规则，不在此文件写入实际广告位值。

| 广告形态 | 当前直连调用方式 | 应使用的参数表类型 |
|---|---|---|
| 激励广告 | `PAGRewardedAd.loadAd()` | `Rewarded` → `Waterfall` → `Vertical` |
| 插屏广告 | `PAGInterstitialAd.loadAd()` | `Interstitial` → `Waterfall` → `Vertical` |
| 开屏广告 | `PAGAppOpenAd.loadAd()` | `App Open` → `Waterfall` → `Vertical` |

如果未来切换到 TopOn、AdMob、Google Ad Manager 或合作方提供的聚合竞价适配器，则应保留 Server Bidding 广告位并由对应聚合 SDK 发起请求；不能继续用当前 Pangle 直连 API。

## 修复动作

1. 将本机构建配置中的开屏、激励、插屏广告位替换为参数表的 `Waterfall / Vertical` 三项。
2. 保留 Pangle App ID 不变。
3. 在应用内增加调试状态，明确提示当前广告请求是直连模式、广告是否配置、以及失败码，但不输出真实广告位 ID。
4. 重新打包，并在真实 Android 设备验证激励解锁、自然断点插屏与启动开屏。

## References

[1] [Pangle Error Code](https://www.pangleglobal.com/zh/integration/error-code)

[2] [Pangle SDK FAQ](https://www.pangleglobal.com/integration/SDK-FAQ)
