# DailyFlow 0.5.0 架构与历史演进

## Phase 2D 数据与导航基础

应用任务界面继续由 DailyViewModel → UpdatingTaskRepository → RoomTaskRepository → Room/SQLite 驱动。新增 PlanningViewModel 与独立管理页面，复用同一 Repository，提供规则、例外和按月历史操作；没有重复实现另一个任务库。

Glance 继续使用同一应用 Repository 和当天查询，按钮仍由事务内日期/预期状态/stateRevision 校验保护。规则变更、例外恢复也通过 UpdatingTaskRepository 请求全部小组件刷新，并增加修订号使旧按钮失效。

schema v4 只增加 recurrence_versions。日查询单次 JOIN 选择日期之前最新的规则版本，采用对应生效日作间隔起点；初始 recurrence_rules 与 v1/v2/v3 schema 保留。已经完成或明确的单日例外可作为规则之外的独立实例保留，单次删除优先隐藏。

历史页按月查询今天之前最多 31 天，依靠 Room 表失效信号更新；不生成永久未来实例。当前是按既有定义派生的日清单，不是内容的不可变审计日志。

任务表单与规则表单的轻量版本化编码由 SavedStateHandle 保存，通过真实 Activity CreationExtras 创建，支持系统提供保存状态时的恢复。恢复不提交到任务数据库；损坏/超大状态安全舍弃。

主机构建仍使用英文路径镜像及逐文件 SHA-256 核验。测试结果与真实设备、启动器和系统进程回收场景分开记账；不使用 ADB。

## Phase 2E 展示层

FlowTheme、FlowDesign 与共享 PageFrame/ActionDock/NoticeCard/StatusPill 提供一致视觉规范。DailyFlowApp 按页面使用 SaveableStateHolder 保留清单及计划列表状态；PlanningViewModel 保存历史月份和详情返回路径，不新增任务存储。TaskDetails 用当前日期 Flow 识别失效任务。WidgetLayoutPolicy 只计算行数及尺寸，DailyWidgetContent 仍使用原 WidgetActions/CompleteWidgetAction 和同一 Repository。

数据库和 domain/data 层无变更，schema v4，无新迁移。业务回归仍由原有测试覆盖；新增主机 UI、导航和 Glance 布局测试与真实设备验证分开记录。可选 hostRenderProbe 测试源不进入 APK，需显式 Gradle 属性才能运行，不能算作模拟器证据。

## 历史：0.4.0 归档边界

2026-10-29 归档准备没有修改上述实现、数据库 schema 或依赖。该历史归档的版本身份为 `com.dailyflow.app`、versionCode 5、`0.4.0-ui-polish`。主机测试、设备验证和构建环境问题分别记录，详见 [基线](phase2e-baseline-20261029.md) ；当前测试方法与结果口径见 [测试说明](testing.md)。

源代码是版本管理对象，构建镜像、缓存、用户数据库、签名私钥和第三方静态研究材料不进入 Git。历史文档不会被伪造为历史提交；阶段沿革见 [版本历史](version-history.md)。

## Phase 2F 任务管理

当前应用版本为 `0.5.0-task-organize`、versionCode 6，包名仍为 `com.dailyflow.app`。
现有 `priority`、`priorityOverride`、`sortOrder`、`createdAt` 支撑新功能；Entity、schema v4、迁移链与0.4.0一致。
HIGH/NORMAL/LOW 为新界面选项，旧 MEDIUM 存储值保留并在展示/筛选时归入普通。
重复任务单日编辑沿用例外覆盖，系列编辑保留已有单日覆盖；详见 [数据库兼容性](database-compatibility.md)。

DailyViewModel 接收选定日期的 Repository Flow，再用 TaskOrganization 组合搜索、筛选及稳定排序，Compose 保留未完成/已完成分组。搜索与筛选不写任务数据。
手动顺序保存于全局任务定义，在每个完成状态分组内显示；切换日期会沿用同一任务的顺序，不是独立每日排序。
DAO 按主键只更新 sortOrder；不同值交换两行，同值时在事务内按全局稳定顺序重编号，可能有多行写入，但不改完成记录、重复规则、例外或修订号。
更新后仍经 UpdatingTaskRepository 请求小组件刷新，小组件不继承首页筛选/搜索条件。
完整操作与范围见 [使用说明](phase2f-usage.md)，主机验证与设备验证的边界见 [测试说明](testing.md)。

## 审计整改候选

本轮只补全文档、纠正验证记录并增加排序核查测试，未改 Android 业务代码、依赖或数据库配置。
三个历史链接目标连同其必要证据一起交付，避免只补直接目标而留下二级失效链接。
本候选仍待再次独立验收；不进入Phase 2G，不宣称澎湃OS或真实桌面验证通过。
