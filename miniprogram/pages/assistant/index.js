const { request, guard } = require('../../utils/request')
Page({
  data: { question: '', answer: '', busy: false, suggestions: ['这周末有什么活动？', '我的报名活动有哪些？', '相机现在还有多少？'], items: [] },
  onLoad() { guard() }, input(e) { this.setData({ question: e.detail.value }) },
  suggestion(e) { this.setData({ question: e.currentTarget.dataset.question }); this.ask() },
  async ask() {
    if (this.data.busy || !this.data.question.trim()) return
    this.setData({ busy: true })
    try { const result = await request('/assistant', 'POST', { question: this.data.question.trim() }); this.setData({ answer: result.answer, items: result.items.map(r => ({ ...r, displayName: r.title || r.equipmentName || r.name })), intent: result.intent }) }
    catch (e) {} finally { this.setData({ busy: false }) }
  },
  open(e) { if (this.data.intent === 'ACTIVITIES' || this.data.intent === 'MY_REGISTRATIONS') wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) }
})
