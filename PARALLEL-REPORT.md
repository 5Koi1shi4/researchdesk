# ResearchDesk Tasks 7 + 8 — parallel generation report

日期：2026-10-12（Asia/Shanghai）。工作目录：E:\test\work\researchdesk-parallel\generation；分支：parallel/generation。只修改此独立克隆，未修改主仓库/其他聊天目录，未远程推送或合并主仓库。

## 提交与集成顺序

- 共同基础：474b9e5070ce94c63b925ff72d06948f087a3f8a。
- 导入检索里程碑：原始2297b1e674f0cecd529d3188c4ef2809fb473e6a，本克隆cherry-pick为c851d9a。仅导入已提交内容，未复制未提交的检索实现。
- Tasks7/8实现：de8c5fc35ac9aedc06b1cf28c003e2ecc4b50afe，`feat: generate and resume evidence-grounded research conversations`。
- 本报告随后独立提交；最终报告提交SHA见本聊天最终回复/分支HEAD。

集成仓库如果已经导入原始检索提交，不要再重复应用c851d9a；在对应检索基础上应用本实现提交即可。V006属于导入依赖；本模块仅新增V007。

## 实现范围

- Java拥有POST/GET `/api/runs`、取消、SSE订阅、完整快照、会话查询和最终完成持久化。创建时验证所有者/READY资料并冻结范围；同一用户通过行锁限制最多两个活动运行。历史取最近六轮完成记录，最多12条消息，以8000 UTF-8内容字节保守裁剪。
- 快照与事件在同一事务提交；序号递增，终态不回退。Last-Event-ID补读，24小时窗口过期返回410 EVENT_WINDOW_EXPIRED；错误响应显式JSON。每个事件立即提交而非延迟批写，重放按256条批读；没有吞吐量验收声明。
- 完成前锁定并复验来源；取消后迟到完成不能覆盖终态。删除后的快照/事件引用标记DELETED并移除excerpt。过期RUNNING失败且不重调用模型；本次没有执行真实杀进程重启旅程。
- Python调用唯一的 `retrieval.retrieve` 接口。无证据不调用供应商；提示将资料/历史作为不可信数据；只接受本次证据的稳定 `[[cite:<chunk_id>]]` 标记。伪造或缺失引用不能成为完成答案。
- 明确DEMO模式与真实兼容API适配器；供应商密钥仅在服务器环境。适配器拒绝重定向、防止认证头转发，区分连接限制与总截止时间，独立计时器中断持续零碎数据的阻塞读。失败不透明重试；上游错误脱敏。
- 前端提供三栏工作台、资料选择、流式草稿/验证状态、键盘引用、取消反馈、最近12轮持久会话记录、刷新快照与有界重连。重复事件去重，缺口补读，恢复不POST新运行；旧恢复/取消响应不会覆盖新运行。
- OpenAPI新增运行/会话端点与RunSnapshot，TS/Python类型已重新生成。标准生成器输入文件名已统一，类型再生成检查无漂移。

## 验证证据

所有服务验收仅使用Compose项目researchdesk-generation、127.0.0.1:18083和独立卷。Java主机JDK25编译目标21，容器Java21；Node22.17；Python使用主仓库现有venv只读执行本克隆路径，AI容器Python3.12。没有配置或运行真实模型。

