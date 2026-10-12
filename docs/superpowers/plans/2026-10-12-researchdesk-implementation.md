# ResearchDesk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Subagent-driven execution is available if selected by the user. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可部署、可演示、可评测的资料研究工作台，覆盖上传、引用问答、报告编辑与导出。

**Architecture:** React 浏览器客户端仅访问 Java 同源 API。Java 管理身份、项目、业务事务与运行状态；Python 管理解析、检索和模型生成，通过受认证的内部接口与任务队列协作。PostgreSQL 保存业务真相，Redis 只传输任务。

**Tech Stack:** React、TypeScript、Vite、Spring Boot、Python、FastAPI、PostgreSQL、pgvector、Redis、MinIO、Docker Compose；Vitest、Playwright、JUnit、Testcontainers、pytest。

## Global Constraints

- 已批准规格：`E:\test\work\docs\superpowers\specs\2026-10-12-researchdesk-design.md`。
- 目标仓库：`E:\test\work\researchdesk`；不得修改 java-roadmap 或 sentinelops-ai。
- 支持含可提取文字的 PDF 与 UTF-8 Markdown；不支持扫描件 OCR、联网搜索、多人实时协作、计费和任意工具执行。
- 初始默认限制为单文件 20 MiB、PDF 200 页、每项目 50 份资料，并允许部署配置收紧。
- 默认每用户同时运行 2 个生成任务，问答总时限 120 秒、报告生成总时限 300 秒。
- 默认模型输出上限为问答 2,000 tokens、报告 6,000 tokens。
- 暂时性故障最多自动重试 3 次，使用退避；格式错误和无文本等永久错误不自动重试。
- 浏览器不持有供应商密钥，不直接访问 Python；项目所有权由 Java 校验。
- 业务 schema 与检索 schema 分账号授权；写数据库迁移前加载 supabase-postgres-best-practices 技能。
- 新增行为先有失败测试，再最小实现和验证；纯文档及常规配置不编造测试。
- 开发者不得用跳过测试、仅 mock 或替身模型成绩替代真实链路验收。
- 此文件中的依赖选择是架构选择，不是经过验证的版本兼容结论。任务 1 核对官方资料并锁定实际版本。

## 执行方式与文件地图

目前只存在设计和计划，以下路径均为新项目内的计划路径，并非现有实现。执行时先创建独立 Git 仓库，复制已批准设计和本计划；按 using-git-worktrees 技能决定是否需要工作树。不得把父目录初始化为 Git 仓库，不自动创建远程仓库或发布公网服务。

| 路径 | 责任 |
| --- | --- |
| `apps/web/src/features/{auth,projects,documents,research,reports}/` | 按功能组织页面与组件 |
| `apps/web/src/lib/api.ts`、`events.ts` | HTTP 错误协议、SSE 恢复 |
| `apps/api/src/main/java/io/researchdesk/{identity,project,document,task,research,report}/` | 业务模块 |
| `apps/api/src/main/resources/db/migration/` | Flyway 业务及授权迁移 |
| `apps/ai/researchdesk_ai/{ingestion,retrieval,generation,worker}/` | Python 职责模块 |
| `apps/ai/tests/` | Python 单元与集成测试 |
| `contracts/public.yaml`、`internal.yaml`、`events.schema.json` | 唯一契约源及事件 schema |
| `infra/compose.yaml`、`infra/proxy.conf` | 本地/生产配置与同源入口 |
| `scripts/` | 启停、验收与评测 PowerShell 入口 |
| `evals/` | 数据集、评分协议和版本化结果 |
| `docs/` | 架构、演示、部署和测试证据 |

所有执行命令默认在新仓库根目录运行。任务创建脚本时，脚本必须返回真实子进程退出码；无 Docker、模型密钥或其他前提时明确失败/阻塞，不能自动变成成功。

## 契约基线

任务 1 将以下公共形状写入 OpenAPI、Java DTO 和生成的 TypeScript/Python 类型，禁止在后续任务中创建拼写不同的平行版本。

