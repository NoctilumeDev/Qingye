const categories = { SPORT: ['运动', '🏀', 'green'], ART: ['艺术', '📷', 'blue'], TECH: ['科技', '✦', 'orange'], VOLUNTEER: ['志愿', '🌱', 'green'], OTHER: ['校园', '☀', 'red'] }
const states = { PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '未通过', CANCELLED: '已取消', APPROVED: '已批准', CHECKED_OUT: '已领取', RETURNED: '已归还', REGISTERED: '已报名', WAITLISTED: '候补中', ACTIVE: '已加入', LEFT: '已退出' }
function timestamp(value) { return new Date(String(value).replace(/(\.\d{3})\d+/, '$1') + '+08:00').getTime() }
function activity(item) {
  const style = categories[item.category] || categories.OTHER
  let phase = states[item.status] || item.status
  if (item.status === 'PUBLISHED') {
    const now = Date.now()
    phase = now >= timestamp(item.endTime) ? '已结束' : now >= timestamp(item.startTime) ? '进行中' : now >= timestamp(item.signupDeadline) ? '报名结束' : item.registeredCount >= item.capacity ? '候补报名' : '正在报名'
  }
  return { ...item, categoryName: style[0], symbol: style[1], tone: style[2], phase, displayTime: format(item.startTime), slots: Math.max(0, item.capacity - item.registeredCount) }
}
function format(value) { return value ? String(value).replace('T', ' ').slice(5, 16) : '' }
function record(item) { return { ...item, statusText: states[item.status] || item.status, displayStart: format(item.plannedStart), displayEnd: format(item.plannedEnd), overdue: item.status === 'CHECKED_OUT' && Date.now() >= timestamp(item.plannedEnd) } }
function dateParts(date = new Date(Date.now() + 15 * 60 * 1000)) {
  const p = n => String(n).padStart(2, '0')
  return { date: `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`, time: `${p(date.getHours())}:${p(date.getMinutes())}` }
}
module.exports = { categories, states, activity, record, format, dateParts }
