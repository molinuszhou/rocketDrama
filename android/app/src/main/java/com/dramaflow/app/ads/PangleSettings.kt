package com.dramaflow.app.ads

/**
 * 兼容旧 UI/业务调用的类型别名。
 *
 * 白包 v1.6 的实际广告实现已切换到 TopOn；保留别名可避免旧 Compose 演示页面在
 * 编译期引用失效，后续可逐步将历史 UI 中的命名迁移为通用广告术语。
 */
typealias PangleSettings = TopOnSettings
typealias PangleConsent = AdConsent
typealias PangleConsentStore = AdConsentStore
