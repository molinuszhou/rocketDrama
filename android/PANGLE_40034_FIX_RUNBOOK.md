# Pangle `40034` 修复与后台核对清单

## 修复结果

Pangle 官方将 `40034` 定义为竞价广告位与请求环境不匹配。JoyeDrama 当前直接使用 Pangle Android SDK 的广告加载 API，属于非竞价直连模式，因此不能使用参数表中标记为 `Server Bidding` 的广告位。[1]

本机 Debug 构建已切换为参数表对应广告形态的 **Waterfall / Vertical** 测试广告位：激励广告、插屏广告和开屏广告均使用瀑布流直连广告位。Pangle App ID 保持不变。实际广告位值仅保留在未提交的 `local.properties`，不在本手册和源码包中出现。

## 真机回归步骤

| 验收项 | 操作 | 预期 |
|---|---|---|
| 激励广告 | 在播放器选择第 2 集或之后的锁集。 | 不再返回 `40034`；广告可展示或返回其他真实填充/网络错误码。完整观看后解锁。 |
| 插屏广告 | 自然播放一集至结束。 | Debug 构建最短 10 秒频控；收到自然断点后尝试加载插屏。 |
| 开屏广告 | 已同意广告后，重新启动或将应用从后台切回。 | 尝试开屏广告；无填充或频控时直接进入目录。 |
| 隐私拒绝 | 在“广告设置”关闭广告后选择锁集。 | 免费内容可播放；锁集提示广告解锁不可用；不请求广告。 |

## 若激励广告仍失败

修复后，`40034` 不应再由竞价/非竞价广告位混用导致。若出现其他错误码，请采集下列信息并提交 Pangle 后台支持：错误码、请求时间（含时区）、App ID、广告形态（Rewarded）、广告位名称或 ID、SDK 版本、应用包名、设备型号、Android 版本、国家/地区、网络类型，以及完整 Logcat 中 `JoyeDramaPlayer` 与 `PangleAdsManager` 的相关行。

不要发送 VOD License、短剧 securityKey、Firebase 文件、账号密码、服务端密钥或用户个人信息。

## 后台核对项

1. 确认三种 Waterfall 测试广告位均仍处于可用的测试状态，并归属当前 Pangle App ID。
2. 确认当前 Android 包名 `com.feather.joyelledrama` 与 Pangle 后台登记的应用信息一致；若平台要求 SHA-1，也应匹配 Debug/Release 对应签名。
3. 确认目标测试国家/地区受 Pangle 支持，且真实设备不处于模拟器/异常代理环境。
4. 如果切换到 TopOn、AdMob、Google Ad Manager 或其他聚合竞价，请改用该聚合方案要求的 Server Bidding 广告位和适配器；不得继续用 Pangle 直连 API 请求竞价广告位。
5. 发布前替换为正式广告位，并将激励权益最终写入服务端验证链路。

## References

[1] [Pangle Error Code](https://www.pangleglobal.com/zh/integration/error-code)

[2] [Pangle SDK FAQ](https://www.pangleglobal.com/integration/SDK-FAQ)
