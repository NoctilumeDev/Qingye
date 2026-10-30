const { request, guard } = require('./request')
const { keyboard } = require('./keyboard')
const { refreshTabBar } = require('./navigation')
const contract = require('./assistant-contract')
const feedbackCopy = require('./assistant-feedback-copy')
const display = require('./assistant-display')
const results = require('./assistant-results')
const emptyFeedback = { feedbackIndex: -1, feedbackText: '', feedbackNote: '' }
const emptyTokens = { confirmationToken: '', nextToken: '', clarificationToken: '', retryToken: '', feedbackToken: '' }
const emptyPresentation = { statusTitle: '', statusHint: '', statusTone: 'neutral', showAnswerText: false }
function createAssistantPage() { return {
  data: { manual: false, startOpen: false, composing: false, composeText: '', supplements: [], busyAction: '', contextOpen: false, scopeOpen: false, question: '', answer: '', busy: false, status: '', interpretation: '', correcting: false, ...emptyTokens, ...emptyFeedback, feedbackCopy,
    ...emptyPresentation, hasMore: false, facetOpen: false,
    suggestions: ['ACTIVITIES', 'EQUIPMENT', 'MY_REGISTRATIONS'].map(intent => contract.entryCommands.find(c => c.intent === intent).label),
    items: [], sections: [], manualDrafts: [], draftSummaries: [], missingConditions: [], completionDraft: null, followupQuestion: '', replyText: '', recoveryMessage: '', keyboardHeight: 0 },
  onLoad() {
    if (guard()) this.keyboardLayout = keyboard(this, 'keyboardHeight')
    this.pageSession = wx.getStorageSync ? wx.getStorageSync('qingye-token') : ''
  },
  navigateManual(queries = []) {
    if (this.data.busy) return
    const session = this.pageSession
    wx.navigateTo({ url: '/pages/assistant-manual/index', success(result) { if (queries.length) result.eventChannel.emit('queryDraft', { session, queries }) } })
  },
  returnToStart() { if (!this.data.busy) { if (wx.hideKeyboard) wx.hideKeyboard(); this.setData({ startOpen: true, composing: true, composeText: '', correcting: false, ...emptyFeedback }); this.scrollToStart() } },
  editQuestion() { if (!this.data.busy) { this.setData({ startOpen: false, composing: true, composeText: this.data.question, correcting: true }); this.scrollToStart() } },
  cancelCompose() { if (!this.data.busy) this.setData({ startOpen: false, composing: false, composeText: '', correcting: false }) },
  scrollToStart() { if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 180 }) },
  toggleContext() { this.setData({ contextOpen: !this.data.contextOpen }) },
  toggleScope(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset.index || 0)
    this.setData({ sections: this.data.sections.map((s, i) => i === index ? { ...s, scopeOpen: !s.scopeOpen } : s) })
  },
  focusReply(e) { if (this.keyboardLayout) this.keyboardLayout.focus('#clarification-submit', 16, '', e && e.detail) },
  focusCorrection(e) { if (this.keyboardLayout) this.keyboardLayout.focus('#correction-submit', 16, '', e && e.detail) },
  onShow() {
    if (!guard()) return
    refreshTabBar(this, '/pages/assistant/index')
    const session = wx.getStorageSync ? wx.getStorageSync('qingye-token') : ''
    if (this.pageSession != null && session !== this.pageSession) {
      this.onHide(); this.lastPreviewRequest = null; this.setData({ manualDrafts: [], completionDraft: null })
      this.setData({ ...emptyTokens, ...emptyFeedback, ...emptyPresentation, startOpen: false, composing: false, composeText: '', supplements: [], busyAction: '', question: '', answer: '', items: [], sections: [], status: '', intent: '', interpretation: '', replyText: '', followupQuestion: '', facetOpen: false, completionDraft: null, draftSummaries: [], missingConditions: [], recoveryMessage: '' })
    }
    this.pageSession = session
  },
  input(e) { if (!this.data.busy) { if (this.data.composing) { this.setData({ composeText: e.detail.value }); return }; this.lastPreviewRequest = null; this.setData({ question: e.detail.value, ...emptyTokens, ...emptyPresentation, sections: [], items: [], answer: '', interpretation: '', intent: '', status: '', hasMore: false, recoveryMessage: '' }) } },
  replyInput(e) { if (!this.data.busy) this.setData({ replyText: e.detail.value }) },
  focusQuestion(e) { this.keyboardLayout.focus('#assistant-query', 16, '', e && e.detail) },
  blurQuestion() { this.keyboardLayout.blur() }, keyboardChange(e) { this.keyboardLayout.change(e) },
  onHide() {
    const bar = this.getTabBar && this.getTabBar(); if (bar) bar.setData({ hidden: false })
    this.setData({ ...emptyFeedback })
    this.queryGeneration = (this.queryGeneration || 0) + 1
    if (this.data.busy) this.setData({ ...emptyTokens, sections: this.data.sections.map(s => ({ ...s, ...emptyTokens })), busy: false, busyAction: '', recoveryMessage: '刚才还没找完，你可以重新试一次。' })
    if (this.keyboardLayout) this.keyboardLayout.reset()
  },
  onUnload() { this.onHide(); this.setData({ ...emptyTokens, sections: [] }) },
  suggestion(e) { if (this.data.busy) return; this.setData({ [this.data.composing ? 'composeText' : 'question']: e.currentTarget.dataset.question }); return this.ask() },
  invalidate() { this.setData({ ...emptyTokens, sections: this.data.sections.map(s => ({ ...s, ...emptyTokens })), recoveryMessage: '' }) },
  found(e) {
    if (this.data.busy) return
    const index = Number(e.currentTarget.dataset.index), sections = this.data.sections.slice()
    if (!sections[index] || sections[index].status !== 'QUERY') return
    sections[index] = { ...sections[index], feedbackDone: true }
    this.setData({ sections, ...emptyFeedback })
  },
  missed(e) {
    if (this.data.busy) return
    const index = Number(e.currentTarget.dataset.index), section = this.data.sections[index]
    if (section && section.status === 'QUERY' && section.feedbackToken && section.canContinue) { this.setData({ feedbackIndex: index, feedbackText: '', feedbackNote: '' }); this.scrollToStart() }
  },
  feedbackInput(e) { if (!this.data.busy) this.setData({ feedbackText: e.detail.value }) },
  cancelFeedback() { if (!this.data.busy) this.setData({ ...emptyFeedback }) },
  async continueFeedback() {
    const section = this.data.sections[this.data.feedbackIndex]
    if (this.data.busy || !section || !section.feedbackToken || !section.canContinue) return
    const correction = this.data.feedbackText.trim()
    if (!correction) return wx.showToast({ title: feedbackCopy.missing, icon: 'none' })
    const token = section.feedbackToken, generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    if (wx.hideKeyboard) wx.hideKeyboard()
    this.setData({ busy: true, busyAction: 'correction', feedbackNote: '', supplements: this.data.supplements.concat(correction) })
    try {
      const result = await request('/assistant/feedback', 'POST', { token, correction }, { timeout: 20000 })
      if (generation !== this.queryGeneration) return
      this.lastPreviewRequest = null; this.setData({ manualDrafts: [], completionDraft: null }); this.accept(result)
      this.setData({ ...emptyFeedback, busy: false, feedbackNote: feedbackCopy.states[result.feedbackState] || '' })
    } catch (e) {
      if (generation === this.queryGeneration) { this.invalidate(); this.setData({ feedbackIndex: -1, feedbackNote: feedbackCopy.failed }) }
    } finally { if (generation === this.queryGeneration) this.setData({ busy: false, busyAction: '' }) }
  },
  correct() {
    if (this.data.busy) return
    const queries = this.data.manualDrafts.length ? this.data.manualDrafts : this.data.completionDraft ? [this.data.completionDraft] : this.data.sections.filter(s => s.plan).map(s => this.bodyOf(s.plan))
    this.invalidate()
    this.navigateManual(queries)
  },
  toggleDirectory() { if (!this.data.busy) this.navigateManual() },
  bodyOf(p) { return Object.fromEntries(['intent', 'entity', 'category', 'timeOption', 'date', 'status', 'location'].map(k => [k, p[k] == null ? null : p[k]])) },
  timeLabel(key, intent) { return (contract.timeLabelsByIntent[intent] || {})[key] || contract.timeLabels[contract.times.indexOf(key)] },
  summaryOf(p) {
    const object = contract.intentLabels[contract.intents.indexOf(p.intent)] || '对象待补充', time = this.timeLabel(p.timeOption, p.intent) || '时间待补充'
    return [object, p.entity === 'ALL' ? '全部器材' : p.entity, time, p.date, p.category && contract.categoryLabels[contract.categories.indexOf(p.category)], p.location, p.status && contract.stateLabels[p.status]].filter(Boolean).join(' · ')
  },
  section: results.section,
  readableAnswer: results.readableAnswer,
  readableScope: results.readableScope,
  presentation: results.presentation,
  batchPresentation(sections) {
    if (sections.some(section => section.status === 'CONFIRM_SCOPE' && section.confirmationToken)) return { status: 'CONFIRM_SCOPE', statusTitle: '还有查询范围待确认', statusHint: '已完成的结果保留在下方，其余项确认后才会读取。', statusTone: 'pending', showAnswerText: false }
    const stopped = sections.find(section => section.status && section.status !== 'QUERY')
    if (stopped) return { status: stopped.status, ...this.presentation(stopped.status, sections) }
    return { status: 'QUERY', ...this.presentation('QUERY', sections) }
  },
  accept(result, append = false, index = null) {
    if (result.contractVersion && result.contractVersion !== contract.version) { this.invalidate(); this.setData({ status: 'SYSTEM_ERROR', items: [], sections: [], answer: '请更新小程序后再试，当前版本暂时无法查询。', ...this.presentation('SYSTEM_ERROR', []) }); return }
    const s = this.section(result)
    if (index != null && this.data.intent === 'BATCH') {
      const sections = this.data.sections.slice(), previous = sections[index]
      sections[index] = { ...s, key: previous.key, items: append ? previous.items.concat(s.items) : s.items }
      sections[index].resultTitle = display.resultTitle(sections[index].intent, sections[index].items.length)
      sections[index].scopeOpen = !!previous.scopeOpen
      this.setData({ sections, busy: false, hasMore: sections.some(section => section.hasMore), ...this.batchPresentation(sections) }); return
    }
    const sections = result.sections ? result.sections.map((value, i) => ({ ...this.section(value), key: 'query-' + i })) : [{ ...s, key: 'query-0', items: append ? this.data.items.concat(s.items) : s.items }]
    sections.forEach((section, i) => { section.resultTitle = display.resultTitle(section.intent, section.items.length); if (append) section.scopeOpen = !!(this.data.sections[i] && this.data.sections[i].scopeOpen) })
    const displayAnswer = this.readableAnswer(result)
    const change = { answer: displayAnswer, items: result.sections ? [] : sections[0].items, sections, intent: result.intent, status: result.status || '', contextOpen: false, scopeOpen: false, correcting: false, interpretation: result.reason === 'MODEL_UNAVAILABLE' ? '' : (result.status === 'QUERY' ? this.readableScope(result.interpretation) : (result.interpretation || '')), ...emptyTokens, ...this.presentation(result.status || '', sections),
      confirmationToken: result.confirmationToken || '', nextToken: result.nextToken || '', clarificationToken: result.clarificationToken || '', retryToken: result.retryToken || '', hasMore: !!result.hasMore, followupQuestion: result.question || '', recoveryMessage: '' }
    this.setData(change)
    const drafts = result.draft ? result.draft.queries || [result.draft] : []
    this.setData({ manualDrafts: drafts.map(p => ({ ...this.bodyOf(p), reason: p.reason })), completionDraft: result.draft && !result.draft.queries ? result.draft : null,
      draftSummaries: drafts.map((p, i) => ({ _key: 'draft-' + i, text: this.summaryOf(p) })),
      missingConditions: (result.missingConditions || []).map((condition, i) => ({ ...condition, _key: 'missing-' + condition.queryIndex + '-' + (condition.field || condition.label) + '-' + i })), replyText: '' })
    this.setData({ facetOpen: result.status === 'CLARIFY' && !result.clarificationToken })
  },
  async load(url, body) {
    this.setData({ ...emptyFeedback })
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    if (url === '/assistant/query' || url === '/assistant/queries') this.lastPreviewRequest = { url, body }
    this.setData({ busy: true, busyAction: 'query', answer: '', items: [], sections: [], intent: '', interpretation: '', status: '', ...emptyTokens, ...emptyPresentation, hasMore: false, recoveryMessage: '' })
    this.scrollToStart()
    const timeout = ['/assistant', '/assistant/clarify', '/assistant/retry'].includes(url) ? 20000 : 10000
    try { const result = await request(url, 'POST', body, { timeout }); if (generation === this.queryGeneration) { this.accept(result); this.setData({ busy: false }) } }
    catch (e) { if (generation === this.queryGeneration) this.setData({ status: 'SYSTEM_ERROR', facetOpen: false, recoveryMessage: '这次没能找完，可以再试一次，或自己选择条件。' }) }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false, busyAction: '' }) }
  },
  async reply() { const token = this.data.clarificationToken; if (this.data.busy || !token) return; const answer = this.data.replyText.trim(); if (!answer) return wx.showToast({ title: '先告诉我一点线索吧', icon: 'none' }); if (wx.hideKeyboard) wx.hideKeyboard(); this.setData({ supplements: this.data.supplements.concat(answer) }); return this.load('/assistant/clarify', { token, answer }) },
  async retry() { if (!this.data.busy && this.data.retryToken) return this.load('/assistant/retry', { token: this.data.retryToken }) },
  async confirmScope(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset.index || 0), section = this.data.sections[index]
    const token = section && section.confirmationToken || this.data.confirmationToken
    if (this.data.busy || !token) return
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true })
    try {
      const current = this.data.sections[index]; if (token !== (current && current.confirmationToken || this.data.confirmationToken)) return
      const result = await request('/assistant/confirm', 'POST', { token }); if (generation === this.queryGeneration) this.accept(result, false, index)
    } catch (e) { if (generation === this.queryGeneration) { this.invalidate(); this.setData({ recoveryMessage: '刚才的查询结果没有收到，请重新确认后再试。' }) } }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  async more(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset.index || 0), section = this.data.sections[index]
    const token = section && section.nextToken || this.data.nextToken
    if (this.data.busy || !token) return
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true, busyAction: 'more' })
    try { const result = await request('/assistant/more', 'POST', { token }); if (generation === this.queryGeneration) this.accept(result, result.status === 'QUERY', index) }
    catch (e) { if (generation === this.queryGeneration) { this.invalidate(); this.setData({ recoveryMessage: '下一页暂时没加载出来，已显示的记录还在。可以重新查找。' }) } }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false, busyAction: '' }) }
  },
  async restart() {
    if (this.data.busy) return
    const plans = this.data.sections.map(s => s.plan).filter(p => p && p.action === 'QUERY').map(p => this.bodyOf(p))
    if (plans.length) return this.load(plans.length === 1 ? '/assistant/query' : '/assistant/queries', plans.length === 1 ? plans[0] : { queries: plans })
    if (this.lastPreviewRequest) return this.load(this.lastPreviewRequest.url, this.lastPreviewRequest.body)
    return this.ask()
  },
  async ask() {
    if (this.data.busy) return
    const question = (this.data.composing ? this.data.composeText : this.data.question).trim()
    if (!question) return wx.showToast({ title: '先输入想问的问题吧', icon: 'none' })
    this.setData({ question, startOpen: false, composing: false, composeText: '', supplements: [] })
    if (wx.hideKeyboard) wx.hideKeyboard()
    this.lastPreviewRequest = null
    this.setData({ manualDrafts: [], draftSummaries: [], missingConditions: [], completionDraft: null, followupQuestion: '', replyText: '', correcting: false, facetOpen: false })
    return this.load('/assistant', { question: this.data.question.trim() })
  },
  open(e) { const index = Number(e.currentTarget.dataset.index || 0), intent = this.data.sections[index] && this.data.sections[index].intent || this.data.intent; if (intent === 'ACTIVITIES' || intent === 'MY_REGISTRATIONS') wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) }
} }
module.exports = { createAssistantPage }
