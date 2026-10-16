# Windows 开发运行与维护

本文汇总原三天专题文档中仍有效的操作内容，与[开发验收总报告](ACCEPTANCE_RESULTS_20261017.md)配套。首次安装见 [INSTALL_WINDOWS.md](INSTALL_WINDOWS.md)。约定环境是 Windows、每模块单实例，Java 是正式平台入口；内部引擎端口不作为公开服务。

## 1. 日常启停与配置

以下命令在业务仓库 `LessonAgentPlatform-handoff-20261013` 根目录执行。先确认配置的 MySQL 可用，再运行应用：

```powershell
powershell.exe -NoProfile -File .\scripts\start-all.ps1
powershell.exe -NoProfile -File .\scripts\health-check.ps1
powershell.exe -NoProfile -File .\scripts\stop-all.ps1
```

默认前端 `http://127.0.0.1:5177`，Java 8080，F4/F2/F1/F3 分别 8000/8001/8002/8003。执行脚本的终端应满足本机执行策略；脚本遵守有效策略。首次安装完成后不要再用模板覆盖已填写的 `.env`。

Windows Python 保留 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`、`PYTHONUNBUFFERED=1`，避免重定向日志时中文/emoji 编码错误。真实密钥和密码只放本机配置，`.env`、`.runtime`、运行数据和数据库备份不提交源码。日志在 `.runtime/logs/`；停止失败返回非零并保留身份记录，核对 PID、启动时间、可执行路径和命令行后处理，不能按进程名批量终止或删 PID 文件冒充停止成功。

应用不强制依赖 Docker。本开发机曾用 Docker 容器运行 MySQL；这仅是数据库安装方式，不代表整套应用已容器化。选择数据库时依据 `.env` 与实际端口核对，不把旧验收报告中的容器名/端口当通用默认配置。已有数据卷不得删除。

## 2. 可选本机 Nginx 入口

使用 [Windows 模板](../deploy/nginx/lesson-agent.windows.conf.example)及[入口脚本](../deploy/nginx/windows-entry.ps1)。准备 Windows Nginx 解压目录，在前端目录完成 `npm run build`，按原脚本启动应用；本入口脚本只管理 Nginx。

启动 Java **之前**，在该套应用配置中设 `FRONTEND_ORIGIN=http://127.0.0.1:18080`。本机 HTTP 使用 `SESSION_COOKIE_SECURE=false`。浏览器统一使用完整地址，不混用 localhost、127.0.0.1 和其他端口。若切回 5177，也需同步调整 Origin。

```powershell
$EntryNginxHome = 'D:\tools\nginx'
$EntryRuntime = Join-Path $PWD '.runtime\windows-entry'

powershell.exe -NoProfile -File .\deploy\nginx\windows-entry.ps1 -Action Check -NginxHome $EntryNginxHome -RuntimeDir $EntryRuntime
powershell.exe -NoProfile -File .\deploy\nginx\windows-entry.ps1 -Action Start -NginxHome $EntryNginxHome -RuntimeDir $EntryRuntime
powershell.exe -NoProfile -File .\deploy\nginx\windows-entry.ps1 -Action Status -RuntimeDir $EntryRuntime
powershell.exe -NoProfile -File .\deploy\nginx\windows-entry.ps1 -Action Stop -RuntimeDir $EntryRuntime
```

默认入口固定监听 `127.0.0.1:18080`，代理至 `127.0.0.1:8080`。可改 `-ListenPort`、`-BackendPort`、`-FrontendDist`、`-ProxyReadTimeoutSeconds`，不能据此视为已开放公网。`Check` 渲染配置并运行 `nginx -t`；`Start` 检查端口/静态文件并记录进程身份；`Stop` 核对身份后优雅退出，不影响其他实例。故障检查运行目录的 `logs/error.log`、`access.log`、`stderr.log`。

代理保留含端口的 Host，继续由应用检查登录、Origin 和 CSRF。前端深路由刷新回到 index，缺失静态资源返回 404。单文件限额 **20 MiB**，代理/Java multipart 请求体 **22 MiB** 为表单边界留空间，不是扩大单文件限额。

默认代理读取/发送等待 360 秒，连接等待 5 秒；读取等待是两次读取间隔，不是整课总时长。课堂采用 HTTP 轮询，不依赖 WebSocket/SSE。超时响应不撤回已经发出的任务或供应商费用。公网、HTTPS 和外部网络边界待后续部署阶段实施。

## 3. 五项新任务开关

Java 配置属性 `platform.admission-file`，可用环境变量 `PLATFORM_ADMISSION_FILE` 指向本机绝对路径。未指定文件时五项默认全开；一旦指定，缺失、不可读、超限或格式不合法都返回 **503 `FEATURE_CONFIGURATION_INVALID`**，不会静默全开。

文件最大 64 KiB，仅包含以下六个字段；开关必须是 JSON 布尔值，`version` 为 1–100 位英文字母、数字或 `._:-`，不能有重复字段/未知字段/尾随 JSON：

