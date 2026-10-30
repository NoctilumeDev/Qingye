# 风险记录与静态变更门禁

本轮只建设仓库护栏，不打包、不发布，也不为生产部署作安全背书。
风险不能因为任务收口而消失，治理也不以解释完所有风险为终点。

## 当前扫描与历史账册（2026-10-30 核对）

主线 `3b261d2392990291be94567505b2276289fb49ee` 的 [CI 40860881604](https://github.com/NoctilumeDev/Qingye/actions/runs/40860881604) 重新解析 93 个依赖，命中 17 条公告。
按公告 ID 和依赖版本逐项对照账册，这 17 条均为 `NOT_APPLICABLE`：当前核查的调用路径与配置不具备各公告的触发条件。

| 当前匹配依赖 | 公告数 |
| --- | ---: |
| Jackson Core / Databind | 2 / 9 |
| Netty Codec / Handler | 1 / 2 |
| Spring Web MVC | 2 |
| Log4j API | 1 |

扫描器按版本范围匹配，所以仍报告这些公告；应用适用性由账册中的逐条依据说明。相关调用路径、配置或依赖改变时需重新核对。
账册另保留已退役 RabbitMQ、Tomcat 旧版本的历史条目。运行摘要中的 25 条不适用、7 条未解决是历史账册计数，不能当成当前 17 条公告的分类，也不能直接相加。
旧版本的具体修复及正常行为对照见 [RabbitMQ 维护记录](rabbitmq-maintenance-20261023.md)和 [Tomcat 维护记录](tomcat-maintenance-20261029.md)。
静态账册保留 2 个 `REMEDIATED`、36 个 `UNRESOLVED` 候选；后者仍需按具体证据判断，不能作为已确认漏洞数。下文保留原始基线及过程记录。

## 保留的事实

[风险账册](risk-register.json)承接 `f695e1e53ea1c288b12efb9ff192058d4acb80b7` 的扫描与有限分诊，
本次接入基于 `b12f1de60a863f5ee15fb0affa2349e1bebdf670`。后者只增加安全维护政策，
不据此更新前者的运行时安全结论。

| 来源 | 保留状态 | 含义 |
| --- | --- | --- |
| 解析后的依赖扫描，8 个依赖匹配 31 条公告 | 25 条 `NOT_APPLICABLE`，6 条 `UNRESOLVED` | 前者只在各条所列源码及配置条件下不适用；后者未解决，不是已接受 |
| SpotBugs 生产字节码扫描 | 38 个 `UNRESOLVED` 候选 | 工具警告尚未逐项判定为真实缺陷；没有批量豁免 |

本批没有把任何候选改为 `REMEDIATED`，也没有接受剩余风险。
账册中保留原扫描评级、已取得的上游评级、源码位置、理由、未知条件和后续动作。
所有者为 `NoctilumeDev/Qingye` 的仓库维护角色，不新增个人承诺或响应时限。
已确认的缺陷应修复并回归；无法确认的事项继续保持未知，不因为另一项检查通过而关闭。

原始依赖扫描曾因 SBOM 输入格式错误而未执行成功，该失败不能当作“零漏洞”。
成功扫描及原始失败保存在维护者的本地证据存储中；账册记录成功报告的 SHA-256，
不把私人磁盘路径或完整本机环境写入公开仓库。公开账册是可追溯索引，不冒充原始报告的公开镜像。
相关源码可从上述固定提交取得；公告内容可能更新，应区分历史观察与新一轮结果。

RabbitMQ 的六条未知项不再扩展成新的部署设计任务。本批只转存已有证据；
已有版本反证以补充观察保留，未据此擅自关闭条目。
自管 Broker、应用 JSON 校验或队列权限，均不能直接替代底层协议解析风险的核查。
相关依赖、调用路径或配置改变时，由维护者复核影响范围；不要求每个无关 PR 重做威胁研究。

## 门禁实际拦截什么

`python -B scripts/check.py static` 从当前源码重新编译生产类，运行固定版本的
`spotbugs-maven-plugin:4.10.5.0` / SpotBugs `4.10.5`，配置 `Max`、`Low`，不扫描测试夹具。
不使用排除过滤器，不用 `check` 的零告警规则冒充已完成历史分诊。

[检查器](../../scripts/check-static.py)拒绝：

- 扫描命令失败、报告缺失或损坏、扫描器版本变化、分析错误或缺类。
- 报告类清单与本次全部生产编译输出不一致，或报告计数不一致。
- 新候选、候选类型或评级变化，以及未经解释消失的历史候选。
- 已标记 `CONFIRMED` 却未完成修复的事项，或标记已修复但再次出现的事项。
- 缺少所有者、证据、处置理由、后续动作，或试图通过本门禁接受风险的账册。

保留的未知候选会在输出中明确计数。通过只表示本次没有未核对的检测结果变化，
**不表示零缺陷、不表示 38 条候选已安全，也不表示部署风险获准。**
检测器不能发现全部代码变化或全部缺陷；账册的语义判断仍须审查，不能为了绿灯自动重建基线。
`REMEDIATED` 必须补充修复与回归证据；历史条目保留，不以删除代替关闭。

静态编译与报告位于 `backend/target/static-analysis/`，独立于 H2/MySQL 的测试报告。
此入口只执行 `clean compile` 与分析，不执行打包，不启动应用或中间件。
H2 必需 CI job 接入此门禁，无论成功失败都尝试保留报告；客户端 hygiene 校验账册及检查器负例。
[负例测试](../../scripts/tests/test_static_gate.py)同时验证普通 Python 与 `-O`，并验证扫描失败时不会消费旧报告。

```powershell
python -B scripts/check.py static
python -B -m unittest discover -s scripts/tests -v
python -O -B -m unittest discover -s scripts/tests -v
```

历史完整依赖基线使用 CycloneDX Maven Plugin `2.9.3` 解析 SBOM，再由 OSV-Scanner `2.7.0`
以 CycloneDX 输入扫描。重跑必须保存新的源码坐标、原始输出与工具退出码，不覆盖旧报告；
有公告的退出结果与扫描根本未成功应分开记录。
PR #6 当时**没有**接入持续完整依赖扫描，账册校验不能代替新扫描。
后续基于 `21d2fddc1d6937f86dd98bd388a806d08847d497` 的治理增量已补
[持续检查及有限核查](continuous-checks.md)：重新解析、扫描、比对原候选，不修改原分诊状态。
许可来源的未决项与覆盖盲区也在该记录中保留，不将其隐藏为已完成安全结论。

## 变更记录

- 2026-10-29 Tomcat 维护：见[版本迁移与同输入回归](tomcat-maintenance-20261029.md)。
  10.1.55 在严格 SNI 的环回 HTTP/2 夹具中放行缺少 authority/Host 的请求；10.1.61 在进入 servlet 前拒绝，正常请求仍通过。
  原分诊及部署未知条件保留；本轮依赖清单单独登记旧坐标退役和具体修复证据，不接受其余风险。

- 2026-10-29 反馈接口绑定复核：`2d78c65` 的 [CI 40844634280](https://github.com/NoctilumeDev/Qingye/actions/runs/40844634280)
  在 181 项 H2 测试通过后，因 `Api.java` 引用版本改变被依赖门禁拦截。
  复核 `Api.java`、`Forms.java`、`application.yml` 后更新这三个文件的规范化源码散列。
  新反馈 DTO 只有令牌与有界修正文本；控制器继续返回普通 JSON 并使用认证主体，
  没有增加 DataInput、浮点强制转换、视图/身份注解、多态类型、XML、Path 或 InetAddress 输入。
  `AssistantLearning` 仅复制现有 mapper，并将服务端保存的 JSON 解析为 Map；本机服务响应仍走既有有界 HTTP 接收器。
  配置只增加可选反馈服务 URL/密钥，没有开启 SSE、XSLT、容器 FORM/DIGEST、安全约束或 HTTP/2/TLS/SNI。
  涉及的 11 项 Jackson、2 项 Spring MVC、3 项原 Tomcat 条目仍为 `NOT_APPLICABLE`；新增 SNI 条目仍为 `UNRESOLVED`。
  没有变更处置状态、依赖版本或检查器，不以此记录证明新增功能没有其他风险。

- 2026-10-29：核对 `8abb9e4` 解析出的 Tomcat 10.1.55 新公告
  [GHSA-f525-44xv-f2qj](https://github.com/advisories/GHSA-f525-44xv-f2qj)。
  GitHub/OSV 评级为 Critical，[Apache 原公告](https://tomcat.apache.org/security-10.html)评级为 Moderate，均保留。
  公告涉及 HTTP/2 对严格 TLS SNI 校验的绕过；仓库配置没有启用该链路，
  但启动脚本接受外部配置，实际部署的连接器设置尚未核实，故登记为 `UNRESOLVED`、`risk_accepted=false`。
  这次是静态分诊和公告变更登记，没有升级 Tomcat、验证漏洞触发或把它标为已修复。
  原始拦截见 [CI 40838839920](https://github.com/NoctilumeDev/Qingye/actions/runs/40838839920)。
  后续启用 HTTP/2/TLS 部署前，应核实有效配置或升级上游修复版再验证。
  依赖检查器、扫描配置、原有条目及处置规则均未改变；新增一条未决项，不能将门禁通过解释成消除了此风险。

- 2026-10-24 启动配置优先级：见[隔离启动对照](startup-mode-20261024.md)。
  候选让显式 QINGYE_DEMO=false 生效；局域网实际暴露、真实微信登录仍未验证。

- 2026-10-24 外部 HTTP 接收上限：见[有界修复与分诊引用复核](http-response-bound-20261024.md)。
  新增独立正文传输预算；引用该接收器的 11 项依赖分诊前提逐项复核后重新绑定源码，历史状态保持不变。

- 2026-10-23：首次将既有 31 条依赖候选及 38 条静态候选纳入仓库账册，保留原分诊状态。
  接入静态结果变更门禁；不修订业务、助手合同、依赖版本或产品支持范围。
- 首次本地候选因 `spotbugs-022: missing detector location` 被拒绝：导入时将两个告警的主类与相关库类一起写成了数组。
  原扫描 XML 未变；修正为 `primary=true` 的类，增加多类注解回归。普通与优化模式的 29 项维护测试随后通过。
  这是一处账册导入错误，不是新发现的业务缺陷，也没有改变原候选处置状态。

- 2026-10-24 四道门补迁：按 [整合记录](../four-gate-integration.md)复核源码 `2b1061b`。
  两项旧 LlmPlanner 捕获位置随旧方法移除，记为 REMEDIATED 并保留原身份和回归证据；其余 36 项静态候选仍为 UNRESOLVED。
  新报告的八项增量已通过不可变快照/出口和死代码清理消除，没有排除规则或批量加入新基线。
  依赖引用输入变动已按原公告前提复核，追加有限解析器与记录类型证据，原依赖分诊状态不变。


## WeChat account maintenance 20261030

本次以 `ba1cd59927d1ceb2434e45d03760dfc2d6421d9e` 为改动前坐标，核对新增的登录可用性和账号授权 REST 接口。Api.java 的逻辑 UTF-8 SHA-256 为 `c01e78591df94ba03856ab17548711bed9e1dacdedaf13b8c9c185c5525f674d`。接口仍返回数据，未引入 SSE、XSLT、DIGEST、FORM 或 servlet security-constraint；五条引用 Api 的既有依赖适用性判断保持原状态。仅更新该文件的输入摘要，没有放宽依赖门禁。

首次静态检查因构造函数新增 AccountService，报告 spotbugs-001 的身份从 `fb5810a29a9a4e89ad3149f9165b71a7` 变为 `54562539ec38b5149059fb05ed1b98cd`。逐项核对仍是同一个 Api.messages 注入赋值，EI_EXPOSE_REP2、priority 2、rank 18 均未变。账册保留旧身份及说明，只迁移此条身份；继续保持 UNRESOLVED、risk_accepted=false，没有将其记作已修复。首败记录保留在本轮工作证据目录的 wechat-admin-quick-first.log。
