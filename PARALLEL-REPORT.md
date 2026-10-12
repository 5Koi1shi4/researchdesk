# Task 5 — 原文预览、引用定位与删除

状态：本模块已实施、验证并经独立审查，可供主集成聊天审查和合入。跨任务集成与真实模型评测未在此分支冒充完成。

## 分支与提交

- 唯一代码目录：`E:\test\work\researchdesk-parallel\sources`。
- 分支：`parallel/sources`。
- 共同基础：`474b9e5070ce94c63b925ff72d06948f087a3f8a`。
- 功能提交：`9085c97802bea53e795d86fd44f64116f9da8f30` — `feat: preview and delete document sources`。
- 本报告以随后单独的文档提交保存；最终 HEAD SHA 在聊天最终回复给出。
- 导入其他任务提交：无。只读复制主仓库依赖缓存、Python wheels；共享 Python venv 只作解释器/依赖执行，未修改。
- 没有向远程推送，也没有修改主仓库或其他并行克隆。

## 实施范围

- Java 授权代理原文，按所属项目 owner 验证文档/版本；未知与无权访问均返回 404。只允许 READY 资料读取，删除后返回 410。
- 版本到来源的信息接口及最多 200 条历史 Citation 的可用性投影接口；已删除来源返回 `availability=DELETED`、`excerpt=null`，保留快照身份、标题和位置。
- `SourceViewer({citation: Citation})` 导出供研究/报告页面使用。Markdown 以纯文本显示，段落编号与解析器一致，支持可见焦点和键盘定位。
- PDF 以授权的服务端分页 PNG 显示，支持引用页、前后页切换。现有 PDFBox 在受限子 JVM 中渲染，限制时间、并发、堆内存和图像像素，不在浏览器执行 PDF 活动内容。
- 删除先提交 DELETING、撤销索引和有效任务，后通过持久记录异步删除对象与失效向量；物理清理失败保留重试记录。重复删除幂等，UI 清除当前预览并解释历史正文保留。
- 修复已删除 FAILED 任务通过 retry 复活的问题；删除后同内容上传创建新资料/版本，旧引用仍指向已删除版本。
- V005 是本模块唯一新增迁移。受限 typed SECURITY DEFINER 函数负责已失效索引清理，业务账号不直接写检索表。
- OpenAPI、TypeScript/Python 来源类型已更新，公共 Citation/GenerationRequest/RunEvent 未更名。

## 真实验证结果

所有命令在本克隆明确工作目录执行；Java 使用 `C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot`，Node `22.17.1`，Playwright 锁定 `1.64.0` + 独立 Edge 会话。

| 命令/检查 | 观察结果 |
| --- | --- |
| `./scripts/test-api.ps1 -Test DocumentDeletionIT -Integration` | 2026-10-12 13:09:37（Asia/Shanghai）BUILD SUCCESS；10 单元测试 + 4 集成测试，0 failure/error/skip |
| `npm --prefix apps/web test` | 20 项通过：SourceViewer、DocumentPage、已有身份请求竞态 |
| `npm --prefix apps/web run build` | TypeScript + Vite 生产构建通过 |
| `npm --prefix apps/web run test:contracts` | TypeScript 契约检查通过 |
| 共享只读 Python 解释器执行 `pytest apps/ai/tests -m 'not integration' -q -p no:cacheprovider`，PYTHONPATH 指向本克隆 | 41 passed，5 integration deselected；未把这 5 项标为通过 |
| 单独 `pytest apps/ai/tests/test_contracts.py -q -p no:cacheprovider` | 最终契约生成后 31 passed；这些测试已包含于上述非集成集合，不重复计算总数 |
| `npm --prefix apps/web run test:e2e` | 13:14–13:15 完整实际浏览器流程通过：注册/项目/上传/刷新重进/READY/Markdown HTML 惰性文本/键盘焦点与 Escape/取消和确认删除/PDF PNG 解码与第 2 页/390px 窄屏无横向溢出 |
| 空数据库 Flyway history | V001、V002、V003、V004、V005 均成功 |
| `git diff --check`、提交前 `git diff --cached --check` | 通过；仅已有 Windows 换行规范提示 |

