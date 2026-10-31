> 以下保留对应历史阶段说明；当前新增提醒和权限边界见 [0.6.0使用说明](phase2g-usage.md)。

# DailyFlow 桌面小组件（Phase 2C-W）

2026-10-27，Asia/Hong_Kong。完成按钮直接写入现有 Room，标题才打开 App；不需要网络、提醒权限或用户逐项验证。

## 使用方式

安装新版后，安卓桌面长按空白处，进入小组件列表，添加“DailyFlow 今日清单”。具体入口由桌面实现决定；本文仅说明可选使用方式，不是要求用户执行验收。小尺寸展示 3 条，大尺寸 5 条，可在桌面拖动边缘调整尺寸。标题点击进入任务详情；左侧圆圈完成，勾号撤销；刷新按钮重新读取今日任务。计数包含全部今日任务，超出的行通过“打开全部”查看。

## 实现路径

| 职责 | 文件 |
| --- | --- |
| Glance 布局与 Room Flow 观察 | app/src/main/java/com/dailyflow/app/widget/DailyTaskWidget.kt |
| 参数解码、完成回调、详情 Intent | widget/WidgetActions.kt |
| 日期策略与快照命令 | widget/WidgetController.kt |
| 多实例、Worker、日期广播与闹钟 | widget/WidgetUpdates.kt |
| App 写入后请求刷新 | data/UpdatingTaskRepository.kt |
| 事务校验与唯一完成记录 | data/RoomTaskRepository.kt |
| 注册及最小尺寸 | AndroidManifest.xml、res/xml/daily_task_widget_info.xml |

完成命令包含任务、日期、预期值、修订号和目标值。事务内检查日期仍为今天、实例仍有效、数据库快照未改变，成功才写目标状态并递增修订号。重复点击同一旧按钮最多写一次；过期按钮只刷新，并提示状态或日期已变化。重复任务仍按日期保存，完成今天不会完成明天。

App 与小组件的读取共用 DailyFlowApplication.repository / database。进程被系统回收后重新从 Room 加载，修订号同样持久化。没有独立小组件任务数据库或完成状态偏好。

## 后台限制与失败反馈

App 提交后排队请求所有实例刷新；小组件提交后直接调用 Glance 更新。系统最终发送 RemoteViews 的时机仍受调度影响，不能承诺固定毫秒延迟。数据库保存失败不会假装完成；UI 提示重试。刷新失败不会回滚已经成功保存的任务状态，后台刷新有重试。

日期广播、每 15 分钟工作、每 30 分钟 widget 更新和午夜非精确闹钟负责恢复展示。Doze、强制停止、设备关机及澎湃OS限制可能延迟刷新；旧日期按钮会拒绝写入。没有使用 ADB、精确闹钟、常驻前台服务或网络请求。

实际主机测试及设备未验证项请查看本轮 phase2cw-report.md；不能用组件注册成功或 RemoteViews 转换成功代替真实桌面兼容性验收。


## Phase 2D 接线补充

0.3.0 的日查询会解析规则生效版本，并保留已有完成或单日例外实例。规则变更与例外恢复也经过同一个 Repository 更新通知，递增持久修订号，拒绝之前画面的旧完成按钮；仍未单独保存小组件完成状态。原 0.2.1 的布局和主机真实点击回归继续保留。当前结果以 phase2d-report.md 为准。

## Phase 2E 自适应展示

WidgetLayoutPolicy 根据 LocalSize、Configuration.fontScale 预留日期、进度、操作和页脚空间。标准 240×280dp / 320×380dp 保持 3 / 5 条；大字号减少条数，最极端尺寸/字号可只显示日期、进度和“打开全部”，不会误报暂无任务。行高至少 48dp，完成和刷新采用原创矢量图标，标题最多两行，剩余任务入口保持可点。颜色继续支持日夜，计数始终来自全部当天任务。

只修改展示，不变更 Room、CAS 修订号、具体日期校验、连续点击防护、所有实例刷新和后台调度。实际 Glance RemoteViews 的主机测量可检查部分几何尺寸，不能证明澎湃OS桌面适配、后台时延或真实点击反馈。模拟器尝试和未验证范围见 phase2e-report.md。
