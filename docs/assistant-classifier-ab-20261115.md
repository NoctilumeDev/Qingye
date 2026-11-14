# 第二门多目标分类 A/B 实验

本实验只回答一个问题：青野是否只需要多项分类容器，就能可靠表达一个请求中的当前有效目标；或者逐项迭代分类是否明显更可靠。

实验不启动青野后端，不连接数据库，不进入第三、第四门，也不产生查询授权。生产 `LlmPlanner`、助手服务、提示词和评分记录均不修改。

## 固定变量

- 题库：既有 391 个任务，原始字节 SHA-256 为 `291998f96d1855c97b8ef51d92c0dd7817d464b0668aef44fa55127ba909ffc0`。
- 单项本体：继续使用 `action / intent / search / reason / selections / question` 六字段对象。
- 子项分类：`LOCATE / CLARIFY / REJECT`。
- 语义正文：A、B 由同一份当前生产提示派生，只替换单项输出限制并附加各自外层格式。
- 模型参数、超时、首轮原始输入 `turns[0]`、业务判据及重复次数在同一轮内保持一致；不自动重试。`userTask` 在部分多轮题中只是任务说明，不作为模型输入。
- 一个原始用户任务只产生一个业务判定。子项结果只用于定位原因，不能拆成多份成绩。

## 唯一实验变量

方案 A 一次返回：

```json
{"control":"DONE","items":[{"action":"LOCATE","intent":"ACTIVITIES","search":null,"reason":null,"selections":[],"question":null}]}
```

方案 B 每次返回一项：

```json
{"control":"NEXT","item":{"action":"LOCATE","intent":"ACTIVITIES","search":null,"reason":null,"selections":[],"question":null}}
```

最多四次 `NEXT`，随后单独返回 `DONE` 或 `OVERFLOW`。`CLARIFY`、`REJECT` 是子项分类，不能代替枚举终止。

## 业务评分

结构解析器只报告协议通过、协议失败或传输故障，不自动宣布语义正确。业务审查按原始任务逐项核对：

- 所有当前有效目标是否完整；
- 改口撤回的旧目标是否消失；
- 明确条件是否保留并归入正确目标；
- 关联、交集、统计和依赖任务是否被错误拆成独立查询；
- 必要未知是否保持 `CLARIFY`，越权是否保持 `REJECT`；
- 超过四项是否明确 `OVERFLOW`，而不是静默截断。

最终判定为“完整分类正确”“合理不确定”“语义分类错误”或“协议／传输故障”。评分依据在读取结果前冻结；旧 PR 的成绩不计入本实验。

## 执行顺序

先运行 [21 题定向试验](assistant-classifier-ab-pilot-20261115.json)，确认协议、证据和评分流程没有串线；再以相同代码运行完整 391 题。每个任务交替 A/B 调用顺序，减少固定先后顺序造成的偏差。

运行示例：

```powershell
$env:QINGYE_EXPERIMENT_DEEPSEEK_KEY = '<临时密钥>'
python -B scripts/assistant-classifier-ab.py D:\ResearchEvidence\qingye-classifier-ab-deepseek-pilot-20261115 `
  --provider deepseek `
  --selection docs/assistant-classifier-ab-pilot-20261115.json `
  --proxy http://127.0.0.1:7897
```

输出目录必须不存在。脚本保存冻结坐标、实验源码哈希、工作树状态哈希、两份完整提示、脱敏网络记录、结构化分类和待审业务表；协议失败同样保留已经收到的原始回包，不覆盖既有实验。