```json
{
  "version": "local-01",
  "structure": true,
  "f1": true,
  "f2": true,
  "f3": true,
  "f4": true
}
```

- `structure`：新文本/Word 导入及预览、人工新增版本，以及 F1–F4 新任务的前置条件。
- `f1`：新的单次评价、批次和结果比较。
- `f2`：新的生成、优化，含旧上传优化接口。
- `f3`：新的课堂启动任务。
- `f4`：新的平台共析会话。

首次配置示例（目标文件尚不存在时）：

```powershell
$admissionDir = Join-Path $PWD 'Lessongen-main\web\var\operator'
[System.IO.Directory]::CreateDirectory($admissionDir) | Out-Null
$admissionFile = Join-Path $admissionDir 'capabilities.json'
$admissionJson = '{"version":"local-01","structure":true,"f1":true,"f2":true,"f3":true,"f4":true}'
[System.IO.File]::WriteAllText($admissionFile, $admissionJson, [System.Text.UTF8Encoding]::new($false))
$env:PLATFORM_ADMISSION_FILE = $admissionFile
# 在继承该变量的终端中启动 Java；也可将绝对路径写入本机 .env。
```

运行中修改时，同目录写临时文件后原子替换，不直接截断当前文件。例：关闭 F2：

```powershell
$admissionNext = $admissionFile + '.next'
$admissionJson = '{"version":"local-02","structure":true,"f1":true,"f2":false,"f3":true,"f4":true}'
[System.IO.File]::WriteAllText($admissionNext, $admissionJson, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::Replace($admissionNext, $admissionFile, $null)
```

每次新任务准入热读，无需重启；关闭返回 **503 `FEATURE_DISABLED`**。权限、归属及同 key 重放检查优先；相同请求复用既有任务，改输入仍冲突。已准入任务、排队派发、批次后续项、停止/归档、下载、复核和已准入 F4 写回继续。关闭不强杀，重新开启不自动复活失败或费用未知的请求。

F4 已绑定会话直接复用；旧已绑定会话先只读核对原来源再恢复准入记录，不以 POST 试探。无本地归属/准入证据的远端会话不能绕过检查。F3 已准入课堂的内部 F4 兼容续作核对持久启动记录，不信任客户端传入“已准入”。

`app.scheduling-enabled` 是调度维护配置，不能替代这些开关；`platform.structure-backfill.enabled` 默认 false，仅负责历史缺结构补齐，也不是 structure 开关。内部引擎不重复准入，避免拦截既有任务；不要将其直接暴露到外网。

## 4. 超时、失败恢复与文件能力

F3 默认环境变量 `F3_MODEL_TIMEOUT_SECONDS=60`、`F3_ANALYSIS_TIMEOUT_SECONDS=300`，必须为有限正数；每次模型请求和整次分析各有硬期限，Windows Job 约束子孙进程，清理另有最多 5 秒确认期。新课堂无默认整课截止；显式 `maxWallClockSeconds` 按活动时间计，暂停不计，但已发出 HTTP 的实际时间继续计入安全期限。

Java 显式分析请求等待服务公布期限加 30 秒，页面加 60 秒；提高分析期限须同步提高代理等待。失败分析可返回 **502 `F3_ANALYSIS_INCOMPLETE`**，FAILED 与警告仍保存供只读刷新；显式重试另追加分析快照，不改原课堂事件。未知结果不自动重发；重启后未完成课堂为 INTERRUPTED，不自动恢复收费推理。本地终止不能保证撤销供应商已接收请求的费用。

F1 `execution.hard_timeout_seconds` 默认 900 秒，环境变量 `F1_ANNOTATION_HARD_TIMEOUT_SECONDS` 优先。批恢复复用已完成项；超时或结果未知不自动重扣。只重试生成产物与重新评分是不同操作，不应混用。

PDF 使用独立 FODT 模板，`platform.pdf.soffice` 指向 LibreOffice；缺配置返回 503，繁忙 429，转换超时 504，转换并发默认 2、期限 60 秒。Word/PDF 标题黑体、正文/表格/备注宋体，未嵌入字体；其他机器应安装对应字体。原稿下载保持原字节，复杂图片/文本框/合并嵌套原布局允许有提示的降级。

F2 局部范围外新增/删改拒绝，原有缺项提示；无法定位的重点不能默认为全文重写。人工自由编辑保留 `EDITED_FALLBACK`，不冒充模型范围验证候选。旧真实任务结果不追溯改标。

测试中的 10 元累计预算不是平台生产全局预算。运行前应自行配置实际模型、账单及成本保护；费用未知保持未知，不填零。历史预留与 usage 金额见总报告。

## 5. 数据备份、升级及条件回退

停写后保留同一恢复点的 MySQL 和各模块文件，记录摘要及实际配置路径：

