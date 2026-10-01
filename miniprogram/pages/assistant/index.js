const { request, guard } = require('../../utils/request')
const { keyboard } = require('../../utils/keyboard')
Page({
  data: { question: '', answer: '', busy: false, suggestions: ['这周末有什么活动？', '我的报名活动有哪些？', '相机现在还有多少？'], items: [], keyboardHeight: 0 },
  onLoad() { if (guard()) this.keyboardLayout = keyboard(this, 'keyboardHeight') }, input(e) { this.setData({ question: e.detail.value }) },
  focusQuestion(e) { this.keyboardLayout.focus('#assistant-query', 16, '', e && e.detail) },
  blurQuestion() { this.keyboardLayout.blur() },
  keyboardChange(e) { this.keyboardLayout.change(e) },
  onHide() { if (this.keyboardLayout) this.keyboardLayout.reset() },
  onUnload() { this.onHide() },
  suggestion(e) { this.setData({ question: e.currentTarget.dataset.question }); this.ask() },
  async ask() {
    if (this.data.busy) return
    if (!this.data.question.trim()) return wx.showToast({ title: '先输入想问的问题吧', icon: 'none' })
    if (wx.hideKeyboard) wx.hideKeyboard()
    this.setData({ busy: true })
    try { const result = await request('/assistant', 'POST', { question: this.data.question.trim() }); this.setData({ answer: result.answer, items: result.items.map(r => ({ ...r, displayName: r.title || r.equipmentName || r.name })), intent: result.intent }) }
    catch (e) {} finally { this.setData({ busy: false }) }
  },
  open(e) { if (this.data.intent === 'ACTIVITIES' || this.data.intent === 'MY_REGISTRATIONS') wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) }
})
