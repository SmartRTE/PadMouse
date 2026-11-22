# 贡献指南

## 环境

JDK 17 + Android SDK 35，构建命令见 [README](README.md)。

## 提交前

```bash
./gradlew testDebugUnitTest assembleDebug
```

- 纯计算逻辑（死区归一化、响应曲线、滚动位移/时长、延迟统计等）请放在 `core/` 并补单测
- 代码风格：Kotlin 官方风格；注释用中文，解释**为什么**这么做，而不是复述做了什么
- 一个 PR 只做一件事；描述里写清动机与验证方式（设备型号 / 手柄型号 / Android 版本）

## 报告问题

请附上：

1. 设备型号 + Android 版本 + 手柄型号 + 连接方式（蓝牙 / USB）
2. 复现步骤（期望看到什么 / 实际看到什么）
3. App 内「调试」→「导出诊断文件」得到的 `padprobe.txt`（只含日志与设置，不含个人信息）

## 安全问题

若发现可能被第三方利用的问题（例如导出的组件、权限绕过、注入被滥用），请**不要**公开提 issue，
用 GitHub 的 Security Advisory 私下报告。
