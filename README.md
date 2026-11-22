# 手柄鼠标 PadMouse

[![CI](https://github.com/SmartRTE/PadMouse/actions/workflows/ci.yml/badge.svg)](https://github.com/SmartRTE/PadMouse/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

把 Android 手柄（Xbox / Switch / 通用 HID）当成鼠标来用：左摇杆移动光标、右摇杆滚动、按键映射成点击 / 返回 / 桌面 / 音量等，让没有触屏适配的 App 也能用手柄操作、让部分需要点击屏幕的 App 能够临时使用手柄操控、让躲在被窝里的我可以不伸出手也能刷手机（其实这才是主要用途）。

> 通过**无障碍服务**注入输入。App 不联网、不读取屏幕内容、不上传任何数据 —— 详见 [PRIVACY.md](PRIVACY.md)。

## 功能

- **光标**：左或右摇杆驱动自绘鼠标光标，带死区、响应曲线、速度平滑、精准模式（按住 RT）与加速模式（按住 L3）
- **滚动**：默认「丝滑」等速滚动（按目标速度反算每段手势，段间无缝）；可切回分段滑动做对比
- **十字键**：四向滑动手势；可与左摇杆互换，给没有摇杆的小手柄用
- **按键映射**：A/B/X/Y、肩键、扳机、Start/View/Xbox、十字键、L3/R3 可自由绑定到点击、长按、双击、返回、桌面、最近任务、通知栏、音量、触摸按住拖动等动作；同一个键可「短按 / 长按」两用
- **按 App 生效**：黑名单（进入即自动停用）、键盘优先（让出焦点方便打字）
- **输入法模式**：临时让出输入焦点打字，光标描边转红提示，输入法收起后自动恢复
- **状态页**：实时显示手柄物理状态（按键 / 摇杆 / 扳机 / 十字键 / 死区圆）、注入延迟 p50/p95、手势成功/失败/超时
- **配置**：全部参数可调，JSON 导入导出（带 schema 版本号），可整机迁移

## 环境要求

- JDK 17
- Android SDK Platform 35（`compileSdk = 35`，`minSdk = 26`）
- Gradle 用仓库自带的 wrapper（8.11.1），无需单独安装

## 构建

```bash
git clone https://github.com/SmartRTE/PadMouse.git
cd PadMouse

./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（需签名，见下）
./gradlew testDebugUnitTest      # 单元测试
```

发布签名通过项目根目录的 `keystore.properties`（已 gitignore）或环境变量提供：
`PADMOUSE_STORE_FILE` / `PADMOUSE_STORE_PASSWORD` / `PADMOUSE_KEY_ALIAS` / `PADMOUSE_KEY_PASSWORD`。
读不到这些配置时构建**不会失败**，只是产出未签名 APK，方便没有密钥的贡献者。

## 安装与首次使用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 App，按提示进入「设置 → 无障碍」开启 PadMouse 服务
2. 连上手柄，到「状态」页确认按键与摇杆能实时响应
3. 需要时到「应用」页把聊天 / 输入类 App 设为「键盘优先」

## 架构

```
capture/  无障碍服务、1x1 可获焦点窗口（读手柄事件）、轴读取与归一化
engine/   光标速度模型、按键动作分发、手势串行化、滚动驱动
overlay/  悬浮窗：自绘光标、焦点窗
core/     设置存储、配置导入导出、探针日志、纯数学（可单测）
model/    设置与绑定表的数据模型
ui/       Jetpack Compose 界面：状态 / 按键 / 参数 / 应用 / 调试
```

三个绕不开的系统约束，也决定了现在的设计：

1. 非 root 无法移动系统鼠标指针 → 光标是自绘的悬浮窗，点击由手势注入完成
2. `dispatchGesture` 同一时刻只允许一个手势 → 所有注入串行化，忙时只保留最后一个请求
3. 手柄轴事件只送到"持有输入焦点的窗口" → 需要一个 1×1 的焦点窗来收事件，且它会和输入法抢焦点


## 已知限制

- 部分游戏 / App 会拦截注入的触摸或按键（反作弊），这是系统层面限制
- 不同 ROM（尤其 MIUI / HyperOS）对无障碍与后台限制差异较大，可能需要额外权限放行
- 商店上架对无障碍权限审核较严，自行构建安装最省事

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 作者与致谢

- 作者：[SmartRTE](https://github.com/SmartRTE)
- 主要编程：DeepSeek
- 部分修改：MiniMax

## 许可证

[MIT](LICENSE)
