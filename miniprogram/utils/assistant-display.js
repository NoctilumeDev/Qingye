// User-facing language belongs to the client, independently of the model prompt.
const contract = require('./assistant-contract')

function scope(plan) {
  if (!plan) return []
  const intent = plan.intent
  const labels = contract.timeLabelsByIntent[intent] || {}
  const time = labels[plan.timeOption] || contract.timeLabels[contract.times.indexOf(plan.timeOption)]
  const rows = [
    ['查找内容', contract.intentLabels[contract.intents.indexOf(intent)]],
    ['名称', !plan.entity || plan.entity === 'ALL' ? '不限' : plan.entity],
    [intent === 'MY_LOANS' ? '计划使用时间' : intent === 'EQUIPMENT' ? '库存时间' : '活动时间', plan.date || time],
    ['分类', plan.category && contract.categoryLabels[contract.categories.indexOf(plan.category)]],
    ['地点', plan.location], ['状态', plan.status && contract.stateLabels[plan.status]]
  ]
  return rows.filter(([, value]) => value).map(([label, value]) => ({ label, value }))
}

function message(result) {
  const reasons = {
    MODEL_UNAVAILABLE: '青野暂时没连上。可以稍后重试，也可以自己查找。',
    INVALID_PROTOCOL: '这次没能整理出可靠的查询。可以重试，也可以自己查找。',
    UNSUPPORTED_FILTER: '目前还不能按这个条件查找。你可以自己查看已有记录。',
    UNSUPPORTED_CAPABILITY: '这件事青野暂时还办不到。可以查活动、器材和你自己的记录。',
    PRIVATE_DATA: '他人的私人记录不能查看。你可以查自己的报名和借用记录。',
    WRITE_OPERATION: '青野可以帮你查信息。报名、借用或修改资料，请到对应页面操作。',
    IDENTITY_CLAIM: '查询使用你当前登录的身份，不能切换成其他人的身份。',
    OUTSIDE_DOMAIN: '我能帮你查校园活动、器材和自己的报名、借用记录。',
    GATE_ROUND_LIMIT: '这次还没弄清楚，就先停在这里。你可以重新提问，或自己查找。',
    UNKNOWN_ENTITY: '还不能确定你指的是哪一个。可以补充名称，或自己查找。',
    PLAN_INVALID: '这些条件暂时无法一起查询。请重新描述，或自己查找。',
    INVALID_REQUEST: '这次没法处理，请检查输入后重试。'
  }
  const states = {
    UNKNOWN: '这次还没弄清你想找什么。可以重新描述，或自己查找。',
    CLARIFICATION_PAUSED: '这次先停在这里。可以重新描述，或自己查找。',
    UNSUPPORTED: '目前还不能完成这个查找。你可以自己查看已有记录。',
    REJECT: '这个请求无法处理。青野只能查询你有权查看的信息。',
    SYSTEM_ERROR: '这次没能找完。可以稍后重试，也可以自己查找。'
  }
  return reasons[result.reason] || states[result.status] || ''
}

function warning(text) {
  if (text.includes('快照')) return '记录会随业务更新，加载下一页时以最新数据为准。'
  return text
}

module.exports = { scope, message, warning }