```typescript
type TaskState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
type RunState = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
type RunKind = 'ANSWER' | 'REPORT';
type ApiError = { code: string; message: string; requestId: string };
type Citation = {
  chunkId: string; documentVersionId: string; title: string;
  page: number | null; paragraph: number | null;
  excerpt: string | null; availability: 'AVAILABLE' | 'DELETED';
};
type RunEvent = {
  runId: string; seq: number;
  type: 'started' | 'delta' | 'citation' | 'completed' | 'failed' | 'cancelled';
  payload: Record<string, unknown>;
};
type GenerationRequest = {
  kind: RunKind; projectId: string; conversationId?: string;
  documentVersionIds: string[]; prompt: string;
};
```

内部任务使用 `taskId, attemptId, fence, projectId, documentVersionId, objectKey, parserVersion`。Java 生成授权资料范围；Python 不能从用户文本中重新选择项目或资料。Java 回调接口拒绝旧 `fence`。

## 里程碑一：用户可以上传并阅读资料

### Task 1：可启动的工程及契约检查

**Files:** 创建 `README.md`、`.gitignore`、`.env.example`、`apps/api/pom.xml`、`apps/api/mvnw.cmd` 及 wrapper 文件、`apps/ai/pyproject.toml`、`apps/web/package.json`；创建上述 contracts、`infra/compose.yaml`、`infra/proxy.conf`、`scripts/test-contracts.ps1`、`scripts/test-api.ps1`、`docs/dependency-baseline.md`。

**Interfaces:** 产出 `/api/health`、内部 `/health`、契约生成命令；API 测试脚本接收 `-Test` 和 `-Integration`，分别调用 Maven test 和 verify 并传递筛选条件。

- [x] 检查 Git、JDK、Node、Python、Docker 实际可用性；使用官方发布和兼容资料确定版本，记录原因、运行时、镜像摘要和锁文件。读取本任务适用的技能后生成最小三个应用。
- [x] 先定义健康接口检查：`GET /api/health` 期望 `200 {"status":"UP"}`，依赖不可用的 readiness 期望 503；实际启动前运行应连接失败，不把缺少测试工具当作业务红灯。
- [x] 配置 health 与 readiness 区分，加入真实数据库和队列探测；创建同源代理，把 `/api` 转到 Java，其他路径转到前端。
- [x] 在 OpenAPI 定义 `GenerationRequest`、`Citation`、`ApiError` 及每种 event payload；生成类型并进行契约校验，未知枚举必须失败。
- [x] 运行 `./scripts/test-contracts.ps1` 与 `docker compose -f infra/compose.yaml config`，启动后验证两个 health 接口和依赖断开时 readiness 503。记录实际结果；提交 `chore: bootstrap ResearchDesk services and contracts`。

### Task 2：身份及项目所有权

**Files:** 创建 `identity/SessionService.java`、`identity/AuthController.java`、`project/ProjectService.java`、`project/ProjectController.java`、迁移 `V001__identity_projects.sql`、测试 `identity/ProjectOwnershipIT.java`；前端 `features/auth/LoginPage.tsx`、`features/projects/ProjectPage.tsx`。

**Interfaces:** `/api/auth/register|login|logout|session`；`POST/GET /api/projects`、`GET /api/projects/{id}`。登录返回 HttpOnly 会话 cookie；写请求要求 CSRF token。

- [ ] 加载数据库技能并编写失败用例：用户 A 创建项目后，B 和不存在 ID 均得到同构 404；没有 CSRF 的有效会话写请求得到 403；登出后读取得到 401。
- [ ] 运行 `./scripts/test-api.ps1 -Test ProjectOwnershipIT -Integration`，确认失败发生于授权行为而非环境。
- [ ] 实现邮箱形式用户名、成熟密码哈希、会话轮换、过期与撤销。查询必须同时限定资源 ID 和会话用户，使用类似 `findByIdAndOwnerId(projectId, principal.userId())` 的仓储契约；不存在则统一 404。
- [ ] 实现注册登录与项目页面，明确无邮箱验证/密码找回；前端收到 401 清除页面私有数据并转登录。
- [ ] 重跑集成测试并在浏览器完成注册、登录、创建、登出；提交 `feat: add sessions and private research projects`。

### Task 3：文件上传、配额与资料列表

