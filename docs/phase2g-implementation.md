# 本地提醒实现与边界

ReminderRepository在任务事务内保存基础设置或单日覆盖；ReminderAwareRepository经同一Engine Mutex串行化变更和投递，Glance刷新仍由原UpdatingTaskRepository触发。主数据来自Room。
Planner批量读配置涉及的任务/规则版本/日期状态和例外，按生产有效实例规则派生候选，不逐日生成数据库任务。系列滚动扫描未来366天并每日维护，显式覆盖独立计算；每次只有一个持久化系统唤醒游标。同刻任务共用一次唤醒批量投递，不注册无限重复闹钟。
每个实例稳定身份为taskId/date。Alarm Intent为独立action和含编码身份的data URI，PendingIntent immutable；requestCode0不会碰撞不同data/component。Notification使用完整实例tag+id0，点击Intent同样含身份/任务日期；任务ID就是系列ID，日期区分实例。游标token拒绝旧回调。取消使用NO_CREATE和相同身份，不取消其他任务或小组件对象。
DB先提交再同步系统；旧游标身份在取消成功前不丢失。已知到期游标在24小时内仍有有效待投递实例时，常规对账保留原token并重新注册，最迟注册到窗口截止；已处理完的游标才推进。批次代表任务被删除或完成，不丢弃同批其他有效实例。系统异常写ERROR/NOTIFY_ERROR，周期Worker和启动核对恢复。Worker只作15分钟修复，不当作精确计时器；不存在常驻服务。

## 权限

新增POST_NOTIFICATIONS用于Android13+运行时通知；新增SCHEDULE_EXACT_ALARM用于Android12+用户授予精确能力。不申请USE_EXACT_ALARM，不申请电池优化白名单、不新增INTERNET。BOOT_COMPLETED沿用原已有权限。
每次注册检查精确能力，权限不足或中途SecurityException降级setAndAllowWhileIdle并记录INEXACT；有能力用setExactAndAllowWhileIdle。精确授权被撤销系统可终止应用并取消闹钟，前台/周期修复在系统允许时重算；不宣称撤销后立即无缝恢复。通知被拒或渠道关闭保留配置但阻断调度。

## 时间与恢复

配置保存本地日期偏移+分钟，不保存固定UTC时刻；每次按当前ZoneId解析。间隙顺延、重叠取早偏移。BOOT_COMPLETED、MY_PACKAGE_REPLACED、TIME_SET、TIMEZONE_CHANGED、DATE_CHANGED和精确授权广播与启动使用同一reconcile。没有持久化待投递游标时仅恢复未来，不扫描补发全部历史提醒；已知待投递游标允许24小时内的迟到恢复，注册或发送失败游标可在对账时补偿。闹钟回调也受同一24小时窗口限制。
唯一投递键阻止重复schedule/广播重复通知。Room先CLAIMED再调用NotificationManager，成功后DELIVERED；发送调用抛错才保留FAILED并重试。notify已返回但DELIVERED写入失败时，保留CLAIMED，不降级为可重发的FAILED；清除通知、重开数据库或再次对账均不盲目重发。删除、完成或关闭时，包含FAILED和CLAIMED在内的未取消账本均按稳定身份取消通知；FAILED超过24小时在界面显示过期和重试结束。
跨Room和系统服务无法原子提交，进程恰在claim/post间死亡可能少一次通知，按防重优先保留待核实状态；确认写库失败也显示待核实，不自动猜测送达结果。此前版本已有FAILED记录无法反推是发送失败还是确认失败，整改不伪造其来源。DELIVERED表示系统notify调用完成，不是物理屏幕显示回执；不承诺exactly-once，勿据此声称澎湃OS后台通知已实测。

## 性能与未验证

在IO线程计算，旧业务只增加配置同步，不在Compose直接操作AlarmManager。候选扫描有366天窗口但可随配置任务量线性增长，投递记录持久化保证时钟回拨不重放；超大多年数据性能仍需后续量化，不声称通过设备压力验收。
API28/35 Robolectric及fake端口仅验证主机可测链路；实际系统重启、Doze、厂商限制、锁屏声音、真实权限界面和设备覆盖安装未实测。未使用ADB/手机/模拟器，不接云推送或后台常驻服务，不执行GitHub。

## 官方依据（2026-10-30查阅）

- [Android闹钟与恢复](https://developer.android.com/develop/background-work/services/alarms)
- [Android14精确闹钟默认拒绝](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)
- [通知运行时权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [小米自启动管理](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624)
- [小米后台本地通知管理](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1626)

小米文档提示自启动和后台本地通知另有管理，不能从标准Android API返回值推断具体设备必然显示。该风险为官方规则核对，不是本机兼容性测试，也不因此引入第三方推送。
