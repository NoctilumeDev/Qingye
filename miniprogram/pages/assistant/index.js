const { request, guard } = require('../../utils/request')
const { keyboard } = require('../../utils/keyboard')
Page({
  data: { question: '', answer: '', busy: false, status: '', interpretation: '', corrections: [], correcting: false, confirmationToken: '', suggestions: ['这周末有什么活动？', '我的报名活动有哪些？', '相机现在还有多少？'], items: [], keyboardHeight: 0 },
  onLoad() { if (guard()) this.keyboardLayout = keyboard(this, 'keyboardHeight') }, input(e) { if (!this.data.busy) this.setData({ question: e.detail.value, confirmationToken: '' }) },
  focusQuestion(e) { this.keyboardLayout.focus('#assistant-query', 16, '', e && e.detail) },
  blurQuestion() { this.keyboardLayout.blur() },
  keyboardChange(e) { this.keyboardLayout.change(e) },
  onHide() { this.queryGeneration = (this.queryGeneration || 0) + 1; this.setData({ confirmationToken: '', busy: false }); if (this.keyboardLayout) this.keyboardLayout.reset() },
  onUnload() { this.onHide() },
  suggestion(e) { if (this.data.busy) return; this.setData({ question: e.currentTarget.dataset.question }); return this.ask() },
  correct() { if (!this.data.busy) this.setData({ correcting: true, confirmationToken: '' }) },
  accept(result) { this.setData({ answer: result.answer, items: result.items.map(r => ({ ...r, displayName: r.title || r.equipmentName || r.name })), intent: result.intent, status: result.status || '', interpretation: result.interpretation || '', corrections: result.corrections || [], confirmationToken: result.confirmationToken || '' }) },
  async confirmScope() {
    if (this.data.busy || !this.data.confirmationToken) return
    const token = this.data.confirmationToken
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true })
    try { const result = await request('/assistant/confirm', 'POST', { token }); if (generation === this.queryGeneration) this.accept(result) }
    catch (e) { if (generation === this.queryGeneration) this.setData({ confirmationToken: '' }) }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  async ask() {
    if (this.data.busy) return
    if (!this.data.question.trim()) return wx.showToast({ title: '先输入想问的问题吧', icon: 'none' })
    if (wx.hideKeyboard) wx.hideKeyboard()
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true, answer: '', items: [], intent: '', status: '', interpretation: '', corrections: [], correcting: false, confirmationToken: '' })
    try { const result = await request('/assistant', 'POST', { question: this.data.question.trim() }); if (generation === this.queryGeneration) this.accept(result) }
    catch (e) {} finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  open(e) { if (this.data.intent === 'ACTIVITIES' || this.data.intent === 'MY_REGISTRATIONS') wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) }
})
