# Task 12 — 并行交付报告

日期：2026-10-12（Asia/Shanghai）。独立部署、安全与基础恢复硬化已验证；完整应用验收仍等待功能集成，真实供应商执行按用户要求延后。

## 范围与提交

- 唯一修改目录：`E:\test\work\researchdesk-parallel\delivery`，分支 `parallel/delivery`。
- 共同基础：`474b9e5070ce94c63b925ff72d06948f087a3f8a`。
- 实现提交：`94a43726d01d6da23f6997ccf2c74a7925f4b5b6` — `feat: harden isolated deployment and recovery checks`。
- 本报告及验收摘要的提交为本文件所在提交，最终 SHA 随聊天交付。集成应取实现提交和报告提交。
- 导入其他并行分支的 SHA：无。未新增 SQL 迁移，V010 保留给后续用量接入。

已交付隔离运行/构建/验证/烟测脚本、CI 单元流程、生产与 TLS Compose 配置、冷备份恢复脚本、官方修复版 MinIO 源码构建、multipart 生命周期验证、敏感日志回归，以及部署/演示/面试说明。保留现有公共 DTO 字段，没有升级共享 npm/Python 锁文件。

后端修复包含配置配额提示、PostgreSQL 就绪查询超时、PDFBox 重复 commons-logging 排除及实际依赖审计。新增请求 ID 的边缘/API/错误关联和任务回调 taskId/attemptId/fence 日志。日志不记录 URL 查询、正文、对象键或认证凭据。空项目 JSON 已由原实现正确返回结构化 400，本分支增加回归保护。

## 实际验证

运行时：Windows PowerShell 7、JDK 25.0.4（编译 release21）、现有 Python3.11.4 测试解释器、容器 Python3.12、锁定的 Playwright1.64.0 与隔离 Edge。Python 解释器/主缓存仅复用读取，构建缓存、轮子、产物和测试数据均位于自己的 clone。

| 命令/检查 | 实际结果 |
| --- | --- |
| `scripts/verify.ps1 -Scope Foundation` | 最终退出0，完整基础流水线通过。 |
| 契约生成/检查 | Python31、Java7 和 TypeScript 编译通过，公共契约无改动。 |
| Java 单元 | 18项通过，0失败/错误/跳过。包含契约、配额、PDF资源、安全Cookie/内部认证/请求与任务日志。 |
| Java 集成 | 19项通过：17项真实Compose业务验收（Upload9、ProjectOwnership6、TaskLease1、UploadLimits1）和2项独立SpringBoot健康检查。实际依赖停启证据另列。 |
| Python 单元 | 56项通过；基础流水线单元统计包含上述 Python 契约测试。 |
| Python 故障集成 | 6项通过：丢失队列、存储中断、持久清理、删除发布保护、精确重试预算、部分索引/旧fence，以及实际SIGKILL worker和Redis停止后的恢复。 |
| 前端 | 10项单元通过，TypeScript/Vite构建通过。 |
| Foundation 浏览器 | 注册、私有项目、上传/真实worker READY、非法类型、CSRF拒绝与真实重试fence2、永久失败、390px窄屏、退出后隐私检查通过。桌面/窄屏截图已人工查看。 |
| Redis/PostgreSQL 故障 | 各自停止时 Java/Python ready503，恢复后ready200；liveness保持UP。 |
| `tests/acceptance/log_redaction.py` | public静态入口及内部AI查询标记未进入四服务日志，真实红→绿。 |
| TLS真实检查 | 本地测试证书完整验证；Secure/HttpOnly/SameSite、HSTS、CSRF403、跨站403、同源写入、requestId一致及日志内容排除通过。使用测试TLS+Secure Cookie配置，未冒充已配置生产凭据。 |
| multipart真实检查 | 官方源码镜像中8秒加速过期：旧分片404 NoSuchUpload，新分片及已完成对象保留。生产配置24h/1h。 |
| 冷备份/恢复 | 真实三卷冷备份和新项目恢复，旧账户登录、READY、原文SHA256、已发布索引、新写入通过。备份27.77秒，恢复到完成验证26.55秒。 |
| `scripts/verify.ps1 -Scope Full` | 正确退出1并明确 FULL ACCEPTANCE PENDING；完整问答/引用/报告/导出浏览器旅程尚不存在，未伪报通过。 |
| Git检查 | 实现提交和最终报告提交前 `git diff --check` 均通过。 |

计数按套件记录，不把流水线重复调用 Maven/契约的次数相加。真实模型、检索质量、吞吐和成本成绩均未运行或宣称。

最后的HealthIT调整仅在测试预期ready503时，临时降低两个健康探针的预期失败日志，并在finally恢复原级别。生产日志及预期ready200的检查不受影响。重新编译后单独运行HealthIT：2项通过、0失败/错误/跳过；未因测试日志调整重复执行整条基础流水线。

最终基础流水线日志为 `.cache/verify-foundation.log`；失败首轮保留在 `.cache/verify-foundation-first.log`。TLS、multipart、恢复结果的无敏感摘要已保存于 `docs/acceptance/*-2026-10-12.json`。截图在 `.cache/task3-browser/screenshots/`。原始日志、备份和证书在忽略的缓存中，不提交凭据或用户内容。

