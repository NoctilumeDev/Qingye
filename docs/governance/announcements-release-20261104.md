# 公告与发布前检查（2026-11-04）

基线为 `bf7a4984facedb78fad3be5c92384435c316fa14`，分支 `feat/announcements-20261104`。本次增加公告，修正正式模式认证和本地缓存并发，四道门、模型提示词与查询合同不变。

## 公告

首页入口位于“我的活动”和“问问青野”之间。普通用户可归档、恢复；归档状态跟账号保存，没有七天清理期限。管理员从工作台发布公告，可修改和删除管理员公告。删除后所有用户均不可见，后台保留历史内容和操作记录。

两条使用说明来自 `backend/src/main/resources/announcements.json`。管理员不能改删；维护者修改内容时递增 `revision`，稳定 `key` 保留原公告 ID 和个人归档状态。旧版本启动不能覆盖更高 revision。

编辑、删除、归档均携带打开时的内容版本。服务器锁定公告后校验版本，过时操作返回 409；编辑页保留草稿。管理员写入先锁账号再重检当前权限，避免等待期间撤权仍然写入。归档不更改内容版本，也不影响其他人的列表。

## 检查中修复的问题

- 正式模式认证现在在同一条用户查询中排除 `demo:` 身份。沿用演示阶段的签名密钥也不能继续使用旧演示管理员会话。真实账号按当前启用状态认证。
- `QueryCache` 不再在全局锁内执行数据库查询。读取保存代际，失效递增代际，旧查询返回时不得回填；Redis 访问仍在短临界区内。
- MySQL 对 `COALESCE(archived,FALSE)` 返回数字，H2 返回布尔值。存储出口统一为布尔值，客户端合同保持一致。最初 MySQL 的两条失败及修复后的回归分别保留。

## 静态与依赖复核

新扫描发现五条 `EI_EXPOSE_REP2`，分别涉及 AnnouncementApi.service、AnnouncementService.store、AnnouncementStore.sql、BuiltinAnnouncements.json/sql。它们是 Spring 管理的依赖引用，无请求提供的替换入口、依赖 getter 或响应序列化出口。mapper 只在初始化中读取固定 classpath JSON，未修改其配置。这五条按各自调用路径登记 `NOT_APPLICABLE`，既有候选状态不变。第三方 bean、动态扩展、依赖暴露或运行期 mapper 配置变化时须重审。

QueryCache 关联的 11 条 Jackson 和 3 条 Netty 适用性前提保持：Redis String 仍解析为 List，Lettuce 管线不变。新公告 DTO 仅含字符串、long 和 Boolean，预置说明为 classpath InputStream → JsonNode；未增加 DataInput、多态、对象身份、视图、XML、地址或路径绑定。`GHSA-7hhh-6rmp-j9qf` 原来的“所有显式输入均为 String”改为准确的输入路径说明，处置状态不变。源码指纹在逐项复核后更新。

## 部署范围

本轮发布候选采用单应用实例、MySQL、Redis/MQ 关闭的配置。连接池为 8；容量以[压测记录](../release-check-20261104.md)为准。

Redis 的失效键集合仍保存在当前进程，跨实例和快速重启可能漏删其他进程留下的缓存，15 秒 TTL 提供过期边界。TaskWorker 还没有领取租约，多 Worker 或消费延迟可重复发布任务；TaskService 行锁与状态重检防止重复完成业务通知。多实例部署需要先处理这两项并另行测试。
