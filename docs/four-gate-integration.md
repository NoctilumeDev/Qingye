# 四道门分支补迁与独立仓整合

独立仓最初从 `dome@dbb32160a811fd06c826a8d9eafbc660a0548219` 提炼，遗漏了尚未合入旧主线的四道门开发分支。
因此独立仓 `188786148e966066eb0935c91415c27272ab4ece` 的助手仍是 v2，而展示图已采用后续设计。
本次以该独立仓提交为整合基线，迁入旧仓开发分支到 `45e7d4ffb4f5e1525a7b18d8b4d6e9cbc05f2f48` 的八个 Qingye 提交。
只读旧仓；旧仓的 main、开发分支、PR 和工作区不变。

## 提交对应关系

通过路径限定的补丁保留作者和提交顺序，只移除 `qingye/` 前缀；不是整目录覆盖。

| dome 来源 | 独立仓迁入 | 内容 |
| --- | --- | --- |
| `15a3330` | `ce1e6d1` | 确认后的条件查询与详情 |
| `0dfb770` | `56e1121` | 共用合同与有界澄清 |
| `4698095` | `189cdac` | 澄清草稿和时间选项 |
| `a45ef90` | `eac5ef2` | 保留验证与语义残差 |
| `88bc5ed` | `c745d4e` | 确认前检查缺失条件 |
| `e0e69f7` | `26c6f3c` | 混合草稿与模型等待上限 |
| `84f3008` | `f3e9e2d` | 业务优先架构与前端组件 |
| `45e7d4f` | `1e97868` | 研究边界留在本项目 |

独立仓既有的 Apache-2.0 许可、主线保护、CI、RabbitMQ 升级、权限回归、架构约束、覆盖率、静态与依赖门禁继续保留。

## 整合适配

- 首轮运行触发原有 `ArchitectureBoundaryTest.modelContractsStayIndependent`：迁入的两个合同类直接依赖 Jackson 和资源 IO。保留分层规则，以同一 JSON 生成不可变 Java 值；前端词表与模型元数据仍由原合同导出。测试逐项比较两份 JSON 与 Java 值，验证嵌套值不能被改写。不是放宽门禁或增加本地语义解析器。
- 迁入的客户端检查新增了一处 Python `assert`。改为显式异常，并加入普通与优化模式下原生页面按钮违规都必须被拒绝的反例；保留原有其他负例。
- README 合并业务改造说明与独立仓治理说明。HTML 图和 PNG 预览同步：初筛不保证完整语义判定，模型候选字段与服务器计划分开，没有通用 `allowExpand` 开关。
- 来源分支的后端、前端、测试、演示数据、SQL、图片和脚本均纳入补迁范围。依赖版本及 RabbitMQ 维护证据不被旧分支覆盖。

## 图与源码对应

| 图中职责 | 当前实现与边界 |
| --- | --- |
| 第一门 | `AuthFilter` / `Forms` 校验身份与输入，`AssistantService.ask` 接受有限完整入口；陌生名称不由关键词表拒绝，初筛不是最终授权 |
| 第二门 | `AssistantContract.entryCommand` 在模型调用之前处理完整入口；`query` 接受结构化选择；开放语言由 `LlmPlanner.locate` 提出候选，澄清最多五次，可提前暂停 |
| 第三门 | `AssistantHintDecoder` 严格解码；`AssistantContract.directoryQuery` 编译；`QueryPlan` / `AssistantQualification` 校验有限能力，主体来自会话 |
| 第四门 | `AssistantConfirmations` 绑定同一计划、主体和会话；`AssistantService.execute` 调用 `AssistantSql` 等固定查询，输出授权字段和分页 |

模型候选字段：`action, intent, search, reason, selections, question`；每个 selection 为 `field, value, source`。
服务器计划字段：`action, intent, entity, category, timeOption, date, status, location, reason, queries`。
大集合是完整授权对象、既有目录与同目标相关候选的业务策略；不是由模型自由扩大查询范围，也不能恢复模型已经遗漏的硬条件。

## 验证记录

本地首败保留在 `test-results/integration/quick-first.log` 与 `architecture-first-failure.xml`；后续测试代码泛型重载编译失败保留在 `quick-second.log`。
修复后 H2 测试已通过，具体最终计数和远端检查在本次 PR 关账时补入。
独立仓的静态、依赖、MySQL 和保护状态仍须对整合版本核对，历史通过记录不替代当前检查。

本轮不新增付费模型采集。来源分支 372 次调用的 250 次预期查询、94 次正确停止、28 次偏差仍是原窗口观察，不能称为整合版本重新达到的成绩。
真实微信登录、真机、部署和任意自然语言语义正确性不因本次迁入获得资格。

### 静态与依赖变更复核

静态首轮发现八个新增检测项和两项历史检测消失，原始报告保留在 `test-results/integration/static-first.xml`。
新增项中，两组列表引用暴露改为构造时快照，保留 null 交给原 Bean Validation 拒绝；两个不可变合同出口显式返回只读视图，并用嵌套修改反例验证；另两项是已返回分支后的无效条件和未使用局部变量，已删除。
历史 `spotbugs-027/028` 对应旧 `LlmPlanner.plan` 的宽泛捕获位置；该方法被迁入的 `locate` 替代，协议解码在运输异常捕获外执行。以新源码、协议错误/服务失败回归及重新扫描核对，不以检测消失直接宣称所有风险解决。

依赖版本和扫描器未变。变动引用输入是 `Api`、`Forms`、`ExternalHttp`、`LlmPlanner`：
新路由仍返回数据而非 SSE/XSLT 视图；新表单是有限 String 与定型记录列表；外部返回仍按 String 解码。
严格模型解析已移到 `AssistantHintDecoder`，目标为 String 与有限 Selection 列表，未引入多态、类型 ID、视图、忽略字段、InetAddress、Path、XML 或浮点/BigDecimal 类型绑定。
复核沿用账册中各公告的既有前提，更新具体源码证据和引用哈希，不改变原有 NOT_APPLICABLE / UNRESOLVED 状态，不声明全面依赖安全。

按来源分支的 85 个路径逐个核对：73 项内容/删除状态保持一致；11 项因上述治理、不可变值或文档适配而有解释过的差异；1 张旧架构图由独立仓同主题 HTML 生成的当前 PNG 替代，消费者已更新。前端业务文件与源分支一致，未遗漏整组页面。

本地最终复验曾错误地并行启动共享 `backend/target` 的 H2 clean 与依赖扫描，导致 clean 删除失败及扫描配置缺失。
这是本次检查编排失败，保留 `h2-final.log` / `dependencies-final.log`，随后按 H2 → 静态 → 依赖顺序串行重跑，不计作产品通过或产品缺陷。
