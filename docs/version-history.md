# 真实开发历史与源码来源

> 以下至“Phase 2F 后续记录”之前为0.4.0归档时的历史摘要，保留其证据口径，不将历史结果当成本轮执行结果。

这些阶段早于 Git 初始化，记录的是已有项目档案，不是 Git commit 历史。本次不回填提交、不创建旧版 Tag，也不宣称旧版本重新测试通过。

| 阶段 | 真实版本 | 历史测试报告 | 本地源码 ZIP |
| --- | --- | --- | --- |
| 1 | 第三方应用静态研究 | 不属于 DailyFlow 功能验收 | 研究材料仅本地保留 |
| 2A / 2B | 0.1.0-foundation | 45/45 | DailyFlow-phase2-foundation-source-20261027.zip |
| 2C | 0.2.0-core-ui | 76/76 | DailyFlow-phase2c-source-20261027.zip |
| 2C-W | 0.2.1-widget | 115/115 | DailyFlow-phase2cw-source-20261027.zip |
| 2D | 0.3.0-repeat-history | 156/156 | DailyFlow-phase2d-source-20261027.zip |
| 2E | 0.4.0-ui-polish | 181/181 | DailyFlow-phase2e-source-20261028.zip |

本次只读检查确认上述源码 ZIP 存在、CRC 可读，包含 Gradle 工程、Wrapper、应用源码和测试；其版本字段与表格一致。这不是对各旧工程可构建性或全部依赖可恢复性的重新认证。

0.4.0 已另有 2026-10-29 Windows 主机复验证据，当前源码在归档编辑前与对应源码 ZIP 的 225 个文件完全一致。此轮允许变化仅限归档文档与 .gitignore，业务源码、构建配置、schema 和历史交付文件均受保护。

原有阶段报告、完整源码 ZIP、APK、研究证据与日志继续留在原本地位置，不移动、不覆盖。Git 中保存经审查的历史摘要；如以后需要导入真实旧版本，须单独审查隐私和第三方材料，并以实际导入时间创建提交。

## Phase 2F 后续记录

0.5.0-task-organize（versionCode 6）在独立工作副本实现任务管理增强。
此前独立审计实际执行211项测试通过，Lint为0错误、18警告，结论B；它是历史执行结果，不替代本轮整改验证。
本轮整改的完整测试与Lint结果见 [测试说明](testing.md)。保留旧交付物，不回填Git历史，不自动给旧版本认证或建Tag。
