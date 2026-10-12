# ResearchDesk

面向私有资料研究的全栈 AI 应用：React / TypeScript 前端、Spring Boot 业务服务、Python AI 服务，配合 PostgreSQL / pgvector、Redis 和私有对象存储。

已集成资料上传与原文预览、带来源的流式问答、可编辑报告、不可变版本、Markdown / 中文 PDF 导出，以及用户范围的用量记录。当前交付使用明确标识的演示模式；完整集成验收进展见 [验收记录](docs/integration-acceptance.md)，不能用单模块测试替代完整验收。

## 功能与边界

- 私有账户和项目：服务端会话、HttpOnly Cookie、CSRF 校验、资源所有权隔离。
- PDF / UTF-8 Markdown 上传：默认 20 MiB、200 页、每项目 50 份；可收紧限制。扫描件 OCR 不在本版范围。
- 可靠解析：PostgreSQL 保存任务与 Outbox，Redis 负责分发；租约、attempt 和 fence 防止旧 worker 发布结果。解析在受限进程中执行。
- 原文与来源：Markdown 段落定位、PDF 分页图片预览；删除立即撤销后续读取和检索，异步清理对象与向量。历史正文保留，删除的引用不再提供摘录。
- 研究问答：选择资料范围、流式草稿、服务端引用校验、取消、断线补读及刷新恢复。浏览器只调用 Java，供应商凭据只在服务端。
- 报告：从一次生成打开对应新草稿；编辑、历史版本、保存冲突与另存副本；按已保存版本导出 Markdown / PDF。
- 用量：每个运行幂等记录，缺失 token 用量显示“未知”；只有匹配模型和完整价格配置时才给出带价格日期的估算，演示结果不是账单。

真实 OpenAI 兼容生成适配器已实现，服务地址、模型和密钥按用户要求在项目完成后配置。**当前嵌入是 32 维 SHA256 确定性演示数据，不具备语义检索质量保证**；真实语义嵌入需要版本化迁移和重新索引，不能只改一个模型环境变量。真实模型、人工质量标注与正式保留集评测尚未执行。

## 本地启动与验收

使用 PowerShell 7、JDK 21–25、Node 22.12+、Python 3.11–3.13、Docker Linux containers。浏览器验收使用已安装的 Microsoft Edge 和锁定的 Playwright。首次安装需要下载依赖、基础镜像与固定 MinIO 源码。

```powershell
# 在仓库根目录执行；将 JAVA_HOME 改为实际安装路径。
$env:JAVA_HOME = 'C:/Program Files/Microsoft/jdk-25.0.4.7-hotspot'
Copy-Item .env.example .env
./scripts/setup.ps1
./scripts/build.ps1 -Project researchdesk-test-integration -Port 18087
. ./scripts/runtime.ps1
Initialize-Runtime -Project researchdesk-test-integration -Port 18087
Invoke-DeliveryCompose up -d --no-build
./scripts/verify.ps1 -Scope Full -Project researchdesk-test-integration -Port 18087
```

打开 `http://127.0.0.1:18087`，注册自己的账户后创建项目；没有共享默认账户。密码至少 12 个字符且不超过 72 个 UTF-8 字节。

`researchdesk-test-integration` 是可重复验收环境，专用入口 18087，测试数据库仅绑定 `127.0.0.1:15487`。完整验收会暂时停止和恢复此环境的依赖、注入故障并执行冷备份恢复，请使用测试资料。日常演示可单独使用 `researchdesk-delivery:18085`，其数据库不开放主机端口，见 [部署说明](docs/deployment.md)。不要混用两个环境的验收参数。

```powershell
./scripts/verify.ps1 -Scope Unit       # 契约、单元、组件和前端构建
./scripts/smoke.ps1 -Scope Full -Project researchdesk-test-integration -Port 18087
./scripts/test-contracts.ps1           # OpenAPI 类型生成与漂移检查
Invoke-DeliveryCompose down            # 停止当前选中的环境，保留数据卷
```

完整验收包括真实 Java / Python / PostgreSQL / Redis / MinIO 链路、浏览器上传到报告导出、所有权隔离、取消与删除竞态、生成超时、worker / 队列恢复，以及报告版本和导出文件恢复。模型采用明确标注的演示适配器及本地故障服务，不把这些测试当成真实模型质量分数。

## 配置与架构

`contracts/openapi.json` 是公共契约来源，TypeScript 与 Pydantic 类型由脚本生成。Java 授权资料范围、控制运行终态和报告事务；Python 只使用冻结的授权范围进行解析、检索与生成。业务数据库和检索数据库使用不同账号权限，迁移账号不交给运行中的服务。

`.env.example` 描述生成模式、资源上限和可选价格配置。价格由服务端按模型匹配，需同时设置输入/输出每百万 token 价格、币种和价格日期。供应商用量缺失时不估算费用。不要把密钥写进前端构建或提交到 Git。

会话默认 24 小时过期；退出使服务端会话失效。账号口令使用 BCrypt，写操作校验 CSRF 和来源；没有邮件验证或密码找回。原文、问题、报告会按用途发送给所配置的模型服务，页面有说明；日志只记录关联 ID、耗时和失败类别，不记录内容或认证信息。

存储使用固定的官方 MinIO 修复版源码构建，并有未完成分片过期检查。该上游已归档；固定一个已知问题不等于持续维护或完整漏洞审计。HTTP 本地验收不代表公网生产部署完成，TLS、独立凭据与受限桶权限见部署文档。

## 交付材料

- [演示流程](docs/demo.md) 与 [面试讲解](docs/interview.md)
- [部署、TLS 与备份恢复](docs/deployment.md)
- [评测协议](evals/protocol.md) 与 [评测边界](docs/evaluation-results.md)
- [各模块原始交付记录](docs/parallel/integration-milestone.md)
- [用量实现](docs/integration-usage.md) 与 [独立集成审查](docs/integration-review.md)

设计和实施计划保存在 `docs/superpowers/`。本文不宣称未经测量的吞吐、费用、语义准确率或生产恢复目标。