**Files:** 创建 `document/DocumentService.java`、`document/DocumentController.java`、`document/ObjectStorage.java`、`V002__documents_tasks_outbox.sql`、`document/UploadIT.java`、`features/documents/DocumentPage.tsx`、`DocumentPage.test.tsx`。

**Interfaces:** `POST /api/projects/{id}/documents` 接收 multipart，返回 `{documentId, versionId, taskId, state}`；列表和任务查询返回数据库状态。DocumentState 为 PROCESSING、READY、FAILED、DELETING、DELETED。

- [ ] 编写失败测试：20 MiB 以上返回 413；伪造扩展名、非法 UTF-8、加密 PDF 返回稳定错误；第 51 份返回配额错误；并发上传相同内容只产生一个文档及有效任务。
- [ ] 运行 `./scripts/test-api.ps1 -Test UploadIT -Integration`，确认目标断言失败。
- [ ] 实现流式上传限制、类型检测、项目行锁或等效原子配额控制、项目内内容摘要唯一约束；对象写入失败不得创建成功业务记录，事务失败后的孤儿对象列入清理队列。
- [ ] 同一数据库事务写文档、任务与 Outbox。前端只展示后端状态；上传前提示资料将用于配置的模型服务，失败显示明确原因及操作。
- [ ] 重跑集成测试和 `npm --prefix apps/web test -- DocumentPage.test.tsx`；提交 `feat: accept bounded document uploads`。

### Task 4：可靠解析与幂等索引

**Files:** 创建 `task/TaskService.java`、`task/OutboxDispatcher.java`、`task/InternalTaskController.java`、`task/TaskLeaseIT.java`；Python `worker/consumer.py`、`ingestion/parser.py`、`ingestion/indexer.py`、`tests/test_ingestion.py`、`tests/test_worker_recovery.py`。

**Interfaces:** 内部 claim、heartbeat、complete、fail 带 attemptId/fence；`parse_document(content: bytes, media_type: str) -> list[ParsedSection]`，ParsedSection 包含 text、page、paragraph；`index_document(task, sections)` 按版本及片段序号幂等写入。

- [ ] 先测试：相同消息投递两次只保留一组索引；旧 fence 完成回报被拒绝；worker 在部分写入后崩溃，重试成功前资料不能进入 READY。
- [ ] 运行 `./scripts/test-api.ps1 -Test TaskLeaseIT -Integration` 和 `uv run --project apps/ai pytest apps/ai/tests/test_worker_recovery.py -q`，确认目标失败。
- [ ] 实现 Redis 消费组、数据库租约/CAS 与 Outbox 重投；首次失败后最多重试 3 次，延迟 2/4/8 秒加抖动；永久错误立即 FAILED。增加后台恢复扫描，队列丢失后可重建未完成任务。
- [ ] 解析器运行在限时子进程，禁用外部资源读取，200 页超限、无文本、加密和格式损坏分别映射错误。Markdown 保留标题和段落序号，PDF 保留页码。
- [ ] 索引先写未发布版本，完整校验后发布，再向 Java 完成回报。Java 尚未 READY 的版本不能进入授权检索集合。实现确定性 embedding 替身并明确标识。
- [ ] 执行上述测试及 `uv run --project apps/ai pytest apps/ai/tests/test_ingestion.py -q`；提交 `feat: add recoverable ingestion workers`。

### Task 5：原文预览、引用定位与删除

**Files:** 创建 `document/SourceController.java`、`document/DeletionService.java`、`document/DocumentDeletionIT.java`；`features/documents/SourceViewer.tsx`、`SourceViewer.test.tsx`、`tests/e2e/documents.spec.ts`。

**Interfaces:** `GET /api/documents/{id}/source` 由 Java 授权代理内容；`DELETE /api/documents/{id}` 先失效后异步清理；前端 `SourceViewer({citation: Citation})` 定位来源。

- [ ] 测试删除后不能读取原文或检索；旧 Citation 返回 availability=DELETED 且 excerpt=null；重复删除幂等。测试 PDF 页码、Markdown 段落定位和键盘操作。
- [ ] 运行 `./scripts/test-api.ps1 -Test DocumentDeletionIT -Integration` 与 `npm --prefix apps/web test -- SourceViewer.test.tsx`，确认失败。
- [ ] 实现安全 PDF 预览与 Markdown 纯文本/安全渲染；删除立即撤销有效版本，清理向量和对象失败可重试，历史回答正文保留且删除提示说明这一点。
- [ ] 通过浏览器上传并刷新、查看 READY、定位原文、删除；运行 `npm --prefix apps/web run test:e2e -- documents.spec.ts`，提交 `feat: preview and delete document sources`。

