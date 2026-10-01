# 安卓真机键盘验收

2026-10-01 开始，2026-10-02 完成以下三组真机复验。一个手机提供热点，安卓测试手机和电脑连接该热点，使用演示身份及局域网后端。没有接入真实微信账号登录。

用户对最新手机预览反馈“这三处都正常了”，对应助手查询按钮、预约连续填写、昵称弹窗按钮及动画 / 遮罩，见 [真机确认记录](design/keyboard/phone-confirmation.json)。这是用户实际操作反馈；下面的原生模拟器位置检查作为补充，不冒充真实输入法测试。

## 真机发现与修正

| 场景 | 原问题 | 本轮结果 |
| --- | --- | --- |
| 问问青野 | 输入框可见，查询按钮仍在输入法下面，[原截图](design/keyboard/phone-assistant-before.png) | 输入时自动上移至查询按钮可见；用户确认可点 |
| 器材预约 | 数量输入及下一项用途说明被遮挡，需要关键盘再填，[原截图](design/keyboard/phone-loan-before.png) | 保留当前字段标签，并展示能容纳的下一项；用户确认可连续填写 |
| 昵称弹窗 | 首版按钮被盖住，随后移动遮罩导致亮色断层，[原截图](design/keyboard/phone-mask-gap-before.png) | 只移动白色弹窗，遮罩固定覆盖整页；用户确认按钮、动画和遮罩正常 |
| 器材编辑 | 用户首先报告的表单遮挡问题 | 与预约共用适配，原生运行时验证介绍到分类、分类到数量；该变体未单独收到最终真机确认 |

共享编辑页的活动、社团、器材、预约输入框统一接入键盘适配。首页搜索保留原生输入框默认行为，未报告最终遮挡；活动介绍、社团编辑和原生审核意见没有逐项追加真机确认。一次安卓反馈不代表所有安卓、iOS 或输入法均通过。

## GitHub 兼容性参考与实际诊断

先查阅了 [小程序 Android 数字键盘重复事件 / 缺少关闭事件报告](https://github.com/xiaweiss/miniprogram-bug-report/issues/288)。该报告对应其列出的微信及基础库版本，是兼容性参考，不能据此断言用户手机就是同一问题。

查到 [Keyboard-for-miniProgram](https://github.com/Kim1am/Keyboard-for-miniProgram) 提供数字、英文、车牌键盘，[taro-keyboard](https://github.com/jarbozhang/taro-keyboard) 提供基于 Taro 的数字键盘功能。青野保留系统中文输入法，没有安装这些组件或引入 Taro / uni-app。

实际微信原生运行时同时暴露了两处项目适配问题：

1. 原先用属性选择器定位输入框，`wx.createSelectorQuery` 返回 `null`，而 `.field` 能取到矩形，见 [修正前读回](design/keyboard/native-selector-before.json)。开发者工具页面自动化能找到该元素，不代表原生节点查询也能找到。改为原生 `#field-*` ID 后得到有效位置。
2. 输入框与包装字段边界存在约 0.000016 像素的浮点差异，精确比较漏掉当前字段，从而不展示下一项，见 [首个反例](design/keyboard/native-next-field-first-failure.json)。包含关系比较加入 1 像素容差；回归检查保留这一边界。

因此同时处理平台事件差异与项目节点定位，依据实际读回修正，没有继续叠加固定上移值。

## 实现与验证

`utils/keyboard.js` 统一处理组件事件、全局高度事件及焦点高度回退。输入框关闭默认自动顶起，由一个控制器负责位置，按窗口已经缩小的量扣除补偿，避免重复上移。展示当前字段和能容纳的下一项；助手直接以查询按钮为目标。键盘动画稳定后复核一次位置，相同目标不重复启动滚动。

页面滚动使用 180ms，弹窗采用 180ms transform 过渡。遮罩不随键盘改变底边，也未添加弹窗阴影。失焦稍作等待，连续切换输入框时取消收起处理；隐藏或卸载页面移除自己的监听、计时器并使迟到测量失效。补充卸载反例时，修正前发生一次迟到页面移动，见 [反例日志](design/keyboard/keyboard-unload-late-movement-before.txt)，修正后回归通过。

原生模拟器注入 500px 键盘高度后，实测以下五组位置通过，见 [完整矩形读回](design/keyboard/keyboard-ui-final.json)：

- 助手查询按钮全部露出：[截图](design/keyboard/keyboard-assistant-final.png)。
- 器材介绍及下一项分类可见：[截图](design/keyboard/keyboard-editor-final.png)。
- 不关键盘切换分类，下一项数量可见。
- 预约数量及下一项用途说明可见：[截图](design/keyboard/keyboard-loan-final.png)。
- 昵称弹窗按钮可见、遮罩高度覆盖窗口：[截图](design/keyboard/keyboard-nickname-final.png)。

这些截图没有真实系统键盘，因此以读回矩形和注入的遮挡边界核对；真实动画是否舒服以用户最新手机反馈为准。最后补充的卸载清理不改变已验收的视觉布局。

前端 35 项 Node 回归与十页 WCC / WCSC 原生编译通过，见 [前端回归输出](design/keyboard/client-keyboard-final.txt)。检查含字段连续输入、缺少组件事件、重复高度、失焦关闭回退、窗口缩小去重、返回恢复、用户手动滚动保留及迟到测量失效。没有新增键盘库或第二套前端。

## 范围

本轮确认使用这台安卓手机的三个明确场景。真实微信登录、HTTPS、微信审核、正式部署和压力容量保持在当前毕业设计验收范围外。局域网地址和测试凭据只用于本机预览，提交保留客户端默认回环地址，密钥与私人配置被 Git 忽略。