| 命令/阶段 | 实际结果 |
| --- | --- |
| `scripts/test-generation.ps1 -Phase module -Python E:\test\work\researchdesk\apps\ai\.venv\Scripts\python.exe` | 退出0：Python生成/共享契约60项、前端37项、Java生成/共享契约12项；TS契约检查和生产构建通过 |
| Maven `verify -Dit.test=GenerationScopeIT,RunRecoveryIT` | 8项通过，真实HTTP/PostgreSQL：跨用户实际会话拒绝、运行读/订阅/取消隔离、额度、SSE补读/410/JSON错误、过期尝试不重生成 |
| Maven `verify -Dit.test=GenerationRaceIT` | 9项通过，包含2项新的受控流/实际数据库竞态及7项继承恢复用例；取消后晚到完成、来源删除后完成均未写COMPLETED |
| `scripts/test-generation.ps1 -Phase browser`，最终正常构建 | 退出0：真实Edge上传、实际worker/检索、演示回答、断网恢复无新POST、键盘引用、刷新、持续会话历史、续问/取消、390px无横向溢出、登出清理 |
| Java完整单元回归单独复跑 | 14项通过，退出0 |
| Python完整非集成回归曾运行 | 79项通过、10项integration未执行；后续完整入口复跑出现下面的既有解析时限失败 |
| 契约重新生成、`git diff --cached --check` | 通过；Python初次检查仅因输入文件名元数据不同产生漂移，已改为标准openapi.json，类型无变化 |

红灯记录包括缺失运行端点/研究入口、UTF-8历史预算、慢首token、重定向认证外发、持续零碎字节超出截止时间、SSE错误内容协商及同项目迟到响应竞态。未用跳过断言或放宽资源限制获得成功。

完整 `-Phase unit` 最近没有统一绿灯：一次既有PdfResourceTest耗时19030ms超过15000ms断言；单独Java复跑通过。再次顺序入口在既有Markdown解析子进程10秒时限触发PARSER_RESOURCE_LIMIT，结果78 passed/1 failed/10 deselected。没有修改这些基础模块或延长限额；请由delivery在受控负载环境复核。因此本报告仅确认本模块验收，不能据此宣称全项目验收完成。现有Starlette测试客户端弃用提示及Commons Logging提示也已保留。

日志位于本克隆 `.cache/generation/`：module-final.log、java-http-final.log、java-race.log、browser-accepted.log、all-final.log、all-final-sequential.log、java-final-repeat.log。截图desktop.png/mobile.png位于 `.cache/generation/browser/`，已实际检查桌面和窄屏。它们是本地验收产物，未作为源码提交。

## 独立审查

首次独立审查发现慢流五秒超时、暂时订阅错误无法恢复、历史不可见；全部修复。复核发现持续零碎数据拖延截止时间、同项目迟到恢复/取消覆盖新运行；增加失败回归后修复。最后独立复核确认两项解决且无该范围的具体新回归，并重新运行截止时间回归通过。

## 尚未满足的集成依赖与风险

1. sources模块的SourceViewer/可用性API尚未导入。ResearchPage公开 `renderSource(citation)` seam；当前展示有明确说明的引用快照，完整原文预览与跨页面即时删除刷新由集成接入。
2. reports模块需提供 `generation.report.generate_report(...)` 和Spring `ReportDraftSink.saveDraft(runId,projectId,markdown,citations)`。后者在最终来源复验事务内执行，必须按runId幂等创建新草稿。缺少Python报告模块时返回REPORT_NOT_AVAILABLE；缺少Java sink时没有可编辑报告实体。未宣称报告端到端通过。
3. delivery需将GENERATION_MODE一致传入Java/Python，真实base URL/model/key只给Python，并配置AI_BASE_URL、最终代理服务地址刷新与安全生产环境。开发MinIO镜像整改仍属于delivery，未声称生产就绪。
4. 用户明确要求项目完成后才配置真实服务；真实模型、保留集质量与真实供应商计费/取消效果评测全部延期，不能以确定性演示或本地HTTP协议测试代替。阻塞读会在剩余截止时间被中断；取消后供应商仍可能计费。
5. 上述基础解析/PDF资源时限波动和真实worker杀进程恢复仍待最终集成验收。未实施主项目容器停止、删除卷或Docker清理。

正常演示环境已恢复并保留用于审查：API readiness为UP；内部故障端口8001已关闭（connect_ex=111）。后续以 `docs/generation-integration.md` 为跨模块接口依据。
