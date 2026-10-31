# 数据库兼容性与迁移

schema v4 不变。Entity、Converter、DailyFlowDatabase 和 schema 1—4 与原版字节一致，无新增列、索引或约束，无破坏性迁移。
priority 默认 NORMAL 自 v2 已有；sortOrder 与 createdAt 已存在。保留 MEDIUM 编码2，新界面归入普通，不批量改写旧值。
单日修改仍使用 priorityOverride，系列修改保留单日覆盖。排序仅更新 sortOrder，不改完成记录、规则、例外、updatedAt 或 stateRevision。
OrganizationPersistenceTest 用冻结 schema 1、2、3、4 创建真实旧 SQLite 文件，填入任务、规则、完成记录、单日例外及 v4 规则版本，再使用当前 Room 打开，验证默认值/有效优先级、历史、修改、排序及关闭重开后的数据保留。
v1/2/3 执行既有迁移链到 v4；v4 原样重开，无需人为增加 4→5 Migration。应用版本与数据库版本独立。
这不是新建空 Room 数据库测试；但没有在真实设备执行带数据覆盖安装，不宣称设备兼容性通过。
