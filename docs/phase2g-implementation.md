# 本地提醒实现与边界

ReminderRepository在任务事务内保存基础设置或单日覆盖；ReminderAwareRepository经同一Engine Mutex串行化变更和投递，Glance刷新仍由原UpdatingTaskRepository触发。主数据来自Room。
Planner批量读配置涉及的任务/规则版本/日期状态和例外，按生产有效实例规则派生候选，不逐日生成数据库任务。系列滚动扫描未来366天并每日维护，显式覆盖独立计算；每次只有一个持久化系统唤醒游标。同刻任务共用一次唤醒批量投递，不注册无限重复闹钟。
每个实例稳定身份为taskId/date。Alarm Intent为独立action和含编码身份的data URI，PendingIntent immutable；requestCode0不会碰撞不同data/component。Notification使用完整实例tag+id0，点击Intent同样含身份/任务日期；任务ID就是系列ID，日期区分实例。游标token拒绝旧回调。取消使用NO_CREATE和相同身份，不取消其他任务或小组件对象。
DB先提交再同步系统；旧游标身份在取消成功前不丢失。系统异常写ERROR/NOTIFY_ERROR，周期Worker和启动核对恢复。Worker只作15分钟修复，不当作精确计时器；不存在常驻服务。

## 权限

新增POST_NOTIFICATIONS用于Android13+运行时通知；新增SCHEDULE_EXACT_ALARM用于Android12+用户授予精确能力。不申请USE_EXACT_ALARM，不申请电池优化白名单、不新增INTERNET。BOOT_COMPLETED沿用原已有权限。
每次注册检查精确能力，权限不足或中途SecurityException降级setAndAllowWhileIdle并记录INEXACT；有能力用setExactAndAllowWhileIdle。精确授权被撤销系统可终止应用并取消闹钟，前台/周期修复在系统允许时重算；不宣称撤销后立即无缝恢复。通知被拒或渠道关闭保留配置但阻断调度。

## 时间与恢复

配置保存本地日期偏移+分钟，不保存固定UTC时刻；每次按当前ZoneId解析。间隙顺延、重叠取早偏移。BOOT_COMPLETED、MY_PACKAGE_REPLACED、TIME_SET、TIMEZONE_CHANGED、DATE_CHANGED和精确授权广播与启动使用同一reconcile。正常启动仅恢复未来，不补发旧提醒；闹钟回调至多处理原游标后24小时仍有效的到期实例。
唯一投递键阻止重复schedule/广播重复通知。Room先CLAIMED再调用NotificationManager，成功后DELIVERED；调用失败保留FAILED并重试。跨Room和系统服务无法原子提交，进程恰在claim/post间死亡可能少一次通知，按防重优先保留待核实状态，不谎称exactly-once保证。DELIVERED表示系统notify调用完成，不是物理屏幕显示回执；勿据此声称澎湃OS后台通知已实测。

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
