const categories = { SPORT: ['运动', 'activity-sport-v1', 'green'], ART: ['艺术', 'activity-photo-v1', 'blue'], TECH: ['科技', 'activity-tech-v1', 'orange'], VOLUNTEER: ['志愿', 'campus-friends-v1', 'green'], OTHER: ['校园', 'campus-friends-v1', 'blue'] }
const states = { PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '未通过', CANCELLED: '已取消', APPROVED: '已批准', CHECKED_OUT: '已领取', RETURNED: '已归还', REGISTERED: '已报名', WAITLISTED: '候补中', ACTIVE: '已加入', LEFT: '已退出' }
function timestamp(value) { return new Date(String(value).replace(/(\.\d{3})\d+/, '$1') + '+08:00').getTime() }
function activity(item) {
  const style = categories[item.category] || categories.OTHER
  let phase = states[item.status] || item.status
  if (item.status === 'PUBLISHED') {
    const now = Date.now()
    phase = now >= timestamp(item.endTime) ? '已结束' : now >= timestamp(item.startTime) ? '进行中' : now >= timestamp(item.signupDeadline) ? '报名结束' : item.registeredCount >= item.capacity ? '候补报名' : '正在报名'
  }
  const defaultCover = '/assets/campus/' + style[1] + '.jpg'
  return { ...item, categoryName: style[0], cover: item.poster || defaultCover, defaultCover, tone: style[2], phase, displayTime: format(item.startTime), slots: Math.max(0, item.capacity - item.registeredCount) }
}
function equipment(item) {
  const kind = /相机/.test(item.name) ? 'camera' : /三脚/.test(item.name) ? 'tripod' : /投影/.test(item.name) ? 'projector' : /开发板/.test(item.name) ? 'board' : null
  const defaultImage = kind ? '/assets/campus/equipment-' + kind + '-v1.jpg' : '/assets/icons/box-3-line-muted.png'
  return { ...item, available: Math.max(0, item.totalQuantity - item.borrowedQuantity), displayImage: item.image || defaultImage, defaultImage }
}
function club(item) {
  const scene = /摄影|拍照/.test(item.name) ? 'activity-photo-v1' : /运动|篮球|足球|跑步/.test(item.name) ? 'activity-sport-v1' : /创作|科技|编程|开发/.test(item.name) ? 'activity-tech-v1' : 'campus-friends-v1'
  return { ...item, cover: '/assets/campus/' + scene + '.jpg' }
}
function format(value) { return value ? String(value).replace('T', ' ').slice(5, 16) : '' }
function record(item) { return { ...item, statusText: states[item.status] || item.status, displayStart: format(item.plannedStart), displayEnd: format(item.plannedEnd), overdue: item.status === 'CHECKED_OUT' && Date.now() >= timestamp(item.plannedEnd) } }
function dateParts(date = new Date(Date.now() + 15 * 60 * 1000)) {
  const p = n => String(n).padStart(2, '0')
  return { date: `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`, time: `${p(date.getHours())}:${p(date.getMinutes())}` }
}
module.exports = { categories, states, activity, equipment, club, record, format, dateParts }
