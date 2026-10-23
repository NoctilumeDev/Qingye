# 青野仓库治理与资格边界

本轮统一的是资格判断原则，不是业务结构。青野仍独立构建、测试和交付；验迹不是业务运行依赖。

2026-10-23 开始第一批治理：主线保护、客户端检查的否决能力、CI 失败报告留存。
不修改业务源码、助手能力合同、已确认的展示图，也不重新运行付费模型实验。
本批不是全仓治理完成，更不构成 Release 或正式部署资格。

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
| client and hygiene | 当前文档链接、已知跟踪残留、维护脚本反例、十页原生结构及 JS 页面逻辑测试 | 微信原生编译、真机交互或后端数据库正确性 |
| H2 | 临时内存库上的业务、助手有限计划、权限撤销、容量等回归；子进程不继承 MySQL 测试变量 | MySQL 真实锁语义、真实供应商理解能力 |
| MySQL | CI 独占 MySQL 8.0.44、专用 qingye_test 与库内测试账号，执行真实数据库回归 | 学校部署、生产容量、跨节点确认或正式微信登录 |

助手的合法 JSON、会话权限、实际查询和自然语言理解是不同结论。
现有 [AssistantDecisionTest](../backend/src/test/java/cn/qingye/AssistantDecisionTest.java)
保留“语义错误但能力合法”的对照；不能将确定性门禁全绿解释为模型永远理解正确。

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

## 证据留存与后续交付

H2 与 MySQL 的 CI job 无论成功或失败，都尝试上传 Surefire XML/TXT；工件名绑定 run ID、attempt 和数据库类型，
不让复跑覆盖上一轮。最长保留 90 天；这是 CI 诊断留存，不是永久发布归档。
测试尚未开始或被超时终止时可能没有报告，缺失不能记作 PASS。
当前阶段的关键首败与最终见证应在到期前绑定到交付记录或发布资产，不上传环境变量、真实凭据与私人数据。

### 项目许可

准入时，青野与来源 dome 均未查到项目级许可证。2026-10-23，作者明确选择 Apache-2.0；
本治理候选据此新增根目录 [LICENSE](../LICENSE) 与 [NOTICE](../NOTICE)。
已有 [Remix Icon 许可](../miniprogram/assets/icons/LICENSE.txt)和[修改声明](../miniprogram/assets/icons/NOTICE.txt)原样保留，
第三方依赖与素材不因项目许可选择而重新授权。本次不改历史提交、tag 或 Release 资产。
项目许可已明确，不代表依赖许可核查、发布资产包含许可文件或交付资格已经完成。

后续仍需独立完成：

1. 静态分析、架构约束、依赖检查和覆盖率的基线核查；不直接复制其他仓库的阈值。
2. 发布脚本、CHANGELOG、源码 SHA、资产清单和校验和，以及干净环境复验与公开读回。
3. 依赖与素材的许可核查，以及发布资产中的 LICENSE / NOTICE 收录；不能仅凭根目录许可存在就宣称分发义务全部履行。
4. 贡献与安全维护说明；保留微信编译、真机、真实模型等独立证据边界。

只有当前交付坐标实际履行所声明的义务，才能冻结该版本的交付基线。
PR 合并、历史 PASS、文档齐全都不能代替这一步；发布后缺陷通过新版本修复并重新验证。
