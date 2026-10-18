# 协同共析修订与回看实施计划

**Goal:** 完成聊天确认的五项修复，第四项仅只读回看。

**Spec:** ../specs/2026-10-18-f4-review-design.md

**Architecture:** 章节仍为共析单位；后端使用节点级 edits 原子修改文档；旧建议走严格的兼容定位。前端浏览章节和待处理章节分离。

**Tech Stack:** Vue/TypeScript、FastAPI/Pydantic/SQLAlchemy/Alembic、pytest、Vitest、Playwright。

用户授权完整执行并验证后提交推送。前端和后端为独立文件域，可按 dispatching-parallel-agents 并行；合并前请求独立审查。

## Global Constraints

- 不修改已有测试会话、用户原教案及 Word 临时锁文件。
- 不增加撤销选择，不改变已完成决定与历史快照。
- 不允许定位失败时静默追加或降级丢失表格。
- 未通过验证不提交；不强制推送共享分支。

## Review Focus

- 真实换行与字面量反斜杠、竖线不能相互损坏。
- 单格定位配整行修订必须正确拆分或拒绝，不能污染该格。
- 多项修改部分过期时零写入，重复请求不重复插入。
- 旧建议空 edits、旧 schema、策略快照、历史数据仍可读取。
- 回看时所有写操作入口受保护，刷新/会话变化不误操作其他章节。

## Task 1 后端修订定位与兼容

- [x] 在 tests/test_structured_edits.py 写失败用例：结构化多格修改、跨三段替换/新增、原子失败、转义、整行到单格、追加误用；先运行见失败。
- [x] 新增 app/structured_edits.py，提供 editable_nodes(document, source_ref)、apply_edits(document, source_ref, edits) 与严格兼容逻辑；继续使用 structured_adapter.validate 和派生教学行刷新。
- [x] 修改 providers/interfaces.py、models.py、迁移：保存 edits；修改 context.py/workflow.py/audit.py 提供节点、执行原子修订并返回具体原因及 can_append。
- [x] 增加旧版本迁移、建议落库恢复、完整轮次及非结构化会话兼容测试。
- [x] 运行 F4 全套 pytest，记录结果。

## Task 2 前端回看和候选显示

- [x] 先增加 Vitest/Playwright 行为测试并确认缺功能失败。
- [x] 修改 NavigationWorkbenchView.vue 与样式：独立浏览 ID，上一单元/已完成目录/返回当前单元，轻量状态，删除备注。
- [x] 回看无生成/采纳/拒绝/下一单元操作；现有 view 确认可使用浏览目标。
- [x] api.ts 增加可选 can_append；为不可追加候选隐藏追加按钮并显示失败说明。
- [x] 运行前端全套单测、类型检查、构建、端到端；留桌面与窄屏截图。

## Task 3 集成验收和交付

- [x] 以隔离数据库进行实际 API 工作流试运行，验证投影/结构/历史一致。
- [x] 检查浏览器截图或实际页面，必要时修复并重新验证。
- [x] 独立审查完整差异，修复重要发现，补回归并运行受影响套件。
- [x] 写明验收证据与升级步骤。

交付操作：核对 git diff/status 后提交修复分支；更新远端信息，main 可快进时合并并 push origin main。不创建 PR，不强推。提交和远端状态以 Git 记录及聊天交付反馈为准。

验收记录：`LessonAgentPlatform-handoff-20261013/docs/F4_REVIEW_ACCEPTANCE_20261018.md`。