## 里程碑二：用户可以获得可追溯的研究回答

### Task 6：评测资料、基线检索及授权过滤

**Files:** 创建 `evals/dataset.jsonl`、`evals/protocol.md`、`evals/split.json`、`evals/fixtures/`；Python `retrieval/search.py`、`retrieval/types.py`、`tests/test_retrieval.py`、`tests/test_eval_dataset.py`。

**Interfaces:** `retrieve(project_id: str, allowed_version_ids: list[str], query: str, limit: int = 8) -> list[Evidence]`；Evidence 字段为 chunk_id、document_version_id、text、page、paragraph、score。

- [ ] 建立 60 题及来源标注，题型数量按设计，40 题开发集与 20 题保留集固定；先写集合互斥、题型数量和来源存在性的测试。
- [ ] 写检索失败用例：向量最相近片段属于其他项目时绝不返回；非 READY 或不在 allowed_version_ids 的版本也不可返回；空授权集返回空列表。
- [ ] 运行 `uv run --project apps/ai pytest apps/ai/tests/test_retrieval.py apps/ai/tests/test_eval_dataset.py -q`，确认目标失败。
- [ ] 实现数据库查询中前置项目和版本过滤，先建立向量基线，候选最多 20、最终最多 8；写清分块与嵌入版本以便复现。
- [ ] 在 protocol 中冻结评分量表、人工复核规则、指标计算代码接口和质量阈值，记录制定依据；冻结完成前不得运行保留集。阈值需在此任务审阅，不能看完保留集成绩再修改。
- [ ] 重跑测试，只运行开发集基线并保存配置摘要；提交 `feat: add scoped retrieval and evaluation dataset`。

### Task 7：有证据约束的生成

**Files:** 创建 Python `generation/provider.py`、`generation/answer.py`、`generation/citations.py`、`generation/prompts.py`、`tests/test_generation.py`；Java `research/GenerationService.java`、`research/GenerationController.java`、`research/GenerationScopeIT.java`。

**Interfaces:** `POST /api/runs` 消费 GenerationRequest；`GET /api/runs/{id}` 返回状态及完整快照；provider 提供 `stream(messages, max_tokens, deadline)`；引用只接受 Evidence 中的 chunk_id。

- [ ] 测试无证据时不调用供应商；伪造来源不能变为 Citation；注入文本不能改变资料范围；跨用户 conversationId 返回 404；超过两个并发运行返回 429。
- [ ] 运行 `uv run --project apps/ai pytest apps/ai/tests/test_generation.py -q` 与 `./scripts/test-api.ps1 -Test GenerationScopeIT -Integration`，确认失败。
- [ ] Java 校验并冻结有效资料范围及最近 12 条会话消息，再以 8,000 tokens 历史预算裁剪；Python 按证据边界构建提示，系统规则不与原文拼接成同级指令。
- [ ] 增加真实供应商和确定性替身适配器，限制输出及截止时间。流中引用为草稿，完成时校验并保存映射；引用校验不通过标记失败，不展示成已验证答案。
- [ ] Java 完成提交前重验文档可用性，删除竞态终止生成。运行测试并提交 `feat: generate evidence-grounded research answers`。

### Task 8：流式事件、停止及断线恢复

**Files:** 创建 Java `research/RunEventStore.java`、`research/RunStreamController.java`、`research/RunRecoveryIT.java`；前端 `lib/events.ts`、`lib/events.test.ts`、`features/research/ResearchPage.tsx`、`ResearchPage.test.tsx`。

**Interfaces:** `GET /api/runs/{id}/events` 接受 Last-Event-ID；`POST /api/runs/{id}/cancel`；事件 seq 单调递增，终态不可回退。事件超过 24 小时窗口返回 EVENT_WINDOW_EXPIRED，客户端读取运行快照。

