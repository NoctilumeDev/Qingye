# 青野反馈经验模块

独立 Python 模块，使用标准库和 SQLite。默认不启用，不修改四道门合同、业务数据库或模型参数。

## 三个状态

| 状态 | 含义 | 提供给模型 |
| --- | --- | --- |
| PENDING | 反馈已记录，是否出错尚待核实 | 否 |
| APPROVED | 维护者核实了错误及修正经验 | 是 |
| REJECTED | 维护者核实后认定不构成错误 | 否 |

“还没找到”、零条结果、用户离开和重复反馈均不能证明模型出错。没有足够证据时保持 PENDING。用户点击“找到了”只在界面显示收到，不自动审定任何经验。

维护者对照原请求、澄清内容、执行计划、当时返回的授权数据库字段和用户补充，记录核实依据，再决定状态。返回数据只证明该页在当时的读取事实；未读到目标、未收齐分页或用户记错时均不能据此断定模型错误。对于遗漏条件和时间角色等问题，还需核对用户实际需求。没有足够依据就不批准。

HTTP 接口只接受待定反馈和检索已批准经验，不提供审核接口。审核只能由有权访问私有存储的维护者在本地命令行完成。模型及主项目的服务密钥不提供此操作。维护者可以撤回经验至 PENDING，并在下次检索时生效；已经开始的模型调用不被追溯改写。

## 启动

Python 3.10+，无需安装第三方包。数据库含原请求和本人查询结果，应保存在私有目录中，不提交 Git。

```powershell
# 使用自己生成的本机服务密钥；不是厂商 API Key。
$env:QINGYE_FEEDBACK_KEY = '<至少32字符的本机服务密钥>'
python -B tools/feedback-learning/learning.py --db 'D:\PrivateQingye\feedback.sqlite' serve
```

启动青野后端的另一个终端，设置相同服务密钥，以及：

```powershell
$env:QINGYE_FEEDBACK_URL = 'http://127.0.0.1:8097'
$env:QINGYE_FEEDBACK_KEY = '<相同本机服务密钥>'
# 其余数据库、模型和启动配置沿用项目 README。
```

仅支持本机 `127.0.0.1` 服务。密钥应在该安装中独享并保持稳定；更换密钥会更换用户索引，旧经验不会被新索引读取。停用 URL 即可停止反馈保存和经验检索。单次附加请求最多等待 500 毫秒；超时、限额或服务失败不阻断四道门，界面会说明本次反馈未能保存。不自动重试。

## 用户流程

执行查询后展示“这些是你想找的吗？”。“还没找到”只打开补充框；填写后点击“继续帮我找”才提交。取消或离开不产生待定记录。

后端凭实际结果的一次性回执识别对应计划，回执绑定用户、会话和本轮请求，沿用五分钟有效期。前端不能上传替换计划或数据库事实。反馈继续查询时保留原请求和澄清信息，建立新请求，重新经过有限候选、资格校验和范围确认；旧确认不能用于新请求。过期时重新查询。累计原文超过 8000 字符时暂停这条续查路径，提示发起新查询。

只保存已向该用户返回的授权字段和计划、时间、页偏移，不额外读取其他业务数据。加载后页替换该计划的反馈回执；记录说明对应页，不声称保存了完整数据库快照。

服务对同一用户及回执去重，同一用户每小时最多接收 20 条新记录，总记录上限 10000；达到上限只停止反馈采集，不停止业务查询。频率和重复次数都不参与 APPROVED 判定。

## 维护者核实

```powershell
python -B tools/feedback-learning/learning.py --db 'D:\PrivateQingye\feedback.sqlite' counts
python -B tools/feedback-learning/learning.py --db 'D:\PrivateQingye\feedback.sqlite' list --state PENDING
python -B tools/feedback-learning/learning.py --db 'D:\PrivateQingye\feedback.sqlite' inspect '<记录ID>'
python -B tools/feedback-learning/learning.py --db 'D:\PrivateQingye\feedback.sqlite' review '<记录ID>' APPROVED --basis-file '核实依据.txt' --lesson-file '修正经验.txt'
python -B tools/feedback-learning/learning.py --db 'D:\PrivateQingye\feedback.sqlite' review '<记录ID>' REJECTED --basis-file '非错误依据.txt'
python -B tools/feedback-learning/learning.py --db 'D:\PrivateQingye\feedback.sqlite' review '<记录ID>' PENDING --basis-file '撤回依据.txt'
```

依据及经验文件使用 UTF-8。依据写清核对了什么及证据位置；经验写可复用的条件或时间角色区别，不把旧库存、旧地点等当作当前业务事实。`counts` 只计三种记录状态，不给出模型准确率。

## 怎样连接模型

主项目通过 `/experiences` 检索同一用户、同一合同版本下的 APPROVED 经验。Python 用字符二元组余弦相似度选取最多三条，作为历史参考附在本次模型输入中。原始请求单独保留，第三门的原文来源核对仍使用本次用户输入；经验不能凭空补出来源、身份、SQL 或执行能力。系统提示词及模型参数不因反馈自动修改。

这是一种检索辅助，不更新模型权重。PENDING 和 REJECTED 不参与检索、特征匹配或经验排序。学习模块不能核实数据库是否存在用户真正想找的对象，审核依据由维护者提供。长尾问题可以保持未决，本模块不负责证明任意自然语言理解正确。

## 验证

```powershell
python -B -m unittest discover -s tools/feedback-learning -v
```

Java 与客户端测试随主项目日常检查运行；Python 测试可单独运行，也已接入 CI 的客户端检查任务。测试中的审核、事实和模型输出使用标明的夹具，不代表已改善真实模型的业务成功率。
