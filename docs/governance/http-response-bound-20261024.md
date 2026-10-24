# 外部 HTTP 正文接收边界维护

基线 `46ec193b4bb4736fcfb06b1beb3b7edcb162d46e`。本次只处理 `Q3-HTTP-BODY`：
原 `ExternalHttp` 完整收取正文后才检查 100000 字符，不能将该检查当作接收过程的资源上限。
没有复现 JVM 内存耗尽或生产攻击，也不因此升级中间件、改动演示登录或授予部署资格。

## 两个独立上限

共享 GET/POST 接收路径在把每批缓冲交给 JDK 字符串接收器之前，累计检查实际正文长度。
超过 **1048576 bytes（1 MiB）** 时取消上游并使结果失败，不继续等待完整正文，不截断为可解析的成功结果。
固定长度、chunked 和非 200 响应都经过同一边界，不信任 Content-Length 来替代实际计数。

原有 **100000 UTF-16 字符单位** 的解码后检查继续保留；两项限制并不等价。
初稿按每字符四字节估计 400000 bytes，但 UTF-32 BOM 和 ISO-2022-JP 字符集切换构成反例，
因此采用独立的 1 MiB 传输预算，并保留这些编码的正常输入回归。
任意冗余状态切换或填充产生的无限字节表示不属于兼容承诺。
这约束本类聚合的正文，不是对 JDK 网络缓冲、解码副本、并发请求或整个 JVM 堆的精确总量保证。

接收器将准入数据复制到自有的 8 KiB 块，再交给 JDK 解码器。仅转发原切片的初始候选，
在有界探针中让 64 个单字节切片继续引用 4 MiB 底层数组；紧凑复制后没有保留原切片，
64 bytes 正文只占一个自有块。这是缓冲引用关系的反例，不是网络攻击或 JVM 耗尽证明。
[接收器回归](../../backend/src/test/java/cn/qingye/integration/ExternalHttpBodyTest.java)同时验证
小切片独立存储、恰好 1 MiB、超出一个字节的取消与异常完成、整批拒绝和无订阅时的关闭行为。

字符集仍由 `BodyHandlers.ofString()` 根据响应头解释。状态检查、字符串到非类型化 `Map` 的
JSON 解析位置、GET 4 秒 / POST 8 秒整体等待期限、取消与中断标志恢复均保留。
`LlmPlanner` 继续区分 JSON 协议错误与传输不可用；微信认证传输异常仍通过现有错误路径返回。

## 原条件对照

新增 [ExternalHttpTest](../../backend/src/test/java/cn/qingye/ExternalHttpTest.java) 的相同 17 项测试，
先在旧接收实现执行：12 项通过，5 项提前拒绝断言失败，错误与跳过均为零。
这 5 项分别覆盖 GET/POST × 固定长度/chunked，以及 chunked 非 200；服务端发出超限前缀后
保持正文未结束，旧实现不能在测试等待窗口内失败。编译、测试发现或下载错误不计为负控。
候选同条件 17 项通过，原 `IntegrationBoundaryTest` 5 项通过，包括头部等待与正文停滞超时。
另含中文、UTF-16、UTF-32 BOM、ISO-2022-JP、普通字符集、旧字符边界、无效 JSON 和调用中断对照。
全部使用本机回环和合成数据，不调用真实模型或微信，不连接共享数据库。

## 源码分诊绑定复核

`dependency-policy.json` 对 `ExternalHttp.java` 的绑定从
`0b36d2ec41b985ba3088ce68bdd5aa88c478caa58c8333d5eb4ef916963b924c` 更新为
`56799c9be0c3110c9918d3119c4a8eac908493f5e1794c1b8b8b199d8b81d60e`（规范化 UTF-8/LF）。
这不是只刷新散列：复核最终 diff 后，下列原分诊依赖的调用前提没有改变。

| 原候选 | 本次复核的相关前提 |
| --- | --- |
| GHSA-7hhh-6rmp-j9qf | JSON 入口仍为 String，没有 DataInput parser |
| GHSA-p6pp-m3f8-5c89 | 仍为非类型化 Map，没有新增浮点目标的字符串强制转换 |
| GHSA-5gvw-p9qm-jgwh、GHSA-mhm7-754m-9p8w | 没有新增 view/property 注解或 active-view reader |
| GHSA-5jmj-h7xm-6q6v | 没有新增 ignored-property 注解或以其作为授权机制 |
| GHSA-cxp5-3px4-pw24 | 没有 identity 注解或对象 ID 解析 |
| GHSA-gx83-3vf8-gh7j、GHSA-wv8q-qhhj-9h54 | 没有激活多态类型、default typing、mixin 或 defaultImpl |
| GHSA-q4xh-88c3-wmh7 | 没有新增 XML 类型目标 |
| GHSA-vvgp-rfg2-7rr6、GHSA-wjgm-6hv5-3cvf | 没有新增 InetAddress / Path 类型目标 |

11 项历史 `NOT_APPLICABLE`、其他候选和未解决事项全部保持原状态；不包装为本次修复成果。
静态检查中的既有 `spotbugs-024` / `spotbugs-025` 同样没有因此获得关闭依据。
历史主线、首败和之前的安全判断仍绑定各自原坐标。

## 验证入口与范围

```powershell
cd backend
mvn -o -B -ntp '-Dtest=ExternalHttpTest,ExternalHttpBodyTest,IntegrationBoundaryTest' clean test
cd ..
python -B scripts/check.py quick
python -B scripts/check.py dependencies
```

真实 MySQL、RabbitMQ、完整门禁和合并后的主线状态以对应运行证据为准，
不能由以上本机 HTTP 对照推导。候选经受保护 PR 审查，不自动合并或创建 Release。
