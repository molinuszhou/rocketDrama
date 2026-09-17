# Pangle 官方测试广告位核验

**核验日期：** 2026-09-17
**官方来源：** [Pangle — How to Test Pangle Ads with Ad ID](https://www.pangleglobal.com/integration/How-to-Test-Pangle-Ads-with-Ad-ID)

Pangle 官方建议在开发和测试阶段使用演示广告或测试设备，以避免因直接使用生产广告位进行测试而触发违规处罚机制。该页面说明，演示广告位可以在不创建开发者自有广告数据的情况下，稳定预览多种广告形式，且不受地域限制。

| 项目 | 官方测试参数 |
| --- | --- |
| Test App ID | `8025677` |
| Rewarded Server Bidding（竖屏） | `980088190` |
| Rewarded Waterfall（竖屏） | `980088192` |
| Interstitial Server Bidding（竖屏） | `980088186` |
| Interstitial Waterfall（竖屏） | `980088188` |
| App Open Server Bidding（竖屏） | `890008769` |
| App Open Waterfall（竖屏） | `890000078` |

> Pangle 明确说明这些测试广告仅支持在 SDK 测试状态下调用，展示和检测信息只用于开发校验，不产生线上影响；上线前必须移除或屏蔽相关测试调用。

本项目当前广告调用链为 **TopOn 聚合 SDK**，而不是 Pangle 原生 SDK 直连。因而不能把上表的 Pangle App ID 或 Slot ID 直接替换为 `TOPON_APP_ID`、`TOPON_APP_KEY` 或 TopOn Placement ID。应通过 `TOPON_DEMO_MODE=true` 选择 TopOn 官方 Demo 配置，或在 TopOn 后台将 Pangle 的测试源配置为聚合源。这样不会改变本地短剧、奖励服务或钱包测试链路，但广告素材、填充率、回调和计费均属于测试环境，不可用于正式发布。
