# 青野仓库治理与资格边界

本轮统一的是仓库护栏的判断原则，不是业务结构。青野仍独立构建和测试；验迹不是业务运行依赖。
终点是保护与检查实际生效，能拒绝明确不合格的变更，并保留风险与失败证据。
打包、创建 Release、发行流水线和生产部署资格不属于本轮任务，也不是治理收口的前置条件。

2026-10-23 开始第一批治理：主线保护、客户端检查的否决能力、CI 失败报告留存。
不修改业务源码、助手能力合同、已确认的展示图，也不重新运行付费模型实验。
本批不是全仓治理完成，更不构成 Release 或正式部署资格。

第二批从已合并的 `298b1ad87e8a4a9496d9f1cdae2b61e4ceeeba71` 开始，
对应 [PR #3](https://github.com/NoctilumeDev/Qingye/pull/3)。该 main 的
[CI 39928351466](https://github.com/NoctilumeDev/Qingye/actions/runs/39928351466)三个 job 均成功。
本批候选增加有限的字节码架构约束、覆盖率采集与报告校验、PR 依赖增量审查及贡献入口；
不把单体改造成新的分层架构，也不据此取得发布资格。

后续 [PR #4](https://github.com/NoctilumeDev/Qingye/pull/4) 已将第二批合入主线；
[PR #5](https://github.com/NoctilumeDev/Qingye/pull/5) 在 `b12f1de60a863f5ee15fb0affa2349e1bebdf670`
补入安全策略。PR #6 随后接入静态变更门禁与既有风险账册，合并到
`21d2fddc1d6937f86dd98bd388a806d08847d497`。
本批在该坐标继续补[持续依赖、敏感信息与覆盖盲区](governance/continuous-checks.md)，不扩大安全调查或业务范围。

## 准入坐标

当前治理从 `3ed4f4842baebe6ce818c752320475eadd3f06b9` 开始。
该 main 的 [CI 39924297925](https://github.com/NoctilumeDev/Qingye/actions/runs/39924297925)
三个 job 均成功；日志中的 H2 与 MySQL 各 69 项，客户端 43 项，维护脚本 4 项。
这是治理前坐标，不能代替后续候选的复验。

准入时，主分支保护接口返回 `404 Branch not protected`，适用 rules 接口返回 `[]`。
三项 CI 能运行，但此前没有配置成强制合并条件。

历史工程证据仍属于 [ORIGIN.md](../ORIGIN.md) 中的来源坐标。
特别是 [能力合同 v2 的语义实验](semantic-repair-20261008.md)绑定 dome 的
`22dbf13dfd69a32a27d4c8f097bae6c83d530780`，不是当前青野代码的语义质量证明。
历史失败、修复后的样本观察、当前确定性测试分别保留，不相互覆盖。

## 主线合并要求

[保护配置](../.github/main-protection.json)记录最低要求；文件存在不代表远端已启用。

- 修改通过 PR 进入 main，必需检查基于最新主线，讨论必须解决。
- 必需检查为 `Qingye · client and hygiene`、`Qingye · H2`、`Qingye · MySQL`，均绑定 GitHub Actions。
- 禁止强推与删除 main，管理员同样受保护约束，不配置 PR 绕过名单。
- 单人维护暂不要求第二个账号批准；这不提供独立审查资格。

2026-10-23 已向 GitHub 应用上述保护，并由独立 GET 读回。
这项记录只证明当时的设置状态，不声称已尝试非法推送或证明管理员永远无法修改设置。
后续阶段退出需重新读取，不能沿用本次结论。

```powershell
python -B scripts/check-branch-protection.py
# 仅验证保存的 API 响应，不宣称当前远端状态：
python -B scripts/check-branch-protection.py --snapshot <response.json>
```

脚本只读，不修改设置。不受保护、权限不足、网络不可达、响应不完整均以非零退出；
缺少任一必需检查、检查来源未绑定或放开强推等配置均不能通过。
普通 PR 的 hygiene 会运行该读回逻辑的负例测试，不用 fork 的低权限令牌冒充实时设置检查。

## 检查的职责

| 检查 | 实际执行与否决范围 | 不由它证明 |
| --- | --- | --- |
| client and hygiene | 当前文档链接、已知跟踪残留、维护脚本反例、十页原生结构及 JS 页面逻辑测试；PR 增加下述依赖审查 | 微信原生编译、真机交互、后端数据库正确性或完整供应链审计 |
| H2 | 临时内存库上的业务、助手有限计划、权限撤销、容量等回归；架构约束、覆盖率报告校验与生产字节码静态变更门禁；子进程不继承 MySQL 测试变量 | MySQL 真实锁语义、真实供应商理解能力、全部告警已解决 |
| MySQL | CI 独占 MySQL 8.0.44、专用 qingye_test 与库内测试账号，执行真实数据库回归及相同质量检查 | 学校部署、生产容量、跨节点确认或正式微信登录 |

助手的合法 JSON、会话权限、实际查询和自然语言理解是不同结论。
现有 [AssistantDecisionTest](../backend/src/test/java/cn/qingye/AssistantDecisionTest.java)
保留“语义错误但能力合法”的对照；不能将确定性门禁全绿解释为模型永远理解正确。

### 有限架构约束

[ArchitectureBoundaryTest](../backend/src/test/java/cn/qingye/ArchitectureBoundaryTest.java)
通过 ArchUnit 读取当前编译后的生产字节码，约束三条已经存在的边界：

- `model` 只依赖本包与 Java 基础值类型，不依赖 Spring、数据库或业务执行层。
- `LlmPlanner` 不直接依赖业务执行、API、仓库数据库包、JDBC 或 Spring JDBC。
- `CapacityScan` 只依赖模型、业务异常 `Problem` 与 Java 基础类型。

同一组规则还检查故意包含数据库或框架依赖的测试夹具，必须实际产生违规；生产扫描必须包含目标类且排除测试夹具。
这些是有限的直接依赖约束，不证明反射、间接调用、自然语言语义或全部权限路径。
现有业务层使用 API 表单、数据库封装使用业务异常的依赖原样保留，不宣称整个单体无环或已经满足新的通用分层模板。

### 覆盖率：先测量，不伪造门槛

H2 与 MySQL 各自执行 `clean test`，由 JaCoCo 生成执行数据和 XML/HTML/CSV 报告。
[统一检查入口](../scripts/check.py)要求报告包含执行会话，类清单与本次生产编译输出匹配，
行与分支计数有效且分母非零。缺报告、漏类、重复或非法计数均拒绝；
[报告反例](../scripts/tests/test_coverage_report.py)同时保留零覆盖率可被如实测量的对照。

本批没有设置百分比阈值，也没有排除未覆盖的生产代码来提高数字。
当前百分比以对应运行的工件为准，H2 与 MySQL 不混成一份结果；不含小程序覆盖率、微信原生编译或真实模型实验。
报告有效只说明本轮测量可读取，不证明测试充分或全部业务正确。
后续先审查未覆盖路径的风险，再决定需要哪些测试与门槛，不直接照搬其他仓库的数值。

### 依赖审查与剩余盲区

2026-10-23 的初始读取显示依赖告警关闭，依赖图比较接口返回 403，SBOM 接口返回 404。
本批启用依赖图与漏洞告警后，告警设置 GET 返回 204，SBOM 已可读取；自动安全更新仍关闭。
这是仓库设置的当次读回，不是漏洞数量或安全性的永久承诺。

PR 在现有必需的 `client and hygiene` job 内运行固定提交的 Dependency Review Action，
对 GitHub 依赖图识别到的变更检查已知公告，新增 high / critical 漏洞拒绝；
范围包含运行、开发与未知作用域，不使用 `warn-only`、忽略失败或公告豁免。
该增量检查只在 PR 执行；main 的该 job 不重新执行 PR 比较，不应把两种事件的通过范围混同。

当前图能看到 Maven 清单与 Actions，但部分由 Spring Boot 父 POM 管理的依赖没有解析版本。
因此本批没有证明完整解析后的传递依赖与构建插件均已审计；依赖图未发现公告不等于不存在漏洞。
许可证识别或未知许可警告也不代替依赖与素材的分发义务核查。
完整依赖基线已经产生 31 条公告候选，原分诊与未知条件见[风险记录](governance/risk-register.md)。
本批补入持续完整解析依赖检查与许可声明清单，实际范围和未决来源见[有限核查记录](governance/continuous-checks.md)。
不借接入检查自动升级业务依赖；工具未覆盖的构建插件依赖也不记为已审计。

### 静态变更门禁与风险责任

生产字节码每次重新编译并分析，报告需覆盖全部编译类且无分析错误。
新增、变化或未经解释消失的检测项会否决检查；历史 38 条未知候选仍显示为 `UNRESOLVED`，
不能把“与历史检测结果一致”理解为“全部风险已排除”。规则、反例与账册维护见[专门说明](governance/risk-register.md)。

风险账册由本仓库维护角色负责，保留来源、状态和下一步。治理收口不删除风险，
未知风险也不自动接管整个三仓治理计划；确认缺陷正常修复，独立安全问题按其证据继续处置。

## 首败与修复

治理前的 `scripts/check-client.py` 用 Python `assert` 检查必需页面文件、原生标签和编译输出。
Python 优化模式会移除这些断言。2026-10-23 在上述准入坐标的检查器上，以临时独立夹具运行
[进程级反例](../scripts/tests/test_client_checks.py)，先获得下列失败，再修改检查器。

| 夹具 | 普通 Python | `python -O` 下的旧检查器 | 修复后 |
| --- | --- | --- | --- |
| app.json 声明页面，但四个页面文件均缺失 | 拒绝 | 退出 0，误报 `checks passed: 1 pages` | 两种模式均拒绝 |
| 页面包含不支持的 `not-native` 标签 | 拒绝 | 退出 0，误报 `checks passed: 1 pages` | 两种模式均拒绝 |
| 完整且合法的原生页面 | 通过 | 通过 | 两种模式均通过 |

首次进程级反例运行结果为 `Ran 3 tests`、`FAILED (failures=2)`；两个失败均来自 optimized=True。
原始本地输出保存在本轮工作树 `test-results/governance/client-check-first-failure.txt`，未覆盖；
此处保留公开最小见证，旧检查器仍可从准入 SHA 取回，夹具不含私人业务数据。
修复将断言换为显式拒绝，不改允许的标签或业务页面。编译输出为空同样显式拒绝；
本次未安装或执行微信编译器，不据此新增原生编译结论。

```powershell
python -B -m unittest discover -s scripts/tests -v
python -O -B scripts/check-client.py
python -B scripts/check.py quick
```

## 证据留存与本轮收口

H2 与 MySQL 的 CI job 无论成功或失败，都尝试上传 Surefire XML/TXT、JaCoCo 执行数据与覆盖率报告；工件名绑定 run ID、attempt 和数据库类型，
不让复跑覆盖上一轮。最长保留 90 天；这是 CI 诊断留存，不是永久发布归档。
测试尚未开始或被超时终止时可能没有报告，缺失不能记作 PASS。
当前阶段的关键首败与最终见证应在到期前保存必要的脱敏记录与固定源码坐标，不依赖创建 Release。
静态报告同样按 run 与 attempt 留存。不上传环境变量、真实凭据与私人数据。

### 项目许可

准入时，青野与来源 dome 均未查到项目级许可证。2026-10-23，作者明确选择 Apache-2.0；
本治理候选据此新增根目录 [LICENSE](../LICENSE) 与 [NOTICE](../NOTICE)。
已有 [Remix Icon 许可](../miniprogram/assets/icons/LICENSE.txt)和[修改声明](../miniprogram/assets/icons/NOTICE.txt)原样保留，
第三方依赖与素材不因项目许可选择而重新授权。本次不改历史提交、tag 或 Release 资产。
项目许可已明确，不代表依赖许可核查、发布资产包含许可文件或交付资格已经完成。

### 安全报告渠道

[安全策略](../SECURITY.md)已合并。私密漏洞报告配置已通过管理员 API 与公开 Security 入口核实，
维护者已核对 Watch 与安全通知、邮件偏好。真实事件的通知投递仍为 `DELIVERY_NOT_VERIFIED`：
没有制造假漏洞报告，也不以缺少真实投递事件阻断本轮治理。配置核实不等于投递已验证。

### 本批治理收口

1. 完整解析依赖检查接入现有 H2 必需 job，许可声明与来源未决项独立保留；不把历史扫描当成持续检查。
2. 敏感信息有限特征随 hygiene 执行，覆盖盲区已有范围记录并补认证反例；不追求任意百分比或告警清零。
3. 对确认缺陷修复回归；未确定事项保留所有者、证据和状态，不通过本次治理接受风险。
4. 对候选执行实际检查、保留首败，并在阶段退出重新读回远端保护与当前源码坐标。

本轮不要求安装包、发布脚本、Release、发布资产或正式部署认证。
历史发布与实验记录继续保留，但不成为新版本的资格替身。
青野护栏完成后即可推进归鸿、期序；不等待全部外部安全问题被研究到底。