集成测试使用真实 PostgreSQL/pgvector、Redis、MinIO、Java 和 Python 解析 worker，不是业务全链路 mock。存储故障用例临时 stop/start 的唯一服务为 `researchdesk-sources` 的 MinIO，并在 finally 恢复。检索可见性以真实索引 active/published 撤销及实际 chunks 清理断言验证；Task 6 排名检索函数本身没有在此分支导入。

### 红灯和修复记录

- 基础版本 source GET 缺失：期待 200，实际 404。
- 删除后的 FAILED task retry：期待 409，实际 202；补 document state guard。
- 删除后相同内容上传：期待 201 + 新 ID，实际 200 + 旧 DELETED 记录；V005 部分唯一索引及查询过滤修复。
- 原文预览焦点：先观察 1 failure / 8 passing，修复后全量 20 项通过；独立审查复核该 P2 已解决。
- PDF 子进程 API 初始缺失属于编译失败，不称为断言级红灯。最终实际渲染极大 page box 仍不超过像素限制，HTTP PNG 可解码。
- 浏览器脚本初始被 Vitest 误收集、Node 直接执行 `.ts`、刷新后项目选择恢复及带编号段落选择器均有失败记录。最终将 unit/e2e 隔离并沿用 `.mjs` 原生入口，修正导航/选择器后完整通过，没有以失败尝试作为成功证据。

详细证据：`docs/task5-backend-verification.md`、`docs/task5-frontend-verification.md`、`docs/task5-review-report.md`。截图已人工查看并提交到 `docs/test-evidence/task5/{markdown,pdf-page-2,mobile}.png`。

## 独立审查

独立审查覆盖规格和代码质量。唯一 P2 为打开预览后立即 Escape 不生效，已通过焦点修复、回归测试和只读复核关闭。审查还复查了 `.mjs` 入口、隔离端口、刷新流程及键盘断言；没有未解决阻断发现。

## 待主集成验证与限制

- 研究/报告页应导入 SourceViewer，并在读取历史引用/导出时应用可用性投影。回答/报告正文按批准设计保留，不能以清空 Citation excerpt 声称已删除正文。
- Task 6 检索同时需要 Java 授权 READY 集合和 SQL active/published 条件。Task 7 最终保存必须在事务中按确定顺序锁定文档并重验状态；在途生成与删除最终完成竞态需要其集成测试。较早的 availability 响应不能代替最终事务检查。
- API 仅支持当前单版本上传模型；SourceViewer 文本段落保留标题原样。PDF 预览是图片，不能选中文字，首版无逐字坐标高亮。
- 已发送给浏览器的内容不能从另一个标签页远程撤回；后续读取会拒绝，来源响应使用 no-store。宿主应在获知删除后立即更新引用状态。
- 真实 OpenAI 兼容服务配置、真实模型质量评测依用户要求延后，本模块未索要密钥、未调用真实供应商、未声称真实模型验收。
- 基础部署安全/MinIO 生产镜像整改由 delivery 任务负责，本报告不声明全项目生产就绪。

接入细节在 `docs/source-integration.md`。DocumentDeletionIT 默认 `source.baseUrl=http://localhost:18081`、`source.composeProject=researchdesk-sources`，可由主集成显式覆盖。浏览器脚本默认且要求隔离端口 18081，避免误操作主实例。

## 环境保留

Compose 项目 `researchdesk-sources`，入口 `127.0.0.1:18081`。最终迁移曾在核实本项目三个临时卷后从空环境重新启动；没有清理任何其他项目资源。测试实例目前保留供审查，数据库/Redis/MinIO/Python 没有公开主机端口。
