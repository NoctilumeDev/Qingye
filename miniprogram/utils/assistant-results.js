const contract = require('./assistant-contract')
const display = require('./assistant-display')
const emptyPresentation = { statusTitle: '', statusHint: '', statusTone: 'neutral', showAnswerText: false }
function section(result) {
  const labels = { ...contract.stateLabels, PUBLISHED: '已发布' }
  const displayAnswer = result.status === 'CONFIRM_SCOPE' ? readableScope(result.answer) : readableAnswer(result)
  return { ...result, displayAnswer, scopeOpen: false, scopeRows: display.scope(result.plan), displayWarnings: (result.warnings || []).map(display.warning), canContinue: !!result.feedbackToken && (!result.gateRound || result.gateRound < (result.maxGateRounds || 2)), warnings: result.warnings || [], items: (result.items || []).map(r => ({ ...r, displayName: r.title || r.equipmentName || r.name, timeText: display.timeRange(r.startTime || r.plannedStart || '', r.endTime || r.plannedEnd || ''), startLabel: (r.startTime || r.plannedStart || '').replace('T', ' '), endLabel: (r.endTime || r.plannedEnd || '').replace('T', ' '), stateLabel: labels[r.registrationStatus || r.status] || '' })) }
}
function readableAnswer(result) {
  return display.message(result) || (typeof result.answer === 'string' ? result.answer : '')
}
function readableScope(text) {
  return (text || '').replace('公开活动（已发布且社团正常开放）', '已发布的公开活动（社团正常开放）').replace('每页10条，可继续加载。确认前不会开始查找，条件不对可以修改。', '每页10条，可继续加载。').replace('公开活动（仅已发布、社团启用）', '已发布的公开活动').replace('器材目录与库存（含停用器材，停用不代表可借）', '器材及库存（包含已停用器材）').replace('器材名称或业务别名为「', '器材名称或别名：「').replace('」（不做模糊扩词）', '」（精确匹配）').replace('上海时间：当前库存', '库存时间：当前').replace('全部对象', '名称：不限').replace('上海时间：全部时间；按活动起止时间匹配（非创建、报名或归还日期）', '活动时间：不限（北京时间）').replace('未查业务记录；请核对条件后确认，不对就修改。', '确认前不会开始查找。')
}
function presentation(status, sections) {
  if (status !== 'QUERY' && sections.some(section => section.reason === 'MODEL_UNAVAILABLE')) return { statusTitle: '青野暂时没连上', statusHint: '可以稍后重试，也可以自己选择条件查找。', statusTone: 'warning', showAnswerText: true }
  const count = sections.reduce((total, section) => total + section.items.length, 0)
  const hasMore = sections.some(section => section.hasMore)
  if (status === 'QUERY' && count) return { statusTitle: '查询结果', statusHint: hasMore ? '已显示本页结果，下面还有更多记录。' : '看看有没有你想找的。', statusTone: 'success', showAnswerText: false }
  if (status === 'QUERY') return { statusTitle: '没有匹配记录', statusHint: '按这次的条件没有找到，试试其他名称或时间。', statusTone: 'neutral', showAnswerText: false }
  const states = {
    CONFIRM_SCOPE: ['按这些条件查找？', '确认后，我再帮你查找。', 'pending'],
    CLARIFY: ['还差一点信息', '告诉我更多细节，我再帮你找。', 'pending'],
    UNKNOWN: ['还没弄清你想找什么', '你可以换个说法，或自己选择条件。', 'unknown'],
    CLARIFICATION_PAUSED: ['暂时无法确定', '本轮尚未查询。你可以换种说法，或自己选择查询条件。', 'unknown'],
    UNSUPPORTED: ['这个条件暂时查不了', '你可以调整条件，或换一种查询方式。', 'warning'],
    REJECT: ['这个请求不能执行', '青野只会读取当前账号有权查看的信息。', 'danger'],
    SYSTEM_ERROR: ['暂时没能完成', '可以稍后再试，或自己选择条件查找。', 'warning']
  }
  const value = states[status]
  return value ? { statusTitle: value[0], statusHint: value[1], statusTone: value[2], showAnswerText: true } : emptyPresentation
}

module.exports = { section, readableAnswer, readableScope, presentation }
