# RabbitMQ 客户端第二轮维护

本轮只维护客户端及验证链，不改变校园业务、数据库、助手合同或部署支持范围，不创建 Release。
基线：`3ca99c8e604625b0f8cd508a9603b65611045444`；依赖及首组测试：`92f17e8b38ea27db6a1b11616cece8212b3c1765`。
完整候选以本 PR 最终 HEAD 与 CI 为准，不拿局部提交代替最终检查。

## 三种限制，分别取证

- `frame_max`限制单帧，不能代替整条消息大小。
- `maxInboundMessageBodySize`限制消息头声明的整条正文，必须经过实际接收、组装路径。
- 字段解码还涉及长度、嵌套深度、内存分配和异常处理，正文限额不能代替这些检查。

产品从 broker 接收的帧先由客户端解析，之后才到 `RabbitBridge` 的数字 ID 校验。
默认关闭 MQ、业务校验或“自管 broker”都不作为协议漏洞豁免。

旧版源码链是 `ConnectionFactory.params → AMQConnection → AMQChannel → AMQCommand → CommandAssembler.consumeHeaderFrame`。
工厂限额传入接收命令，组装器在接收正文前核查消息头。测试不只直接调用组装器，还通过产品实际工厂完成连接、通道和消费者协商：
小帧内声明 64 MiB + 1 的整条正文被拒绝；三字节正常正文拆成两帧仍完整交付。没有发送巨型消息或耗尽 JVM。

