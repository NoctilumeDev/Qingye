# 青野部署说明

运行环境为 Java 17、MySQL 8、单个应用实例；Redis 和 RabbitMQ 关闭，数据库通知任务继续运行。运行包不需要 Maven。下文适用于 GitHub 发布包；末节另说明从源码生成本地候选的流程。

## 下载与校验

从 [v0.1.0 Release](https://github.com/NoctilumeDev/Qingye/releases/tag/v0.1.0) 下载 `Qingye-v0.1.0.zip` 和同名 `.sha256` 文件。发布包对应提交 `7001a232f9b21602b9288bfd889fb0deb77bb424`，ZIP 的 SHA-256 为：

```text
7ed570916f0a4677e2b26f2c3e5d90acea0c9d5484e01766b99297762c898ad2
```

可用 `Get-FileHash -LiteralPath .\Qingye-v0.1.0.zip -Algorithm SHA256` 核对。解压后进入包目录再运行以下命令。GitHub 自动生成的 Source code 压缩包只有仓库源码；需要直接运行后端时，使用上面的 `Qingye-v0.1.0.zip` 资产。

## 文件与配置

`backend/qingye-0.1.0.jar` 为可执行后端；`database/schema.sql` 为同版本建表脚本；`miniprogram/` 为包含图片和组件的微信小程序源码。`SOURCE_MANIFEST.json` 记录源码提交、源文件和源码归档散列；v0.1.0 从干净的合并提交归档重建 JAR，源码保留在 `source/Qingye-v0.1.0-source.zip`。`SHA256SUMS` 记录包内文件散列，不包含自身。

在当前 PowerShell 会话设置配置，或由部署系统注入环境变量。`config/production.env.example` 只是字段模板，不会被启动脚本执行或自动加载。实际值保存在部署配置中，不写回发布包。

必需项为 `QINGYE_DB_URL`、`QINGYE_DB_USER`、`QINGYE_DB_PASSWORD`、`QINGYE_WX_APPID`、`QINGYE_WX_SECRET` 和 `QINGYE_TOKEN_SECRET`。签名密钥至少 32 字符，使用独立随机值并稳定保存，不能复用微信密钥。AppID 应与小程序实际 AppID 一致。模型配置 `QINGYE_LLM_URL`、`QINGYE_LLM_KEY`、`QINGYE_LLM_MODEL` 是可选项；不配置时保留固定业务查询。

```powershell
$env:QINGYE_DB_URL = 'jdbc:mysql://127.0.0.1:3306/qingye?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:QINGYE_DB_USER = '<部署数据库账号>'
$env:QINGYE_DB_PASSWORD = '<部署数据库密码>'
$env:QINGYE_WX_APPID = '<实际微信AppID>'
$env:QINGYE_WX_SECRET = '<实际微信AppSecret>'
$env:QINGYE_TOKEN_SECRET = '<独立随机签名密钥，至少32字符>'
.\scripts\start-production.ps1
```

尖括号示例必须替换，缺参数或仍是占位符时启动入口会失败。入口不读取 `local.ps1`，固定关闭演示、Redis、MQ，不执行 Maven，传播 Java 的退出码。缺省监听 `127.0.0.1:8087`；同机 HTTPS 反向代理可连接该地址。确需改变监听地址或端口时，显式设置 `QINGYE_BIND` 或 `QINGYE_PORT`。本包不自动安装 HTTPS 代理或证书。

## 新库与已有库

部署者先建立数据库和数据库账号。新库使用 `utf8mb4`，例如由数据库维护者执行：

```sql
CREATE DATABASE IF NOT EXISTS qingye CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

`schema.sql` 只建表，不创建数据库，也不选择数据库。默认启动会在连接 URL 指定的库执行 JAR 内的 `CREATE TABLE IF NOT EXISTS`；如需人工执行外置脚本，先明确选择同一个目标库。当前初始化路径需要建表及业务读写权限；从缺少 `notification.deleted_at` 的旧库升级时还需该表的 ALTER 权限。

正式模式不会灌入演示账号、社团或活动。首次启动会登记两条随产品维护的使用说明，这不是演示业务数据。已有库启动不会由本包 DROP/重建同名表；当前升级还会补消息回收字段及既有器材的登记别名。`CREATE TABLE IF NOT EXISTS` 不代表任意历史库结构都兼容，升级前应保存可恢复的数据库备份并核对本次目标库。

包内没有演示快照。不要使用仓库 `sql/qingye.sql` 初始化正式数据；该演示快照会替换同名业务表。

## 小程序与首位管理员

包内 `miniprogram/utils/config.js` 的 HTTPS 地址和 `miniprogram/project.config.json` 的 AppID 是明确的无效占位符，必须由部署者修改；`urlCheck` 已开启。实际 HTTPS API 地址应加入小程序 request 合法域名，然后用微信开发者工具导入 `miniprogram/`。小程序不保存 AppSecret 或后端签名密钥。

正式微信登录创建的账号默认都是普通用户。首位管理员先通过真实微信登录，在“我的”取得账号 ID；维护者核实本人身份，再在维护窗口检查尚无启用的非演示管理员：

```sql
SELECT id,name FROM app_user
WHERE admin=TRUE AND enabled=TRUE AND openid NOT LIKE 'demo:%';
```

结果应为空；如果已有管理员，使用现有账号管理流程。维护者再按核实的目标 ID 检查账号的昵称、启用状态及非演示身份，执行一次定向赋权：

```sql
-- 0 是无效占位值，必须替换为刚核实的真实账号 ID。
SET @qingye_first_admin_id = 0;
SELECT id,name,enabled,admin,(openid NOT LIKE 'demo:%') AS real_account
FROM app_user WHERE id=@qingye_first_admin_id;
UPDATE app_user SET admin=TRUE
WHERE id=@qingye_first_admin_id AND enabled=TRUE AND admin=FALSE
  AND openid NOT LIKE 'demo:%';
SELECT ROW_COUNT() AS changed_accounts;
```

只应更新一行；记录维护者、目标账号、时间和结果。这个人工引导不伪造应用审计操作者，后续权限调整通过工作台完成。系统不会把首位注册者自动提权，也没有预置正式管理员密码。

## 部署检查

- 核对下载校验值、`SOURCE_MANIFEST.json` 和包内 `SHA256SUMS`，确认使用的源码与运行包版本。
- 无正式参数时入口非零退出；提供实际参数后，`/api/auth/options` 应返回 `demoEnabled=false`、空 `demoUsers` 和 `wechatEnabled=true`，演示登录被拒绝。`wechatEnabled=true` 只表示参数非空，仍需一次实际微信登录验证。
- `/api/health` 成功只证明 HTTP 服务响应；分别检查目标数据库、首位管理员和一条本地通知投递。Redis/MQ 关闭不应关闭 TaskWorker。
- 核对小程序实际 AppID、HTTPS 域名、原生编译及真机交互；未完成的项目保持待验证。单实例压测成绩单独绑定运行环境和源码，不从 8 个数据库连接推算在线人数。

v0.1.0 的 `THIRD_PARTY/` 保存 57 个嵌入运行依赖及 Spring Boot 启动器的对应版本源码、POM、许可材料和运行 SBOM，具体清单及散列见包内 `THIRD_PARTY/README.md`。小程序图片与图标的来源见 `miniprogram/assets/README.md`。GitHub 发布包已经补齐这批交付材料，不含本地候选的 `PUBLIC_RELEASE_TODO.md`。

## 从源码准备候选包

以下工具生成供本地核对的候选目录，不直接生成上述 GitHub 发布包。先在本轮源码上完成检查和 `mvn -DskipTests package`，再显式选择本轮依赖检查生成的 SBOM。工具只收集现有产物，不执行构建、测试、上传或部署：

```powershell
python -B scripts/package-release.py --jar backend/target/qingye-0.1.0.jar --sbom backend/target/dependency-audit/<本轮目录>/bom.cdx.json --output <尚不存在的候选目录> --allow-dirty
```

`--allow-dirty` 明确允许未提交工作树，并保留状态与源文件散列；省略时非干净工作树会拒绝。输出目录必须不存在。打包器核对 JAR 的应用资源与当前源文件一致，记录 JAR 和源码各自散列；这不独立证明 Java 字节码已由这些源码重建，构建和测试证据仍需保留。

本地候选包含 `PUBLIC_RELEASE_TODO.md`，供核对第三方源码、许可和素材交付项；未提交源码候选还需保留原工作树。v0.1.0 的发布组装过程单独保留在发布包的 `source/assemble-github-release.py`，并以合并提交的源码归档构建。候选目录与公开发布包按各自清单核对。
