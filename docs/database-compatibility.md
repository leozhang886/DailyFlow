# Room v5 数据库迁移

0.5.0基线为v4，本阶段正式升级v5。旧tasks/categories/recurrence_rules/recurrence_versions/task_day_states/task_exceptions结构保持不变；优先级、排序、创建时间、完成与历史字段不覆盖。1—4历史JSON保留原哈希，新增5.json。原1→2→3→4迁移链后接MIGRATION_4_5，无destructive migration。

- reminder_rules：taskId主键，enabled/minute/offsetDays；任务外键CASCADE。
- reminder_overrides：taskId/date复合主键，enabled=false为显式单日关闭；任务外键CASCADE。
- reminder_deliveries：taskId/date复合主键，投递/去重状态，不保存标题备注。
- reminder_alarm：slot=0唯一游标，实例身份/时间/token/状态，用于取消旧系统对象及恢复。

投递和游标不级联删除，保证任务删除后还有标识可取消旧通知/闹钟。旧数据升级后新配置表为空，提醒默认关闭。配置写入与任务修改同一Room事务，提交后才触碰系统调度。异常保留明确状态，不把系统注册失败伪装成保存成功且已调度。
真实Native SQLite迁移测试逐列核对旧6表代表性数据，包含分类、两任务、优先级/负排序、规则/规则版本、完成、单日覆盖及备注；升级后读取历史、新增配置/覆盖、关闭重开再读，并验证FK和取消记录。不是空数据库创建替代升级。原有v1—v4迁移回归继续实际执行。
不声称设备覆盖安装验证通过；详见 [测试口径](testing.md)。
