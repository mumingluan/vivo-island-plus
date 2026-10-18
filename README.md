# vivo Island Plus

把 Android 持续活动、进度通知、CallStyle 通话和媒体会话接入 vivo 原子岛。

媒体播放使用 vivo 原有的随身听展示。持续活动与通话复用系统通知和原子岛卡片，保留来源应用的点击动作。通话按钮在系统控件基础上调整排布与样式；模块本身不会发起通话、打开摄像头或开始屏幕共享。

## 使用

安装 APK，在 LSPosed 中启用，作用域选择：

- `com.android.systemui`
- `com.vivo.systemuiplugin`
- `com.vivo.musicwidgetmix`

宿主重新启动后加载。来源应用需要先发出支持的通知或有效媒体会话；应用没有提供的动作不会凭空出现。结束活动或移除来源通知后，对应卡片应随之结束。

## 构建

Windows、Python 3、JDK 21、Android SDK platform 36、build-tools 37.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME`，运行 `python build.py`。

Xposed API 82 只用于编译。APK 默认使用本机 Android debug keystore 签名，仓库不提供私钥。`tools/` 中包含纯 JVM 策略与识别器测试，通知验证入口受系统 DUMP 权限限制。

## 兼容性

主要实机环境为 iQOO 15 / OriginOS 6。21.8、24.7、30.14、17.0.0.31 的系统应用样本做过离线对照，不能视作四个版本都完成实机测试。私有接口缺失或匹配不唯一时保留系统行为。

## 许可证

原有代码采用 GPL-3.0-only，Copyright (C) 2026 Axuan。第三方代码遵循各自许可证，见 [THIRD_PARTY.md](THIRD_PARTY.md)。

原子岛进程在应用初始化就绪后识别渲染接口，避免进程冷启动时只有小岛、无法展开卡片。

版本 `0.3.55`，包名 / 模块 ID：`com.axuan.vivoislandplus`。
