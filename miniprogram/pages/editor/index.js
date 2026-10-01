const { request, guard } = require('../../utils/request')
const { dateParts, format } = require('../../utils/view')
const titles = { activity: '发起一场校园活动', equipment: '整理共享器材', club: '创建一个有趣的社团', loan: '为活动预约好装备' }
const editTitles = { activity: '调整活动信息', equipment: '编辑器材资料', club: '编辑社团资料' }
function loanTimes(activity) {
  if (!activity) return {}
  return { startDate: activity.startTime.slice(0, 10), startTime: activity.startTime.slice(11, 16), endDate: activity.endTime.slice(0, 10), endTime: activity.endTime.slice(11, 16) }
}
Page({
  data: { kind: '', form: {}, choices: [], choiceIndex: 0, categories: ['运动', '艺术', '科技', '志愿', '其他'], categoryKeys: ['SPORT', 'ART', 'TECH', 'VOLUNTEER', 'OTHER'], categoryIndex: 0, colors: ['green', 'blue', 'orange', 'red'], colorNames: ['草坪绿', '天空蓝', '日光橙', '珊瑚红'], colorIndex: 0, busy: false, availability: null },
  async onLoad(options) {
    if (!guard()) return
    this.kind = options.kind; this.id = Number(options.id) || null; this.requestKey = this.key()
    this.setData({ kind: this.kind, title: (this.id ? editTitles[this.kind] : titles[this.kind]) || '校园工作台', editing: !!this.id })
    try {
      const user = await request('/me'); if (!user.workbench) { wx.navigateBack(); return }
      let form = {}, choices = []
      const start = dateParts(new Date(Date.now() + 2 * 86400000)), end = dateParts(new Date(Date.now() + 2 * 86400000 + 2 * 3600000)), deadline = dateParts(new Date(Date.now() + 86400000))
      if (this.kind === 'activity') {
        choices = (await request('/clubs')).filter(c => user.admin || (c.myRole === 'MANAGER' && c.myStatus === 'ACTIVE'))
        form = { clubId: choices[0] && choices[0].id, title: '', description: '', category: 'SPORT', location: '', poster: '', capacity: 30, startDate: start.date, startTime: start.time, endDate: end.date, endTime: end.time, deadlineDate: deadline.date, deadlineTime: deadline.time }
        if (this.id) { const a = await request('/activities/' + this.id); form = { ...form, ...a, startDate: a.startTime.slice(0, 10), startTime: a.startTime.slice(11, 16), endDate: a.endTime.slice(0, 10), endTime: a.endTime.slice(11, 16), deadlineDate: a.signupDeadline.slice(0, 10), deadlineTime: a.signupDeadline.slice(11, 16) } }
        this.setData({ categoryIndex: Math.max(0, this.data.categoryKeys.indexOf(form.category)), choiceIndex: Math.max(0, choices.findIndex(c => c.id === form.clubId)) })
      } else if (this.kind === 'equipment') {
        if (!user.admin) { wx.navigateBack(); return }
        form = { name: '', category: '摄影', description: '', image: '', totalQuantity: 5, enabled: true }
        if (this.id) form = (await request('/equipment')).find(r => r.id === this.id)
      } else if (this.kind === 'club') {
        form = { name: '', description: '', color: 'green', managerId: user.id }
        if (this.id) form = { ...form, ...(await request('/clubs')).find(r => r.id === this.id) }
        else { if (!user.admin) { wx.navigateBack(); return }; choices = await request('/users'); form.managerId = choices[0] && choices[0].id }
        this.setData({ colorIndex: Math.max(0, this.data.colors.indexOf(form.color)) })
      } else if (this.kind === 'loan') {
        const equipmentId = Number(options.equipmentId), equipment = (await request('/equipment')).find(r => r.id === equipmentId)
        choices = (await request('/activities?scope=work&upcoming=true')).filter(a => a.status === 'PUBLISHED')
        form = { equipmentId, activityId: choices[0] && choices[0].id, quantity: 1, reason: '', ...loanTimes(choices[0]) }
        this.setData({ equipmentName: equipment && equipment.name, activityTime: choices[0] ? format(choices[0].startTime) + ' — ' + format(choices[0].endTime) : '' })
      }
      this.setData({ form, choices, choiceNames: choices.map(c => c.title || c.name) })
      if (this.kind === 'loan') this.check()
    } catch (e) {}
  },
  key() { return 'wx-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12) },
  field(e) { this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value }); this.requestKey = this.key(); if (this.kind === 'loan') { clearTimeout(this.timer); this.timer = setTimeout(() => this.check(), 300) } },
  choice(e) {
    const index = Number(e.detail.value), selected = this.data.choices[index]
    if (!selected) return
    const field = this.kind === 'activity' ? 'clubId' : this.kind === 'loan' ? 'activityId' : 'managerId'
    this.setData({ choiceIndex: index, form: { ...this.data.form, [field]: selected.id, ...(this.kind === 'loan' ? loanTimes(selected) : {}) } })
    this.requestKey = this.key()
    if (this.kind === 'loan') { clearTimeout(this.timer); this.setData({ activityTime: format(selected.startTime) + ' — ' + format(selected.endTime), availability: null }); this.check() }
  },
  category(e) { const index = Number(e.detail.value); this.setData({ categoryIndex: index, 'form.category': this.data.categoryKeys[index] }) },
  color(e) { const index = Number(e.detail.value); this.setData({ colorIndex: index, 'form.color': this.data.colors[index] }) },
  enabled(e) { this.setData({ 'form.enabled': e.detail.value }) },
  async check() {
    const sequence = this.checkSequence = (this.checkSequence || 0) + 1
    const f = this.data.form, start = f.startDate + 'T' + f.startTime + ':00', end = f.endDate + 'T' + f.endTime + ':00'
    if (!(end > start) || !f.equipmentId) { this.setData({ availability: null }); return }
    try { const availability = await request(`/equipment/${f.equipmentId}/availability?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`); if (sequence === this.checkSequence) this.setData({ availability }) } catch (e) { if (sequence === this.checkSequence) this.setData({ availability: null }) }
  },
  async save() {
    if (this.data.busy) return
    const f = this.data.form; let path, body
    if (this.kind === 'activity') {
      if (!f.title || !f.location || !f.clubId || !f.description) return wx.showToast({ title: '请填写活动名称、介绍、地点和社团', icon: 'none' })
      path = '/activities'; body = { clubId: Number(f.clubId), title: f.title, description: f.description, category: f.category, location: f.location, poster: f.poster || '', capacity: Number(f.capacity), startTime: f.startDate + 'T' + f.startTime + ':00', endTime: f.endDate + 'T' + f.endTime + ':00', signupDeadline: f.deadlineDate + 'T' + f.deadlineTime + ':00' }
    } else if (this.kind === 'equipment') { path = '/equipment'; body = { name: f.name, category: f.category, description: f.description, image: f.image || '', totalQuantity: Number(f.totalQuantity), enabled: f.enabled } }
    else if (this.kind === 'club') { path = '/clubs'; body = { name: f.name, description: f.description, color: f.color, managerId: Number(f.managerId) } }
    else if (this.kind === 'loan') {
      if (!f.activityId) return wx.showToast({ title: '请先创建并发布一个活动', icon: 'none' })
      path = '/loans'; body = { activityId: Number(f.activityId), equipmentId: Number(f.equipmentId), quantity: Number(f.quantity), reason: f.reason, plannedStart: f.startDate + 'T' + f.startTime + ':00', plannedEnd: f.endDate + 'T' + f.endTime + ':00', requestKey: this.requestKey }
    } else return
    this.setData({ busy: true })
    try { await request(path + (this.id ? '/' + this.id : ''), this.id ? 'PUT' : 'POST', body); wx.showToast({ title: this.kind === 'loan' || this.kind === 'activity' ? '已提交审核' : '已保存' }); wx.navigateBack() }
    catch (e) {} finally { this.setData({ busy: false }) }
  },
  onUnload() { clearTimeout(this.timer) }
})
