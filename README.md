# DailyFlow

DailyFlow 是完全本地运行的 Android 每日清单应用，使用 Kotlin、Jetpack Compose / Material 3、Room 和 Jetpack Glance。包名 `com.dailyflow.app`，无需登录、服务器或云推送，应用不声明 `android.permission.INTERNET`。

当前稳定版本为 **0.6.0-reminders**，versionCode 7、Room v5。Phase 2G 于 2026-11-03 通过第二轮独立审计，结论 A；范围为 Windows 主机自动化和本地交付验证。

## 功能

- 每日任务新增、编辑、删除、完成与撤销，支持标题、备注和草稿恢复。
- 每日/每周重复规则、规则版本、单日例外、日历与历史记录。
- 高/普通/低优先级、手动顺序、排序、完成及优先级筛选、标题和备注搜索。
- Glance 桌面小组件：查看当天任务、完成/撤销、刷新和打开详情。
- 本地提醒与系统通知：普通任务、重复系列和单日覆盖；通知点击定位对应任务和日期。

任务、历史、完成记录和提醒配置保存于本地 Room 数据库。通知与精确闹钟授权分开申请，提醒默认关闭；精确能力不可用时采用可能延迟的非精确调度。

## 版本与追溯

| 版本 | 状态 |
| --- | --- |
| 0.5.0-task-organize / code 6 / Room v4 | Phase 2F 已通过第二轮独立审计，219 项主机测试通过 |
| 0.6.0 原始开发候选 | 首次独立审计为 B；原 285 项通过，但额外 10 项中有 6 个行为断言失败 |
| 0.6.0 audit-fix1 / code 7 / Room v5 | 修复三个问题，第二轮独立审计为 A；本次稳定归档来源 |

`main` 与 `v0.6.0` 对应最终已验收源码。原始候选提交保留在祖先历史中，未将其冒充为稳定版。312 项交付测试全部通过；另有 22 项独立审计专用测试全部通过，后者没有加入正式源码测试套件。Lint 为 0 错误、18 条既有警告，独立重建 APK 与整改 APK 完全一致。

源码来源、哈希、整改过程和边界见 [0.6.0 验证说明](docs/verification-v0.6.0.md)；功能变化见 [CHANGELOG](CHANGELOG.md)。这些是已核验的历史审计证据，本次 Git 归档没有重新运行整套 Android 测试。

## Windows 构建

保持现有工具链：Gradle Wrapper 8.9、Android Gradle Plugin 8.7.3、Kotlin/Compose 插件 2.0.21、Room 2.6.1、SDK 35、JDK 21.0.12.1，Java/Kotlin 字节码目标为 17。

配置本机 `JAVA_HOME` 和 `ANDROID_HOME`，或在不提交的 `local.properties` 中指定 SDK，然后运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --rerun-tasks
```

已有本地 Robolectric JAR 时可传 `-ProbolectricDependencyDir=<本机目录>`。这不代表 Gradle 整体离线可用；`lifecycle-livedata:2.8.7` 的离线解析缺口在第二轮审计中仍存在。使用现有官方依赖仓库，不更换未经审查的镜像。

Debug 签名密钥仅本地保存。新环境自动生成的证书可能不同，不能据“构建成功”推断可覆盖历史安装包。

## 验证边界

Windows/JUnit、Native SQLite、Robolectric 与静态审查已经执行；未使用手机、ADB 或模拟器。红米 K80 Pro、澎湃OS、真实桌面、Doze、物理重启、厂商后台和通知时效均未实测。

Room 与 NotificationManager 不能组成跨系统原子事务。CLAIMED 至发送前中断可能漏一次，未知状态保留待核实，不盲目重发；不承诺绝对 exactly-once。系统强制停止和厂商限制无法绕过。

## 文档与归档

- [架构](docs/architecture.md)、[数据库兼容性](docs/database-compatibility.md)
- [提醒使用](docs/phase2g-usage.md)、[实现边界](docs/phase2g-implementation.md)
- [历史整改记录](docs/phase2g-remediation.md)、[版本管理规则](docs/version-management.md)

仓库按用户本次明确选择保持 Public，只保存已审查的源码及精简文档。本地 APK、源码 ZIP、完整审计报告和证据长期保留；上传不授权清理历史文件，不自动进入 Phase 2H。

DailyFlow 为独立开发应用，早期参考过第三方功能与结构；不是第三方应用的官方版本或完整复刻。仓库不含第三方原始 APK、反编译/REA 输出或专有材料，本次不新增开源许可证。
