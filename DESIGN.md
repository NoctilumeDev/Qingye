# 青野：数据库与后端设计

第一版只管理活动名额和可计数器材，九张表保持固定。地点是活动的一段文字，器材是同质数量，不记录单件资产。

## 九张表

| 表 | 主要事实 / 约束 |
| --- | --- |
| `app_user` | 微信 openid 唯一；姓名、头像、全局管理员标识、启用状态 |
| `club` | 社团介绍、配色、创建人和启用状态 |
| `club_member` | `(club_id,user_id)` 唯一；本社团的 MEMBER / MANAGER；PENDING / ACTIVE / REJECTED / LEFT |
| `activity` | 社团、标题、分类、地点、开始结束、报名截止、名额、审核记录；PENDING / PUBLISHED / REJECTED / CANCELLED |
| `registration` | `(activity_id,user_id)` 唯一；REGISTERED / WAITLISTED / CANCELLED；候补按 `joined_at,id` 排序 |
| `equipment` | 器材类型的名称、总数量、启用状态；没有全局 `available_quantity` 可变库存字段 |
| `loan` | 活动、申请人、器材、数量、计划起止、唯一申请标识、审核及实际领取归还时间 |
| `notification` | 用户应看到的通知内容；是否已投递、已读时间 |
| `message_task` | 对应通知唯一；到期时间、来源、重试次数；PENDING / DONE / CANCELLED |

外键和唯一约束在数据库中生效，查询索引覆盖活动列表、候补顺序、器材区间、用户通知和到期待办。完整字段见 [schema.sql](backend/src/main/resources/schema.sql)。初始化 SQL 面向新库，`CREATE IF NOT EXISTS` 不承担任意旧结构迁移。

## 报名

事务先锁活动行，再读取当前报名数量。空位存在则 REGISTERED，否则 WAITLISTED；唯一约束阻止同一个学生重复占位。取消正式报名后，在同一事务内将最早候补递补，并写通知与消息任务。

报名截止后不接收新报名；已存在的候补在活动开始前仍可递补。活动开始后不能取消报名。取消活动会取消报名及未领取的器材预约，保留 CHECKED_OUT 记录直到实际归还。

## 器材时间区间

预约状态是 PENDING → APPROVED / REJECTED；APPROVED → CHECKED_OUT → RETURNED。PENDING / APPROVED 也可 CANCELLED。第一版领取和归还均由管理员确认，按整单完成。

只有 APPROVED、CHECKED_OUT 的计划区间参加容量计算。所有时间采用 Asia/Shanghai，区间为 `[start,end)`。

```text
A 14:00–15:00 ×3
B 15:00–16:00 ×3
C 14:00–16:00 ×2
峰值是 5，不是 3+3+2=8。
```

算法查询相交预约，将边界裁剪到申请范围，把开始转换为 `+quantity`、结束转换为 `-quantity`，合并同一时刻变化，按时间排序累加求峰值。可预约量为 `total - peak`；复杂度 `O(n log n)`。

审批事务按 **activity → equipment → loan** 顺序拿锁，同类器材以 equipment 行为容量裁决点。持锁后重新读取有效预约并扫描，只有 `peak + quantity <= total` 才批准。所有相关写操作遵守这一顺序；READ_COMMITTED 加锁读取保证等待后看到最新已提交预约。Redis 不参与裁决。

领取在预约窗口内再次锁定并检查 `total - SUM(CHECKED_OUT.quantity)`。计划到期不会自动释放实物；不足时返回 409，保持 APPROVED，提示前序借用可能未归还。减少器材数量不能低于当前实物借出数和未来预约峰值，停用前必须处理未归还与有效预约。

## 权限与分层

HMAC 会话有效八小时，每次请求重新读取用户与当前社团身份；模型和客户端都不能指定有效操作者。管理员属于全局身份；负责人只在特定社团生效。交接负责人时至少保留一位有效负责人。

| 包 | 因何变化 | 边界 |
| --- | --- | --- |
| `model` | 少量通用数据形状 | Actor、Reservation、QueryPlan，无 ORM 模板和业务实体树 |
| `business` | 校园流程或规则改变 | 权限、状态流转、事务、扫描算法、可解释推荐 |
| `db` | 表结构或查询改变 | SQL 固定在 Store；所有外部参数绑定，不接受动态 SQL |
| `api` | 小程序接口或输入改变 | 参数校验、会话过滤、HTTP 状态与结果包装 |
| `integration` | 中间件或供应商改变 | 超时、缓存、MQ 和模型适配；不决定名额和库存 |

保持一个 Maven 模块、一个 Spring Boot 进程。不铺 repository/service/implementation 接口套层，不建立角色菜单中台。

## 可降级能力

公共查询缓存 TTL 为 15 秒，控制器在业务事务提交后失效缓存；失效失败则跳过缓存直到旧条目过期。缓存仅面向当前单实例，跟踪键最多 256 个，更多搜索直接查库。查询缓存的空结果也保留正确类型。

业务变更、notification、message_task 在同一数据库事务提交。每两秒处理到期任务，MQ 投递持久任务 ID，消费者锁任务并幂等完成后 ACK。发布确认失败时本地处理；数据库失败留下待办并延迟重试。MQ 消息重复或旧消息不会重复生成通知。任务表是真实待办来源，MQ 队列不是业务事实。

助手四道门依次是：本地范围检查、严格意图参数检查、固定参数化 SQL / 会话身份、数据库事实模板回答。允许活动、器材、本人报名和本人借用查询。缺配置和模型异常回到本地关键词及日期规则，无模型驱动的写操作。

推荐按历史分类、加入社团、近期活动与剩余名额打固定分，并返回理由；核心算法点仍是器材区间峰值，推荐不引入额外训练或算法平台。