- [ ] 写失败测试：重复 seq 不重复拼接；断线后补齐缺失序列；取消后迟到 completed 不改变终态；运行失败后不自动重新调用模型。
- [ ] 运行 `./scripts/test-api.ps1 -Test RunRecoveryIT -Integration`、`npm --prefix apps/web test -- events.test.ts`，确认失败。
- [ ] 实现持久事件批写、心跳、Java 授权订阅和恢复；可用以下规则约束客户端 reducer：

```typescript
if (event.seq <= state.lastSeq) return state;
if (state.terminal) return state;
// 按 delta/citation/终态分别更新；发现序号缺口先恢复，不能跳过缺失文本。
```

- [ ] 组装三栏工作台，流式输出不抢滚动，引用可键盘选择；停止按钮给出取消请求状态，并说明供应商可能仍计费。
- [ ] 运行页面测试及真实断网/重连旅程；提交 `feat: stream resumable research conversations`。

## 里程碑三：用户可以保存并交付报告

### Task 9：报告草稿、编辑和版本冲突

**Files:** 创建 `report/ReportService.java`、`report/ReportController.java`、`report/ReportVersionIT.java`、`V003__reports_versions.sql`；Python `generation/report.py`、`tests/test_reports.py`；前端 `features/reports/ReportEditor.tsx`、`ReportEditor.test.tsx`。

**Interfaces:** RunKind.REPORT 使用已授权来源生成；`POST /api/reports/{id}/versions` 接受 `{baseVersion, markdown}`；返回不可变版本。冲突返回 409 VERSION_CONFLICT。

- [ ] 编写失败测试：两次基于 v1 保存只能一个成功；重新生成创建新草稿；编辑删除引用不会留下错误编号；引用其他项目 ID 被拒绝。
- [ ] 运行 `./scripts/test-api.ps1 -Test ReportVersionIT -Integration`、`uv run --project apps/ai pytest apps/ai/tests/test_reports.py -q`，确认目标失败。
- [ ] 实现数据库乐观版本更新与不可变历史，生成草稿复用检索和引用验证；报告正文将引用保存为稳定来源标记，展示时解析。
- [ ] 实现 Markdown 编辑/预览、显式保存、离开提示、冲突另存副本、历史查看；原始 HTML 不执行。通过编辑器测试并提交 `feat: edit and version grounded reports`。

### Task 10：安全的 Markdown / PDF 导出

**Files:** 创建 `report/ExportService.java`、`report/ExportController.java`、`report/ExportIT.java`；Python `generation/export.py`、`tests/test_export.py`；`tests/e2e/reports.spec.ts`、`docs/export-verification.md`。

**Interfaces:** `POST /api/reports/{id}/exports` 接受 `{version, format:'MARKDOWN'|'PDF'}` 返回任务；下载端点先校验所属项目，导出源固定为该历史版本。

- [ ] 写失败测试：中文和多页内容保留；包含外部图片、file URL、HTML 的 Markdown 不引发网络/文件读取；导出不能读取其他用户报告。
- [ ] 运行 `uv run --project apps/ai pytest apps/ai/tests/test_export.py -q` 与 `./scripts/test-api.ps1 -Test ExportIT -Integration`，确认目标失败。
- [ ] 选择并锁定能嵌入中文字体的 PDF 渲染器，在禁用网络和受限文件访问环境生成，输出引用附录与已删除来源提示；生成文件进入私有对象存储。
- [ ] 加载 PDF 技能，对中文、多页表格、长引用样例进行实际渲染检查，保存视觉证据。运行报告浏览器旅程，提交 `feat: export versioned research reports`。

## 里程碑四：评测、恢复与可复现交付

### Task 11：检索改进与真实模型评测

**Files:** 创建 Python `retrieval/hybrid.py`、`retrieval/reranker.py`、`tests/test_hybrid.py`；`evals/run.py`、`evals/score.py`、`evals/test_score.py`、`scripts/evaluate.ps1`、`docs/evaluation-results.md`。

**Interfaces:** evaluate 脚本接收 `-Split dev|holdout -Mode stub|real -Config path`；缺少真实模型配置时 real 失败，不能回退替身。结果包含数据和配置 hash、指标、延迟、实际或未知用量。

