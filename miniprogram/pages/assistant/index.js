const { request, guard, confirm } = require('../../utils/request')
const { keyboard } = require('../../utils/keyboard')
Page({
  data: { question: '', answer: '', busy: false, status: '', interpretation: '', corrections: [], correcting: false, confirmationToken: '', nextToken: '', hasMore: false, facetOpen: false,
    objects: ['公开活动', '器材库存', '我的报名', '我的借用'], objectIndex: 0,
    times: ['全部时间', '今天', '明天', '后天', '过去（已结束）', '现在（进行中）', '未来（未开始）', '本周剩余', '周末剩余', '指定日期'], timeIndex: 0,
    categories: ['全部分类', '运动', '艺术', '科技', '志愿', '其他分类'], categoryIndex: 0,
    states: ['全部状态'], stateIndex: 0, entity: '', location: '', date: '',
    suggestions: ['查全部活动', '后天有什么活动？', '我的报名活动有哪些？', '相机现在还有多少？'], items: [], keyboardHeight: 0 },
  onLoad() { if (guard()) this.keyboardLayout = keyboard(this, 'keyboardHeight') }, input(e) { if (!this.data.busy) this.setData({ question: e.detail.value, confirmationToken: '', nextToken: '' }) },
  focusQuestion(e) { this.keyboardLayout.focus('#assistant-query', 16, '', e && e.detail) },
  blurQuestion() { this.keyboardLayout.blur() },
  keyboardChange(e) { this.keyboardLayout.change(e) },
  onHide() { this.queryGeneration = (this.queryGeneration || 0) + 1; this.setData({ confirmationToken: '', nextToken: '', busy: false }); if (this.keyboardLayout) this.keyboardLayout.reset() },
  onUnload() { this.onHide() },
  suggestion(e) { if (this.data.busy) return; this.setData({ question: e.currentTarget.dataset.question }); return this.ask() },
  correct() { if (!this.data.busy) this.setData({ correcting: true, facetOpen: true, confirmationToken: '', nextToken: '' }) },
  toggleFacets() { if (!this.data.busy) this.setData({ facetOpen: !this.data.facetOpen }) },
  facetField(e) { if (!this.data.busy) this.setData({ [e.currentTarget.dataset.field]: e.detail.value, confirmationToken: '', nextToken: '' }) },
  facetPick(e) {
    if (this.data.busy) return
    const field = e.currentTarget.dataset.field, value = Number(e.detail.value)
    this.setData({ [field]: value, confirmationToken: '', nextToken: '' })
    if (field === 'objectIndex') {
      const states = value === 2 ? ['全部状态', '未取消', '已报名', '候补', '已取消'] : value === 3 ? ['全部状态', '未取消', '待审核', '已批准', '未通过', '已领取未归还', '已归还', '已取消'] : ['全部状态']
      this.setData({ states, stateIndex: 0, categoryIndex: 0, location: '', entity: '', timeIndex: value === 1 ? 5 : 0 })
    }
  },
  async searchFacets() {
    const d = this.data
    if (d.busy) return
    if (d.timeIndex === 9 && !d.date) return wx.showToast({ title: '请选择具体日期', icon: 'none' })
    const intent = ['ACTIVITIES', 'EQUIPMENT', 'MY_REGISTRATIONS', 'MY_LOANS'][d.objectIndex]
    const timeOption = ['ANY', 'TODAY', 'TOMORROW', 'DAY_AFTER_TOMORROW', 'PAST', 'CURRENT', 'FUTURE', 'THIS_WEEK', 'WEEKEND', 'DATE'][d.timeIndex]
    if (intent === 'EQUIPMENT' && ['ANY', 'PAST', 'FUTURE'].includes(timeOption)) return wx.showToast({ title: '库存请选择当前或具体时段', icon: 'none' })
    const statuses = intent === 'MY_REGISTRATIONS' ? ['ANY', 'NON_CANCELLED', 'REGISTERED', 'WAITLISTED', 'CANCELLED'] : intent === 'MY_LOANS' ? ['ANY', 'NON_CANCELLED', 'PENDING', 'APPROVED', 'REJECTED', 'CHECKED_OUT', 'RETURNED', 'CANCELLED'] : ['ANY']
    const body = { intent, entity: d.entity.trim() || (intent === 'EQUIPMENT' ? 'ALL' : null), category: ['ACTIVITIES', 'MY_REGISTRATIONS'].includes(intent) ? [null, 'SPORT', 'ART', 'TECH', 'VOLUNTEER', 'OTHER'][d.categoryIndex] : null,
      timeOption, date: timeOption === 'DATE' ? d.date : null, status: statuses[d.stateIndex], location: ['ACTIVITIES', 'MY_REGISTRATIONS'].includes(intent) ? d.location.trim() || null : null }
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true, answer: '', items: [], status: '', confirmationToken: '', nextToken: '', hasMore: false })
    try { const result = await request('/assistant/query', 'POST', body); if (generation === this.queryGeneration) this.accept(result) }
    catch (e) {} finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  accept(result, append = false) {
    const labels = { REGISTERED: '已报名', WAITLISTED: '候补', CANCELLED: '已取消', PENDING: '待审核', APPROVED: '已批准', REJECTED: '未通过', CHECKED_OUT: '已领取未归还', RETURNED: '已归还', PUBLISHED: '已发布' }
    const items = (result.items || []).map(r => ({ ...r, displayName: r.title || r.equipmentName || r.name, startLabel: (r.startTime || r.plannedStart || '').replace('T', ' '), endLabel: (r.endTime || r.plannedEnd || '').replace('T', ' '), stateLabel: labels[r.registrationStatus || r.status] || '' }))
    const change = { answer: result.answer, items: append ? this.data.items.concat(items) : items, intent: result.intent, status: result.status || '', interpretation: result.interpretation || '', corrections: result.corrections || [], confirmationToken: result.confirmationToken || '', nextToken: result.nextToken || '', hasMore: !!result.hasMore }
    if (result.plan && result.status === 'CONFIRM_SCOPE') {
      const p = result.plan, objectIndex = ['ACTIVITIES', 'EQUIPMENT', 'MY_REGISTRATIONS', 'MY_LOANS'].indexOf(p.intent)
      change.states = objectIndex === 2 ? ['全部状态', '未取消', '已报名', '候补', '已取消'] : objectIndex === 3 ? ['全部状态', '未取消', '待审核', '已批准', '未通过', '已领取未归还', '已归还', '已取消'] : ['全部状态']
      Object.assign(change, { objectIndex, entity: p.entity === 'ALL' ? '' : p.entity || '', location: p.location || '', date: p.date || '', categoryIndex: [null, 'SPORT', 'ART', 'TECH', 'VOLUNTEER', 'OTHER'].indexOf(p.category), timeIndex: ['ANY', 'TODAY', 'TOMORROW', 'DAY_AFTER_TOMORROW', 'PAST', 'CURRENT', 'FUTURE', 'THIS_WEEK', 'WEEKEND', 'DATE'].indexOf(p.timeOption) })
      change.stateIndex = (objectIndex === 2 ? ['ANY', 'NON_CANCELLED', 'REGISTERED', 'WAITLISTED', 'CANCELLED'] : objectIndex === 3 ? ['ANY', 'NON_CANCELLED', 'PENDING', 'APPROVED', 'REJECTED', 'CHECKED_OUT', 'RETURNED', 'CANCELLED'] : ['ANY']).indexOf(p.status)
    }
    if (result.status === 'CLARIFY') change.facetOpen = true
    this.setData(change)
  },
  async confirmScope() {
    if (this.data.busy || !this.data.confirmationToken) return
    const token = this.data.confirmationToken
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true })
    try {
      const agreed = await confirm(this.data.answer)
      if (!agreed || generation !== this.queryGeneration || token !== this.data.confirmationToken) return
      const result = await request('/assistant/confirm', 'POST', { token }); if (generation === this.queryGeneration) this.accept(result)
    }
    catch (e) { if (generation === this.queryGeneration) this.setData({ confirmationToken: '' }) }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  async more() {
    if (this.data.busy || !this.data.nextToken) return
    const token = this.data.nextToken, generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true, nextToken: '' })
    try { const result = await request('/assistant/more', 'POST', { token }); if (generation === this.queryGeneration) this.accept(result, result.status === 'QUERY') }
    catch (e) {} finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  async ask() {
    if (this.data.busy) return
    if (!this.data.question.trim()) return wx.showToast({ title: '先输入想问的问题吧', icon: 'none' })
    if (wx.hideKeyboard) wx.hideKeyboard()
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true, answer: '', items: [], intent: '', status: '', interpretation: '', corrections: [], correcting: false, confirmationToken: '', nextToken: '', hasMore: false })
    try { const result = await request('/assistant', 'POST', { question: this.data.question.trim() }); if (generation === this.queryGeneration) this.accept(result) }
    catch (e) {} finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  open(e) { if (this.data.intent === 'ACTIVITIES' || this.data.intent === 'MY_REGISTRATIONS') wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) }
})
