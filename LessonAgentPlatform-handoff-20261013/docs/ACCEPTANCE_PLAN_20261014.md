# 本轮完整验收执行记录

> 本文保留 2026-10-14 的历史执行记录。当前验收状态见 [2026-10-15—17 总报告](ACCEPTANCE_RESULTS_20261017.md)。

范围：以当前本地提交 `9cf888d9dc4cdb40ea526514151f70f796861a5b` 为起点，验证上一轮修复，不将旧报告中的通过结果当成本轮证据。用户已授权执行全部可行测试和补齐边界测试。

执行原则：采用 superpowers 的 verification-before-completion、systematic-debugging、test-driven-development 和独立代码评审。新增已有行为测试可直接验证；产品缺陷先记录失败回归，再作最小修复。测试使用隔离目录和临时数据库。真实模型/教学效果与离线替身明确区分。

- [x] 识别各模块测试入口、工具链、Docker 与现有环境。
- [x] 首轮 Java clean package：96 项通过、0 跳过（含实际 Docker MySQL）。
- [x] 首轮 Python：F1 4、F2 2、F3 11、F4 66、Runner 3、health 6；PowerShell 进程身份 3 项通过。
- [x] 首轮 Vue：5 项单测、typecheck/build 通过；lint 23 个错误；OpenAPI lint 通过。
- [x] 浏览器 3 项回归与真实 HTTP 当前/历史版本下载验收。
- [x] 补充 MySQL V17→V25 有存量数据升级、并发版本/缺失结构写入、实际 SQL 失败验证。
- [x] 补充前端错误/过期响应、F2 制品、F3 落盘失败、F4 输入/幂等边界；记录 RED→GREEN。
- [x] 执行实际 HTTP、DOCX 制品、Windows 及 Linux 脚本验收，保留测试入口。
- [x] 最终 Java 103 项、前端15单测/3浏览器、Python104项、脚本检查通过；差异复核完成。
- [x] 形成后续验收报告与完整人工步骤，如实保留当时的格式检查和真实环境未验项；三天记录现已并入上述总报告。

处置记录：独立评审提出缺失 operation 键的锁风险，改成仅在 head 变化时当前读；受控并发测试进一步实测既有结构 UPDATE-missing/INSERT 死锁，改原子 upsert，MySQL 与 H2 全量通过。没有改变全局隔离级别，没有大范围格式重排，没有提交/部署。

本轮证据目录：工作区 `.repair/acceptance-20261014/`。仓库无根 `.env`，本机无 LibreOffice；目前不具备真实模型及正式 PDF 转换验收前提。已有 `lesoongen-mysql` 容器保留不改，自动化用 Testcontainers 单独创建临时容器。
