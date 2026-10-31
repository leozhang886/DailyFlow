# DailyFlow

DailyFlow 是独立开发、完全本地运行的 Android 每日清单应用，包名为 `com.dailyflow.app`。无需登录、服务器或第三方平台，Android Manifest 不包含 `android.permission.INTERNET`。

早期研究参考过第三方应用的功能与结构；DailyFlow 不是该应用的官方版本，也不声称完整复刻。仓库不得包含第三方原始 APK、反编译/REA 输出或专有资源。本次未新增开源许可证；仓库按用户2026-10-31明确授权设为Public。

## 版本与验收

| 分支 | 版本 | 状态 |
| --- | --- | --- |
| `main` | `0.5.0-task-organize`，versionCode 6，Room v4 | Phase 2F 第二轮独立审计已通过；历史主机测试219项通过 |
| `develop/0.6.0-reminders` | `0.6.0-reminders`，versionCode 7，Room v5 | 开发侧完成；独立审计结论B，等待整改及再次独立验收，不得合并稳定分支 |

本仓库只归档经核验的源码。0.5.0是稳定基线；0.6.0保持在开发分支，待整改和再次独立验收。

0.6.0 独立审计实际复跑285项测试全部通过（原219＋新增66，失败、错误、跳过均为0），但审计补充10个用例有6个行为断言失败；不能据此宣布审计通过。独立 Lint 为0错误、18警告，开发侧历史报告为16警告，差异是两条历史 Compose BOM 更新提示。

## 核心功能

- 任务新增、编辑、详情、删除确认、完成与撤销。
- 每日/每周重复规则、单日例外、规则版本、日历与历史记录、草稿恢复。
- 高/普通/低优先级，手动上移/下移、优先级及创建时间排序；手动顺序为全局任务顺序，在完成状态分组内显示。
- 当前选定日期可见任务的标题/备注搜索、英文大小写忽略、首尾空格处理、完成状态及优先级组合筛选。
- Glance 桌面小组件：当天任务、完成/撤销、刷新与标题跳转详情。
- 0.6.0开发分支增加本地提醒和系统通知，普通任务提醒、系列提醒和单次覆盖；默认关闭，通知与精确闹钟授权分开申请。

## 架构

Kotlin、Jetpack Compose / Material 3、Room、Jetpack Glance。提醒配置以 Room 为事实来源，通过独立调度层连接 AlarmManager 和 NotificationManager；不用云推送或精确定时的常驻服务。v4→v5新增提醒表并保留历史迁移链，禁止 destructive migration。

## Windows 本地构建

保持原有工具链：Gradle Wrapper 8.9、Android Gradle Plugin 8.7.3、Kotlin/Compose插件2.0.21、Room2.6.1、Android SDK35、JDK21.0.12.1，字节码目标17。

设置本机 `JAVA_HOME` 和 `ANDROID_HOME`，或在不提交的 `local.properties` 中指定 SDK。使用项目原有官方依赖仓库：Google Maven、Maven Central，以及插件解析所需 Gradle Plugin Portal。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

已有 Robolectric 本地 JAR 目录时，可传 `-ProbolectricDependencyDir=<本机目录>`；这不等于 Gradle 整体离线模式。`lifecycle-livedata:2.8.7` 的离线解析缺口仍未解决，不要手工篡改缓存或切换未审查镜像。

Debug 签名密钥仅在本机安全保存，不上传仓库。新环境自动生成的 Debug 证书可能不同，不能未经核验便声称与历史 APK 签名一致。

## 已知问题与验证范围

0.6.0尚未通过独立验收：常规对账可能取消已到期但未送达的提醒；通知已发送但数据库确认失败时可能出现重发或删除后残留；超过重试窗口仍可能提示“等待重试”。需整改后复验，不创建 `v0.6.0` 稳定标签或正式 Release。

提醒采用本地日期/时间规则，精确闹钟未授权时可能延迟；Android强制停止和厂商后台限制不能绕过。数据库 CLAIMED 与系统通知之间存在非原子崩溃窗口，不能承诺恰好一次投递。

现有证据来自Windows主机测试、native SQLite、Robolectric和静态检查。未使用手机、ADB或模拟器；尚未完成Redmi K80 Pro澎湃OS、真实启动器、设备休眠唤醒、物理重启和通知时效验证。

## 版本管理

独立开发分支 → 开发侧测试 → 独立审计 → 审计通过 → 合并main → 创建稳定标签。旧版本标签不移动，不伪造早期提交；本地APK、源码ZIP、报告及SHA-256长期保留。稳定标签仅可指向通过验收的版本；v0.5.0以实际标签核验结果为准，不为0.6.0创建稳定标签。

每个源码提交应记录对应交付ZIP的SHA-256，并在发布说明中记录准确commit SHA。上传不代替本地备份，也不授权清理历史交付物。Phase 2H未开始。

## 交付源码来源

- 0.5.0验收源码ZIP SHA-256：`1e7535514f861dbbeb02f4be0e279c17630039c13a994f41503b9010c843439d`。
- 0.6.0候选源码ZIP SHA-256：`63f4c7349fb4687999e7fbc042f11901f333f5dc8c5b5d65b547e3e09d2c5b00`。

仓库README、.gitignore及版本管理规则为归档所补充的文档，不属于Android业务代码修复；历史原始ZIP及APK在本地保留。