[5.14.3 发布说明](https://github.com/rabbitmq/rabbitmq-java-client/releases/tag/v5.14.3)记录了入站限额回补；
[CVE-2023-46120 上游公告](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-mm8h-8587-p46h)的版本范围仍保留。
两种证据并列，不私自改写上游公告，也不外推到全部接收方式或配置。

## 公告逐项迁移

[历史账册](risk-register.json)原样保留：25 条 `NOT_APPLICABLE`、6 条 `UNRESOLVED`及证据属于旧坐标。
下面是本轮有界判断，不是把六条都改成 `FIXED`。所有者仍为青野仓库维护者。

| 公告 | 5.14.3 路径 / 对照 | 5.37.0 候选与处置 |
| --- | --- | --- |
| [CVE-2026-61634](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5xwg-cfvj-gff5) | 协商 16 KiB 后，超出协商限额的帧未按预期拒绝 | 同一实际工厂路径拒绝，保留协议限制修复见证 |
| [CVE-2026-69219](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-68mj-5wr7-6fgg) | ValueReader 的字符串/字节数组长度未与可用数据核对，先分配再 EOF | 两种表示均先拒绝；实际 connection.start 也拒绝。只用小输入，不宣称执行过大内存攻击 |
| [CVE-2026-69220](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-93j5-89vc-pph4) | 70 层混合表/数组仍被解析 | 拒绝深层输入，4 层正常数据仍通过；未实施栈耗尽 |
| [CVE-2026-63335](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-qx7j-jv8m-fppr) | 正文片段超过头部声明值，抛内部 UnsupportedOperationException | 同输入成为 MalformedFrameException 协议拒绝；不承诺恶意 broker 不能使连接中断 |
| [CVE-2023-46120](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-mm8h-8587-p46h) | 实际消费者接收路径已有总正文限制，旧版也通过超限头与合法分片对照 | 保留具体反证及上游范围差异，不计为本次新增修复；其他配置/路径仍不据此排除 |
| [CVE-2026-75516](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-jh4v-gfqj-7rhx) | 不存在公告所述 setFrameMax / Math.min(0) 路径；协商 0 时默认帧限额对照通过 | 新版同条件也通过，不把旧版不存在的具体根因包装成升级成果 |
| [GHSA-5m9f-rphj-c435](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5m9f-rphj-c435) | 原条件性不适用：未调用相应 TLS API | 生产路径未变，原判定保留；不是明文连接安全证明 |
| [GHSA-6g32-pxv4-2wfj](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-6g32-pxv4-2wfj) | 原条件性不适用：未使用 JSON-RPC 类加载入口 | 原边界保留，不计为升级修复 |
| [GHSA-7822-rcf6-97fx](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-7822-rcf6-97fx) | 原条件性不适用：未启用相关属性回显/自动恢复路径 | 原边界保留，不计为升级修复 |
| [GHSA-cqgh-8p3p-mx4m](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-cqgh-8p3p-mx4m) | 原条件性不适用：未使用相应 JSON-RPC 映射入口 | 原边界保留，不计为升级修复 |
| [GHSA-h6w7-qmcm-q6xr](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-h6w7-qmcm-q6xr) | 原条件性不适用：未调用属性 URI 加载入口 | 原边界保留，不计为升级修复 |

## 版本迁移门禁

候选只将 amqp-client 5.14.3 换成 5.37.0。93 个项目依赖中，其他 92 个坐标、作用域及许可标签未变；
RabbitMQ 仍声明 AL 2.0 / GPL v2 / MPL-2.0，不据此作新的分发许可认证。

全量扫描由 31 条变为 20 条，变化仅是上述 11 条旧 RabbitMQ 坐标不再命中，其余公告内容指纹未变。
这是版本迁移后的当次扫描结果，不是修复了 11 个青野漏洞，也不是新版本没有未知问题。

[审查清单](dependency-policy.json)的 `retired_components`保留旧坐标、替代坐标、历史与变更 SHA、全部旧公告指纹和逐项说明。
原风险账册不删除、不改成已接受。清单重新绑定新 POM，其他引用输入哈希不变。
缺替代版本、缺历史条目/证据、旧版本仍存在、新版新增公告、公告变化均拒绝；已确认缺陷不能仅用坐标退役关闭。
这不是自动批准扫描变化。负控在普通及优化 Python 下执行。

## 回归入口与证据范围

[协议测试](../../backend/src/test/java/cn/qingye/RabbitClientBoundaryTest.java)在相同源码上只切换客户端版本：

```powershell
cd backend
mvn -B -ntp -Drabbitmq.version=5.14.3 -Dtest=RabbitClientBoundaryTest test
mvn -B -ntp -Dtest=RabbitClientBoundaryTest test
```

本地 Java 17 / Maven 3.9.11：旧版 11 项中 5 项失败，新版 11 项通过。
失败对应长度、递归、实际握手解码、异常组装和协商帧限额，不等于 5 个已证明可利用的业务漏洞。
整条正文上限、合法分片、协商 0 的默认帧限额和连接失败降级等在旧版也通过。

[真实 broker 测试](../../backend/src/test/java/cn/qingye/RabbitBridgeLiveTest.java)在独立 vhost 验证发布确认、消费 ACK、
非法消息拒绝且不重排、处理失败 NACK 且不重排、producer 关闭后重连及默认禁用。
本地 RabbitMQ 4.3.2 上 5 项通过；连接失败/冷却降级由环回故障测试验证。
这里是真实协议传输，TaskService 使用测试替身；业务持久化、权限和事务仍由原 H2 / MySQL 套件独立检查。

CI 原必需 H2 job 提供 RabbitMQ 4.3.2 与专用 qingye-test-ci vhost，明确设置 QINGYE_MQ_TEST=1；
未设置时这 5 项跳过，不能把跳过算通过。MySQL 必需检查和保护要求不变。
本地执行只用专建、可丢弃的 qingye-test- 前缀 vhost；测试会清空其中的测试队列：

```powershell
$env:QINGYE_MQ_TEST = '1'
$env:QINGYE_MQ_TEST_VHOST = '<专用 qingye-test- 前缀 vhost>'
$env:QINGYE_MQ_TEST_USER = '<仅限该 vhost 的测试账号>'
$env:QINGYE_MQ_TEST_PASSWORD = '<测试凭据>'
python -B scripts/check.py h2
```

本轮本地测试 vhost/账号已清理并读回，原 / vhost 与共享服务保留。
未验证学校部署、生产容量、网络隔离、任意 broker 配置或真实模型质量。

## 首败与当前资格

除了旧版预期反例，还保留了构造器 API 不一致、异常文字断言、低于协议最小值的协商夹具，
以及把接收态 Frame 当作发送态写出的消费夹具失败。它们属于验证设施，修正后重跑，不算产品漏洞。

本地原始 XML、tree、SBOM 和 OSV 报告独立留存；公开最小复现是本页命令和测试源码。
关键报告 SHA-256：

| 报告 | SHA-256 |
| --- | --- |
| 5.14.3 / 最终 11 项协议对照 | `43e606dae8222c4fd7c077919f97759316fe2a13cab7f00fa479f4089fbc0c88` |
| 5.37.0 / 最终 11 项协议对照 | `9117e53e85a8bcbb7a600f9647818072bfac20ee78120d64a2ad5f55535e8c63` |
| 5.37.0 / 5 项真实 broker 回归 | `b4f0204ba5dec23cdff348af1f8f3271b073aa3b986bfd700cd7c1e50ad4138f` |

这些记录不代替最终 HEAD 必需 CI、保护读回及后续 exact-main 验证。
本候选不授予主线已修复、正式发布或部署资格；合并仍需授权。
