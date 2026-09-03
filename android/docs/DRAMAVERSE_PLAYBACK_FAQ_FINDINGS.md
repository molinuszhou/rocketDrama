# Dramaverse 播放失败排查结论

来源：合作方《短剧 SDK FAQ》，2026-08-20 读取。

## 核心结论

当短剧目录能够加载但视频播放失败时，合作方首先要求核对初始化参数的严格匹配关系：**Android 包名、短剧 App ID、securityKey、VOD App ID、VOD 证书文件**。合作方明确说明，证书问题是无法播放的主要原因。

证书必须在合作方后台填写 Android Package Name，且该包名必须与 APK 实际 applicationId 相同；测试或正式证书均不得过期，Android/iOS 证书不得混用。

合作方还提供 `PSSDK.checkPlayStatus(ServiceCheckResultListener)` 用于分别返回 `SUPPORT`、`NOT_SUPPORT` 或 `NETWORK_ERROR`。目录加载成功不等价于当前网络可播放，播放器在进入详情页前应先调用该接口并给出明确提示。

播放器 `onPlayFailed(PSSDK.ErrorInfo)` 会返回具体失败码；其中 `ERROR_CODE_CURRENT_COUNTRY_NOT_SUPPORT` 需要被单独处理，其他 VOD 播放错误需按 BytePlus VOD error-code 文档诊断。

## 本工程的修复方向

1. 初始化前显示只含非敏感标识的配置自检结果，并验证证书 assets 文件存在。
2. 在进入合作方播放器前调用 `checkPlayStatus`，区分地区不支持、网络检测失败和支持状态。
3. 扩展 `onPlayFailed` 的可视化错误信息与 Logcat 诊断字段，不打印 securityKey 或 License 内容。
4. 统一 UI 内容流时，播放前检查必须留在同一详情入口内，不能因来源隐藏而取消。

## 仍需合作方确认

若 `checkPlayStatus` 返回 `SUPPORT` 但依然播放失败，须由合作方后台核对当前 Debug APK 的包名 `com.feather.joyelledrama` 是否已绑定到所提供 VOD License、短剧 App ID、securityKey 和 VOD App ID 的同一配置组，以及证书是否有效。
