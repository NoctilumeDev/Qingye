# 青野

**校园社团活动与共享资源管理系统的设计与实现**

首版代码已实现，主线是「社团 → 活动 → 报名 / 候补 → 器材预约 → 审批 → 领取 → 归还」。学生、负责人和管理员使用同一个原生微信小程序，管理操作集中在移动端工作台。

本机已完成真实 MySQL 的业务测试、HTTP 联调及微信原生 WXML/WXSS 编译检查。模拟器画面检查仍待开发者工具授权；微信真实登录和外部模型服务尚未配置，不能把本地演示视为已上线。具体证据见 [验证记录](VERIFICATION.md)。

## 运行

使用本机已有的 Java 17、Maven、MySQL 8、微信开发者工具。依赖版本沿用现有 Maven 缓存，不使用 Docker、uni-app 或额外 Node 业务服务。

1. MySQL 中准备两个独立数据库：

   ```sql
   CREATE DATABASE IF NOT EXISTS qingye CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE DATABASE IF NOT EXISTS qingye_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

2. 在本目录创建 **被 Git 忽略的** `local.ps1`，填写本机账号，或直接设置同名环境变量：

   ```powershell
   $env:QINGYE_DB_USER = '<你的数据库账号>'
   $env:QINGYE_DB_PASSWORD = '<你的数据库密码>'
   ```

3. 在 PowerShell 运行：

   ```powershell
   .\scripts\start.ps1
   # 可选，启用本机已运行的中间件：
   .\scripts\start.ps1 -Redis -Mq
   ```

   默认后端地址为 `http://127.0.0.1:8087`。首次启动自动建九张表；仅当演示模式开启且用户表为空时，初始化演示用户、社团、活动和器材。不会覆盖已有数据。再次构建前先停止已运行的青野进程，避免 Windows 锁定 JAR。

4. 微信开发者工具导入 **`miniprogram/`**，项目使用 `touristappid`。开发阶段关闭合法域名校验，进入登录页选择演示身份。前端通过 `wx.request` 直接访问 Spring Boot；Node 仅用于工具和语法检查。

   真机中 `127.0.0.1` 指手机自身。局域网调试时修改 `utils/config.js` 为电脑的局域网地址，并通过 `QINGYE_BIND` 设置监听地址。正式使用需配置微信 AppID、服务端密钥和 HTTPS 合法域名。

数据库密码、微信密钥、模型密钥和签名密钥只放环境变量或 `local.ps1`，不写入客户端和提交文件。`target/`、日志、运行文件及开发者工具私人配置均已忽略。

## 已实现

| 使用者 | 功能 |
| --- | --- |
| 同学 | 活动搜索与分类、可解释推荐、报名与候补、取消报名、入社申请、个人记录、站内通知、只读校园助手 |
| 社团负责人 | 本社团成员审批、活动创建和修改、参与名单、器材时间段查询与借用申请 |
| 管理员 | 社团和负责人设置、活动审核、同质器材数量维护、预约审批、实物领取与整单归还、运行状态 |

小程序共十个页面，以草坪绿、天空蓝、白色卡片和少量暖色点缀组织校园内容。没有 PC 管理端、音乐、天气弹窗、场地排期或固定资产台账。

## 技术与边界

| 部分 | 实现 |
| --- | --- |
| 后端 | Java 17 / Spring Boot 3.5.16 单体 |
| 数据库 | MySQL 8，JdbcTemplate 参数化 SQL；H2 仅用于快速测试 |
| 缓存 | Redis，公共列表 15 秒缓存，写入后失效；异常回 MySQL |
| 通知 | RabbitMQ Java 客户端，数据库 `message_task` 保底；异常本地处理，恢复后重试连接 |
| 核心算法 | 半开时间区间上的占用峰值扫描，`O(n log n)`；MySQL 行锁保护最终审批 |
| 大模型 | 本地范围门 → 意图参数门 → 固定查询门 → 数据库事实回答门；失败用本地规则 |

器材按「相机 5 台」管理。预约容量与当前实物分开：批准只证明计划容量足够，领取仍检查所有未归还数量，包括逾期记录。负责人身份属于 `club_member`，不是全局角色。

目录按变化原因分为 `model`（少量稳定数据契约）、`business`（规则与事务）、`db`（表结构和查询）、`api`（接口与输入）和 `integration`（易替换的外部能力）。数据库及分层细节见 [设计说明](DESIGN.md)。

手写源码约 **4,100 行**，统计 Java、SQL、小程序 JS/WXML/WXSS 和验证脚本，包含测试、空行及注释；排除依赖、构建产物和文档。维持 8,000 行停止线，新增功能先检查规模。

## 验证命令

```powershell
cd backend
mvn -o test
cd ..
.\scripts\test-mysql.ps1
python .\scripts\check-client.py
```

原生编译器校验可加 `--compiler-dir <现有开发者工具的 wcc-exec 目录>`，不安装额外编译环境。

HTTP 演示联调会创建标记为「联调」的社团、活动和器材，结束时取消活动、释放预约并停用测试器材，保留历史记录：

```powershell
python .\scripts\smoke-api.py --expect-cache REDIS --expect-mq RABBITMQ
```

MySQL 测试只允许 `qingye_test`，测试前会清空该库九张表的业务数据，不操作 `qingye` 或旧项目数据库。

## 真实微信与模型配置

`start.ps1 -RealWechat` 关闭演示入口，需设置 `QINGYE_TOKEN_SECRET`（至少 32 字符）、`QINGYE_WX_APPID`、`QINGYE_WX_SECRET`。真实用户首次登录后，由数据库维护者将指定用户的 `app_user.admin` 设为真，再从工作台创建社团和负责人。

校园助手可设置 `QINGYE_LLM_URL`（完整 chat-completions 地址）、`QINGYE_LLM_KEY`、`QINGYE_LLM_MODEL`。模型只生成白名单查询参数，不生成 SQL、用户身份或最终事实回答；未配置、超时、无效 JSON 均使用本地规则。通知为站内通知，不依赖微信订阅消息。