- [ ] 编写人工可算的评分测试：两题中一题 top5 命中得到 Recall@5=0.5；未提供 token 用量时输出 null；引用存在但语义不支持计为错误引用。
- [ ] 运行 `uv run --project apps/ai pytest evals/test_score.py apps/ai/tests/test_hybrid.py -q`，确认目标失败。
- [ ] 实现关键词与向量融合、可配置重排；只用开发集比较，并依据质量、成本和延迟记录选择。不要默认更复杂配置一定更好。
- [ ] 确认任务 6 协议与阈值已冻结，使用真实模型一次正式运行保留集；人工复核引用与报告量表，公布失败案例和限制。如果未达标，标记失败并建立新的数据版本，不在旧保留集上反复调参冒充独立验收。
- [ ] 提交脚本、无敏感数据的结果和配置；提交 `feat: benchmark retrieval and generation quality`。

### Task 12：全链路故障、观测与部署验收

**Files:** 创建 `research/UsageService.java`、`task/RecoveryIT.java`、`tests/e2e/research-journey.spec.ts`、`scripts/verify.ps1`、`scripts/smoke.ps1`、`.github/workflows/ci.yml`、`docs/deployment.md`、`docs/demo.md`、`docs/interview.md`。

**Interfaces:** `./scripts/verify.ps1` 执行契约、Java、Python、前端单元/构建及浏览器检查；`./scripts/smoke.ps1` 在启动的 Compose 上完成注册到报告导出的旅程；评测单独调用并明确是否真实模型。

- [ ] 写故障旅程：杀死解析 worker 后任务恢复；Redis 中断后业务任务不丢失；供应商超时显示可重试错误；取消不被迟到成功覆盖；删除与完成竞态不保存有效引用。
- [ ] 在受控测试容器上运行失败用例，不对已有项目服务执行停止操作。补齐缺失恢复行为，使用 requestId/taskId/runId 关联日志，确认日志不含原文、提示词、密钥及认证信息。
- [ ] 增加用户范围用量展示；没有供应商用量则显示未知，成本估算显示价格配置日期。生产 Cookie、CSRF、内部服务认证及端口暴露由集成测试验证。
- [ ] 运行 `./scripts/verify.ps1`，通过后再在干净 Compose 数据卷运行 smoke；销毁测试卷前核对 Compose 项目名和卷清单，只删除本项目的测试资源。
- [ ] 写部署、备份恢复、配置、示例账户创建说明及演示脚本。演示串联上传、引用、报告、失败恢复；面试材料引用实际测试和评测数字，不编造吞吐与质量。
- [ ] 完成代码审阅和修复，执行适用的回归及 `git diff --check`；提交 `docs: ship verified ResearchDesk demo and deployment guide`。公网发布或远程推送按用户授权单独处理。

## 覆盖复核与停止条件

| 规格范围 | 对应任务 |
| --- | --- |
| 技术基线、服务边界、契约 | 1 |
| 身份、项目、权限、CSRF | 2、7、12 |
| 上传限制、异步解析、队列恢复 | 3、4、12 |
| 原文定位、删除与竞态 | 5、7、12 |
| 检索、引用、多轮问答、流式恢复 | 6、7、8 |
| 报告、人工编辑、版本、导出 | 9、10 |
| 安全渲染、受限处理、敏感日志 | 4、5、9、10、12 |
| 数据集、阈值冻结、基线、真实模型 | 6、11 |
| 可访问性、窄屏和浏览器端到端 | 5、8、9、12 |
| 可观测性、部署及秋招材料 | 12 |

各任务用测试命令和行为断言明确验收；代码片段是约束示例，不表示对应实现已经存在。项目复杂度按四个可运行里程碑控制，每个里程碑结束汇报证据与偏差。

遇到 Docker、运行时、下载或密钥问题先完成不受影响任务，并准确报告受阻验收。真实模型密钥通过本地安全配置提供，不要求用户在聊天中发送。没有真实模型成绩时可以交付明确标注的演示，但不能宣称全项目验收完成。

本计划已按批准规格自查覆盖、术语及状态一致性。尚未创建项目、安装依赖、执行实现测试或提交 Git；下一步是选择执行方式并从任务 1 开始。
