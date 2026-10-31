> 本页是0.5.0整改候选的历史测试记录，不是0.6.0本轮执行结果。

# 0.5.0 测试与证据口径

当前候选：0.5.0-task-organize，versionCode 6。历史181项、Phase 2F原新增30项与本轮排序核查8项须在同一次完整Gradle测试中分别对照。
本轮结果在实际执行结束后写入本页；不以历史通过数冒充新执行。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain --rerun-tasks
```

机器环境和可选本地 Robolectric JAR 属性见 [构建说明](build-guide.md)。
逐个核对原测试文件哈希以及 JUnit classname/name，报告通过、失败、错误、跳过，并检查XML时间属于本轮。
排序专项使用生产DAO、Repository和Native SQLite，检查真实UPDATE行数、零/负数/边界、同值重编号、其他日期与数据保留、磁盘重开以及完成撤销。
既有旧库测试使用冻结v1—v4 schema构造带任务、规则、完成、例外的数据文件，执行生产Room打开/迁移/重开，不是空数据库创建测试。

## 历史结果与边界

- 0.4.0为181通过、Lint0错误18警告，见 [历史基线](phase2e-baseline-20261029.md)。
- 首次0.5.0开发记录211通过、Lint17警告；此前独立审计211通过、Lint18警告。WorkManager提示在0.4.0已存在，具体显示差异触发原因未证实。
- 本轮不关闭规则、不建立Lint baseline、不升级依赖来追求17条；以本轮原始XML为准。
- 主机Compose/Glance/Room测试不构成真机、真实启动器或澎湃OS兼容性证明。
- 不运行手机、ADB或模拟器；IME、设备旋转/进程回收、真实桌面跨天与性能仍未实测。
- 离线lifecycle-livedata:2.8.7缓存解析问题尚未解决；联网验证与离线结果分开记录。

## 本轮实际执行结果

开始时间（Asia/Hong_Kong）：2026-10-29T17:57:36.747128+08:00。完整Gradle组合退出码0，测试219通过：原181、Phase2F原新增30、本轮新增8；失败0、错误0、跳过0，共22类。

Lint实测0错误、18警告，组成：{"UnusedAttribute": 2, "GradleDependency": 14, "Overdraw": 2}。与0.4.0按id/severity/message对比新增0、消失0。

本轮Debug构建成功；交付APK明确复用原已验证文件，包哈希保持不变。完整原始日志、退出码、JUnit和Lint XML/HTML/TXT在候选交付目录保存；旧报告不覆盖。
