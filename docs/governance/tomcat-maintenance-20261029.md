# Tomcat authority 边界维护

本轮将嵌入式 Tomcat 的 core、el、websocket 从 10.1.55 同步升级到 10.1.61，保持 Spring Boot 3.5.16、Java 17 和业务源码不变。
基线为 `cb8c8a0cdcf1afbee9c73a63da74f71348edc84e`，依赖与回归夹具提交为 `579181ebc099b8c69c5959b2e0f127d5fd2580e3`。
完整候选以本 PR 的最终提交、必需 CI 和合并后的复验为准。

## 修复边界

[CVE-2026-65637 / GHSA-f525-44xv-f2qj](https://github.com/advisories/GHSA-f525-44xv-f2qj)
涉及 HTTP/2 缺失 authority/Host 时绕过严格 SNI 检查。
[上游修复](https://github.com/apache/tomcat/commit/40012414df828a56126f76a7339669c7c919aae7)
要求请求具有可解析的服务名称；缺失时在流处理阶段返回协议错误，不能到达 servlet。
[Apache 安全公告](https://tomcat.apache.org/security-10.html)列明首个已发布修复版本为 10.1.59；
本轮选用同系列的 10.1.61，覆盖随后公开的修复，并保持 [Spring Boot 3.5 的 Tomcat 10.1 / Servlet 6 兼容范围](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。

仓库默认未启用 TLS/HTTP2。本轮通过环回地址、临时证书和两个独立 SSLHostConfig 建立严格 SNI 的受控测试条件，
没有修改生产连接器配置，也没有推断学校部署曾被利用。

## 同输入的新旧对照

[TomcatAuthorityBoundaryTest](../../backend/src/test/java/cn/qingye/TomcatAuthorityBoundaryTest.java)
使用真实 TLS/ALPN/h2 连接，不读取校园业务数据。四项测试包含以下请求表示：

| 请求 | 10.1.55 | 10.1.61 |
| --- | --- | --- |
| 不含 authority 和 Host | 到达测试 servlet，返回 fixture-ok；回归失败 | RST_STREAM / PROTOCOL_ERROR，未派发到 servlet |
| 非法 authority 或非法 Host | 协议拒绝，未派发 | 协议拒绝，未派发 |
| 合法 authority、合法 Host、两者一致 | 三种表示均正常返回 | 三种表示均正常返回 |
| authority 与 TLS SNI 对应不同配置 | 协议拒绝，未派发 | 协议拒绝，未派发 |

旧版四项中一项失败，新版四项全部通过。异常 Host 与显式跨 SNI 对照在旧版已拒绝，不算本次新增修复。
初次夹具的临时目录生命周期和跨 SNI 返回体断言曾失败，原日志保留；修正夹具后才进行上述同输入对照。

```powershell
cd backend
mvn -B -ntp -Dtomcat.version=10.1.55 -Dtest=TomcatAuthorityBoundaryTest test
mvn -B -ntp -Dtest=TomcatAuthorityBoundaryTest test
```

原始 Surefire 报告 SHA-256：旧版 `543106ea314b24439883332b5baebc15da8c663bcd4b3186e527c2677ab26d2b`；
新版 `327e7de494da42cd480de740340fcca42333d010f45f05ac30ff69ba541fd92a`。
原始报告与首败保存在本次 Codex Security 独立证据集合中；仓库中的测试是可重复运行的公开见证。

## 构建与依赖账册

对实际 Spring Boot JAR 的 `BOOT-INF/lib` 检查确认：三个 Tomcat 组件均为 10.1.61，嵌套字节与 Maven 已解析的相应 JAR 一致。
该 JAR 加载测试专用 H2，在环回随机端口启动；8 次 HTTP 请求覆盖健康检查、未登录 401、演示登录、个人资料、活动、器材及助手合同。
外部模型、反馈、Redis、RabbitMQ 均关闭，测试进程退出；这项检查不代替真实 MySQL 的业务回归。

首次扫描遇到直连 DNS 失败及代理下 Python 下载证书链错误，均保留首败。
随后复用本机已有扫描器，由原门禁核对固定 SHA-256；没有关闭 TLS 验证或扫描规则。
实际重新解析并联网扫描 93 个依赖，仅三个 Tomcat 坐标变化，作用域及许可证声明不变；
其余 90 个坐标与原清单相同，当前公告由 21 条变为 17 条，其余公告内容指纹不变。

旧 Tomcat 坐标的四条公告均保留在[历史风险账册](risk-register.json)：
`GHSA-9xv2-5v5q-p794`、`GHSA-gcx9-497g-6cp6`、`GHSA-h3x4-894j-xpx5` 的原条件性不适用结论不改写成新增修复；
`GHSA-f525-44xv-f2qj` 的原 UNRESOLVED 分诊保留，本页记录新版本对上述具体边界的修复验证。
部署是否曾启用受影响配置仍未调查。其余历史风险不因升级被接受或关闭。

[依赖清单](dependency-policy.json)通过独立 Tomcat 证据合同保留四条旧指纹、原基线、升级提交及两份证据哈希。
共享 POM 再次变化时，既有 RabbitMQ 退役记录增加 `manifest_review_revision`，绑定本次复核的 POM，
原历史提交、原升级提交及三份证据哈希保持不变。
后续复核另绑定已存在的 `prior_policy_revision`，原退役身份、升级提交、合同、公告与证据必须和该祖先清单一致。
门禁核对祖先清单与当时 POM 的绑定，以及新复核提交中的 POM 与当前清单；未填写后续复核时仍绑定原升级提交。
不存在或过早的复核 SHA、未复核的 POM、替代版本缺失、旧版本仍在、证据被改及新公告仍被拒绝。
Squash 后允许获取精确源提交，后续复核沿已交付清单的祖先关系核对。
独立审查发现首个实现误拒“先 squash、再升级”，且可将无关 POM 修改冒充原升级；两个反例均已保留并用于修正清单绑定。

## 回归范围

本地运行维护脚本、客户端、H2、静态检查、完整解析依赖门禁及独立反馈模块测试。
真实 MySQL 8.0.44 与 RabbitMQ 4.3.2 由原必需 CI 提供隔离实例，最终结果绑定 PR/主线提交。
本轮不创建 Release、不部署，也不重新测试模型语义或微信真机；提交不能代替这些资格。