复现需按 `docs/deployment.md` 准备自己的依赖和镜像。当前验收使用新建的 delivery 数据卷及本机已缓存的固定基础镜像；不是“无网络、无缓存的新机器安装”证明。Full 默认失败门槛只用于保护未集成状态，集成时必须提供真实完整旅程。

## MinIO与恢复证据

固定源码提交 `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`，源码 SHA256 `45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841`。本机镜像 `sha256:75dfef14ff71524e798a8bfb7e938a9574e3df739d2d879d9817d9e92d14d026`，实际版本 RELEASE.2025-10-15T17-29-55Z、Go1.24.8。构建保留 AGPL 许可，验证源码和 Go 模块；容器非root、只读根文件系统、无额外能力，私有端口及禁用管理浏览器。细节见 `docs/minio-build.md`。

备份标签 `drill-013a17f84aa2`，记录源码94a4372和实际运行镜像ID，创建于 2026-10-12 14:02:41 Asia/Shanghai。恢复项目 `researchdesk-test-delivery-restore-drill-013a17f84aa2` 使用18086，验证后已停止，数据卷留存供检查。源 delivery 已恢复，ready200。本次时间只描述小型基础测试数据；没有承诺生产RTO/RPO。没有运行旧MinIO卷升级、报告历史或导出文件的恢复验收。

备份核对项目标签、运行镜像与配置标签、归档哈希；只恢复到没有容器/卷的新 test 项目。失败路径测试确认归档失败仍恢复原先运行的服务；不同镜像或已有目标会在写卷前被拒绝。没有执行 broad prune、主项目down或卷删除。

## 审查、失败与修正

独立审查发现并修复 Compose目录基准、Redis误传PostgreSQL参数、HTTP写入前未校验目标、test项目允许并行端口、Java测试目标与脚本不一致、备份只记录标签而非运行镜像等6项P1/P2。两轮只读复审均确认对应修复可接受；最后新增任务日志、URL日志回归及HealthIT调整也通过复审，无剩余P1/P2代码问题。

真实故障实验还发现 `compose start ai` 会自动启动依赖MinIO，从而解除预期存储故障；已实证后使用 `up --no-deps`，并检查MinIO仍exited。Windows默认GBK读取Docker JSON失败，已显式UTF8。首次TLS测试在API重建后未等ready而收到非JSON网关响应，已增加有界就绪等待和实际TLS端口核对，证书验证仍开启。

multipart首轮用上传列表作清理证据失败。固定源码中列表缓存与磁盘清理是两套计时；最终测试直接验证分片NoSuchUpload。列表可能滞后，不将消失的列表条目作为清理保证，也不将8秒测试冒充24小时耐久测试。

一次宿主机打包中的合法200页PDF触发10秒预算。串行针对复核2项通过，随后完整打包和最终流水线通过；安全预算未放宽。资源繁忙时仍可能拒绝合法但耗时的文件，需在目标部署机器复核预算。Java25/Maven/Mockito上游废弃和动态agent警告已记录，不将这些警告当作应用失败或全面静默其他错误。

早期隔离红测试有一次执行失误：拦截子进程前调用旧helper，短暂停止了主 `researchdesk-ai-1`，已立即重启并确认running；重启也触发原migrate依赖。没有主仓库源文件修改、卷删除或手动SQL，未另外审计该依赖的正常启动数据工作。后续所有故障测试先检查项目、保留端口及实际绑定，隔离单元测试不执行Docker。完整经过与纠正保留在 `docs/task12-acceptance.md`，不隐去此事件。

## 尚未满足的集成依赖

1. 整合sources/retrieval/generation/reports的干净提交后，完成注册→上传→问答→引用原文→报告编辑/版本→Markdown/PDF导出的真正Full旅程；当前仅基础浏览器通过。
2. 尚未实现用户级 `UsageService`/用量界面；需接入generation持久化的实际或未知供应商用量，并给成本估算显示价格配置日期。不得把未知值作为零。V010迁移仍保留。
3. 供应商超时/可重试错误、取消与迟到completed、删除与生成完成竞态、流重连、报告版本冲突与安全导出，需要功能集成后的联合验收。parser fence测试不能替代run取消测试。
4. generation的演示标识/真实适配器、retrieval的模型维度与索引兼容、正式评测协议与holdout仍由对应模块提供。真实OpenAI兼容API配置和执行按用户要求在项目完成后进行，不索取密钥。
5. 生产桶受限账号与独立随机凭据需部署者预创建；本次TLS验证没有证明这些IAM权限已生效。公网发布、远程推送、旧存储数据升级均未执行。MinIO上游已归档，已移除指定旧CVE镜像不代表持续维护或完整当前漏洞审计通过。

集成时保留公共契约，重建三端镜像，运行最终Full及报告恢复/真实供应商验收；不要仅凭这份基础交付报告宣称整个项目完成。