- Java：`LESSON_STORAGE_ROOT`，默认 `Lessongen-main/web/var/storage`。
- F1：`System-v1.2/platform_runs`、`platform_batches`、`platform_comparisons` 三处及使用的输入/配置，不能只备份单次任务。
- F2：`PAPER4_WEB_STATE_ROOT`、`PAPER4_ARTIFACTS_ROOT`，默认 `Lessongen-main/paper4_pipeline/var/engine-state`、`var/engine-artifacts`。
- F3：`F3_STORAGE_ROOT`，默认 `SimClass-main/runtime/f3_sessions`。
- F4：`DATABASE_URL` 指向的实际 SQLite 数据库。

SQLite 使用停写复制或备份接口，不复制正在写入的孤立主文件。先恢复到隔离环境，核对历史正文/head/原稿和新任务，再升级实际环境。

Java 正常启动执行 Flyway；F4 在其 backend 及专用虚拟环境执行 `python -m alembic -c alembic.ini upgrade head`。本轮新增迁移：

- **V26：** 结构修复完整旧行快照、摘要和回退审计。
- **V27：** 平台优化原请求稳定指纹。旧 NULL 指纹无法证明时返回 409 `OPTIMIZE_LEGACY_REQUEST_UNVERIFIED`；继续读取既有任务，不猜指纹或盲目换 key 重建。
- **V28：** F4 准入、首次请求快照和来源。MySQL/H2 分 vendor 目录；MySQL 外键及 session 关联字段匹配既有父列字符集/排序规则，支持恢复库不同默认排序规则。

不得覆盖已发布/已执行历史 migration、关闭外键或删除 Flyway 历史来绕过错误。本轮隔离库的失败 V28 记录处置具有严格前提（V27 成功、V28 失败且未建表、已备份并核对身份），不是通用升级步骤。

### 结构条件回退

这只恢复指定审计对应的结构行，**不是数据库 schema 降级**；正文、父链、head、原稿不改。`structure-repair` profile 关闭 Flyway、调度和 backfill，因此必须在已完成迁移且已备份的数据库上执行。

以下命令位于 `Lessongen-main/web`。直接 `java -jar` 不会读取仓库根 `.env`；先在当前 PowerShell 设置并核验 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，或使用已审核的 Spring 外部配置，明确指向目标隔离库且不回显密码。不能因为 `.env` 存在就认为已加载，也不要为加载配置而启动整套服务。先运行只读报告核对目标：

```powershell
java -jar target/Lesoongen-0.0.1-SNAPSHOT.jar --spring.profiles.active=structure-repair --repair-report=structures.json --repair-audit-report=rollback-audits.json
```

先核对只读报告，选 `eligible=true` 的审计及回退理由，写 `reviewed-rollbacks.json`：

```json
[
  {
    "auditId": "待回退的审计编号",
    "expectedContentSha256": "报告中的 contentSha256",
    "expectedCurrentRowSha256": "报告中的 currentRowSha256",
    "reason": "已核验的回退原因"
  }
]
```

仅设 `--repair-rollback=true` 不写数据，实际应用必须同时显式指定 `--repair-apply=true`：

```powershell
java -jar target/Lesoongen-0.0.1-SNAPSHOT.jar --spring.profiles.active=structure-repair --repair-rollback=true --repair-apply=true --repair-manifest=reviewed-rollbacks.json --repair-report=after-rollback.json --repair-audit-report=after-rollback-audits.json
```

执行使用锁定当前读，核对正文、修复后整行和旧快照摘要及未回退状态。后续内容/元数据变化、清单过期、重复回退、旧审计缺完整快照均拒绝；旧审计只能用已验证备份恢复。原先无结构行则恢复为缺失，原先降级状态也可能恢复，因此必须审核。

每条独立事务；后项失败不撤销前面成功项。重新生成报告后只处理剩余项，不重放整份旧清单。审计保留回退时间/原因，不删除历史记录。

## 6. 后续变更的验证入口

已有通过结果及证据边界见总报告；源码变更后运行受影响回归，交付前完整构建。命令需在已安装依赖、可用测试 MySQL/Docker 的环境执行，不应把跳过项标为通过。

```powershell
# 在 Lessongen-main/web
.\mvnw.cmd clean package

# 在 Lessongen-main/web/frontend
npm test
npm run typecheck
npm run build
```

Python 在各模块自己的虚拟环境运行 `python -m pytest tests -q`，F3 Runner 使用其 scripts/f3-runner 测试目录；前端脚本以 [package.json](../Lessongen-main/web/frontend/package.json) 为准。浏览器回归须使用已安装的浏览器；不得因缺默认 Chromium 就将实际 Edge 验证写成 Chromium 验证。

正式链检查导入原稿 → V0 → F4/V1 → F2 复核/V2 → F3 指定版输入 → 当前版下载，并补登录/跨账号、CSRF、幂等、冲突、开关、故障恢复及原稿摘要。模型替身证明工程行为，真实模型和教学质量另记。多实例账本、公网/HTTPS、容量与恢复指标、生产部署及教师评审仍按总报告列入后续安排。
