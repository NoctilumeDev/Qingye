const { request, guard } = require('./request')
const { keyboard } = require('./keyboard')
const { refreshTabBar } = require('./navigation')
const contract = require('./assistant-contract')
const feedbackCopy = require('./assistant-feedback-copy')
const display = require('./assistant-display')
const emptyFeedback = { feedbackIndex: -1, feedbackText: '', feedbackNote: '' }
const emptyTokens = { confirmationToken: '', nextToken: '', clarificationToken: '', retryToken: '', feedbackToken: '' }
const emptyPresentation = { statusTitle: '', statusHint: '', statusTone: 'neutral', showAnswerText: false }
function createAssistantPage(manual = false) { return {
  data: { manual, composing: false, composeText: '', supplements: [], busyAction: '', batchOpen: false, contextOpen: false, scopeOpen: false, question: '', answer: '', busy: false, status: '', interpretation: '', correcting: false, ...emptyTokens, ...emptyFeedback, feedbackCopy,
    ...emptyPresentation, hasMore: false, facetOpen: false, directoryOpen: manual, objects: contract.intentLabels, objectIndex: 0, times: contract.timeLabels, timeKeys: contract.times, timeIndex: 0,
    categories: ['全部分类', ...contract.categoryLabels], categoryIndex: 0, states: ['全部状态'], stateIndex: 0,
    entity: '', location: '', date: '', suggestions: ['ACTIVITIES', 'EQUIPMENT', 'MY_REGISTRATIONS'].map(intent => contract.entryCommands.find(c => c.intent === intent).label),
    items: [], sections: [], queuedQueries: [], queuedSummaries: [], hasPendingQueries: false, draftSummaries: [], missingConditions: [], facetPending: [], completionDraft: null, entityPending: false, editingIndex: -1, followupQuestion: '', replyText: '', recoveryMessage: '', keyboardHeight: 0 },
  onLoad() {
    if (guard()) this.keyboardLayout = keyboard(this, 'keyboardHeight')
    this.pageSession = wx.getStorageSync ? wx.getStorageSync('qingye-token') : ''
    if (manual && this.getOpenerEventChannel) this.getOpenerEventChannel().on('queryDraft', payload => {
      if (payload.session !== this.pageSession) return
      this.receiveDraft(payload)
    })
  },
  receiveDraft(payload) {
    const queries = payload.queries || []
    if (!queries.length) return
    this.setData({ facetOpen: true, directoryOpen: false })
    if (queries.length > 1) { this.syncQueue(queries, 0); this.applyDraft(queries[0], !!queries[0]._needsReview) }
    else this.applyDraft(queries[0], !!queries[0]._needsReview)
  },
  navigateManual(queries = []) {
    if (this.data.busy) return
    const session = this.pageSession
    wx.navigateTo({ url: '/pages/assistant-manual/index', success(result) { if (queries.length) result.eventChannel.emit('queryDraft', { session, queries }) } })
  },
  editQuestion() { if (!this.data.busy) { this.setData({ composing: true, composeText: this.data.question, correcting: true }); this.scrollToStart() } },
  cancelCompose() { if (!this.data.busy) this.setData({ composing: false, composeText: '', correcting: false }) },
  scrollToStart() { if (!manual && wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 180 }) },
  toggleContext() { this.setData({ contextOpen: !this.data.contextOpen }) },
  toggleScope(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset.index || 0)
    this.setData({ sections: this.data.sections.map((s, i) => i === index ? { ...s, scopeOpen: !s.scopeOpen } : s) })
  },
  toggleBatch() { if (!this.data.busy) this.setData({ batchOpen: !this.data.batchOpen }) },
  focusReply(e) { if (this.keyboardLayout) this.keyboardLayout.focus('#clarification-submit', 16, '', e && e.detail) },
  focusCorrection(e) { if (this.keyboardLayout) this.keyboardLayout.focus('#correction-submit', 16, '', e && e.detail) },
  onShow() {
    if (!guard()) return
    if (!manual) refreshTabBar(this, '/pages/assistant/index')
    const session = wx.getStorageSync ? wx.getStorageSync('qingye-token') : ''
    if (this.pageSession != null && session !== this.pageSession) {
      this.onHide(); this.lastPreviewRequest = null; this.syncQueue([])
      this.setData({ ...emptyTokens, ...emptyFeedback, ...emptyPresentation, composing: false, composeText: '', supplements: [], busyAction: '', question: '', answer: '', items: [], sections: [], status: '', intent: '', interpretation: '', entity: '', location: '', date: '', replyText: '', followupQuestion: '', facetOpen: false, directoryOpen: manual, completionDraft: null, draftSummaries: [], missingConditions: [], facetPending: [], entityPending: false, recoveryMessage: '' })
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
  suggestion(e) { if (this.data.busy) return; this.setData({ question: e.currentTarget.dataset.question }); return this.ask() },
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
      this.lastPreviewRequest = null; this.syncQueue([]); this.accept(result)
      this.setData({ ...emptyFeedback, busy: false, feedbackNote: feedbackCopy.states[result.feedbackState] || '' })
    } catch (e) {
      if (generation === this.queryGeneration) { this.invalidate(); this.setData({ feedbackIndex: -1, feedbackNote: feedbackCopy.failed }) }
    } finally { if (generation === this.queryGeneration) this.setData({ busy: false, busyAction: '' }) }
  },
  correct() {
    if (this.data.busy) return
    if (!manual) {
      const queries = this.data.queuedQueries.length ? this.data.queuedQueries : this.data.completionDraft ? [{ ...this.data.completionDraft, _needsReview: true }] : this.data.sections.filter(s => s.plan).map(s => this.bodyOf(s.plan))
      this.invalidate()
      this.navigateManual(queries)
      return
    }
    this.manualNewQuery = false
    this.invalidate()
    if (this.data.sections.length > 1) this.syncQueue(this.data.sections.filter(s => s.plan).map(s => this.bodyOf(s.plan)), 0)
    this.setData({ correcting: false, directoryOpen: false, facetOpen: true })
    if (this.data.queuedQueries.length) this.applyDraft(this.data.queuedQueries[0])
  },
  toggleDirectory() { if (!this.data.busy) { if (!manual) this.navigateManual(); else this.setData({ directoryOpen: true, facetOpen: false }) } },
  closeManual() { if (!this.data.busy) this.setData({ directoryOpen: true, facetOpen: false }) },
  openDirectory(e) { this.selectDirectory(Number(e.currentTarget.dataset.index), '') },
  selectDirectory(index, search) {
    if (this.data.busy || !contract.intents[index]) return
    this.manualNewQuery = true
    const intent = contract.intents[index], keys = contract.timeOptionsByIntent[intent] || contract.times
    this.invalidate(); this.lastPreviewRequest = null; this.syncQueue([])
    this.setData({ facetOpen: true, directoryOpen: false, objectIndex: index, timeKeys: keys, times: keys.map(k => this.timeLabel(k, intent)), timeIndex: keys.indexOf(intent === 'EQUIPMENT' ? 'CURRENT' : 'ANY'),
      states: contract.states[intent].map(k => contract.stateLabels[k]), stateIndex: 0, entity: search || '', location: '', date: '', categoryIndex: 0,
      facetPending: [], completionDraft: null, entityPending: false, editingIndex: -1 })
  },
  facetField(e) {
    if (this.data.busy) return
    const field = e.currentTarget.dataset.field, value = e.detail.value
    this.invalidate()
    if (this.data.completionDraft) {
      const draft = { ...this.data.completionDraft, [field]: String(value).trim() || null }
      this.applyDraft(draft, true)
    } else this.setData({ [field]: value })
  },
  facetPick(e) {
    if (this.data.busy) return
    this.invalidate()
    const field = e.currentTarget.dataset.field, value = Number(e.detail.value)
    if (field !== 'objectIndex' && this.data.objectIndex < 0) return wx.showToast({ title: '请先选择查询对象', icon: 'none' })
    if (this.data.completionDraft) {
      const draft = { ...this.data.completionDraft }
      if (field === 'objectIndex') {
        const intent = contract.intents[value]
        if (!intent) return
        if ((draft.category || draft.location) && !['ACTIVITIES', 'MY_REGISTRATIONS'].includes(intent)) return wx.showToast({ title: '已知分类或地点不适用该对象，请先改口', icon: 'none' })
        draft.intent = intent
      } else if (field === 'timeIndex') { draft.timeOption = this.data.timeKeys[value]; if (draft.timeOption !== 'DATE') draft.date = null }
      else if (field === 'stateIndex') draft.status = contract.states[draft.intent][value]
      else if (field === 'categoryIndex') draft.category = [null, ...contract.categories][value]
      this.applyDraft(draft, true); return
    }
    const resolved = { objectIndex: 'intent', timeIndex: 'timeOption', stateIndex: 'status' }[field]
    this.setData({ [field]: value, facetPending: this.data.facetPending.filter(k => k !== resolved) })
    if (field === 'objectIndex') {
      const intent = contract.intents[value], keys = contract.timeOptionsByIntent[intent] || contract.times
      this.setData({ states: contract.states[intent].map(s => contract.stateLabels[s]), stateIndex: this.data.facetPending.includes('status') ? -1 : 0, categoryIndex: 0, location: '', entity: '', timeKeys: keys, times: keys.map(k => this.timeLabel(k, intent)), timeIndex: this.data.facetPending.includes('timeOption') ? -1 : keys.indexOf(intent === 'EQUIPMENT' ? 'CURRENT' : 'ANY') })
    }
  },
  bodyOf(p) { return Object.fromEntries(['intent', 'entity', 'category', 'timeOption', 'date', 'status', 'location'].map(k => [k, p[k] == null ? null : p[k]])) },
  timeLabel(key, intent) { return (contract.timeLabelsByIntent[intent] || {})[key] || contract.timeLabels[contract.times.indexOf(key)] },
  summaryOf(p) {
    const object = contract.intentLabels[contract.intents.indexOf(p.intent)] || '对象待补充', time = this.timeLabel(p.timeOption, p.intent) || '时间待补充'
    return [object, p.entity === 'ALL' ? '全部器材' : p.entity, time, p.date, p.category && contract.categoryLabels[contract.categories.indexOf(p.category)], p.location, p.status && contract.stateLabels[p.status]].filter(Boolean).join(' · ')
  },
  syncQueue(queries, editingIndex = -1) {
    const queuedQueries = queries.map(query => query._key ? query : { ...query, _key: 'queue-' + (this.queueSequence = (this.queueSequence || 0) + 1) })
    this.setData({ queuedQueries, batchOpen: queuedQueries.length > 0, queuedSummaries: queuedQueries.map(p => this.summaryOf(p)), hasPendingQueries: queuedQueries.some(p => p._needsReview), editingIndex })
  },
  facetBody() {
    const d = this.data, intent = contract.intents[d.objectIndex], timeOption = d.timeKeys[d.timeIndex]
    if (d.facetPending.length || !intent || !timeOption || !contract.states[intent][d.stateIndex]) { wx.showToast({ title: '请明确选择待补充条件，不会默认查全部', icon: 'none' }); return null }
    if (timeOption === 'DATE' && !d.date) { wx.showToast({ title: '请选择具体日期', icon: 'none' }); return null }
    if (intent === 'EQUIPMENT' && ['ANY', 'PAST', 'FUTURE'].includes(timeOption)) { wx.showToast({ title: '库存请选择当前或具体时段', icon: 'none' }); return null }
    return { intent, entity: d.entity.trim() || (intent === 'EQUIPMENT' ? 'ALL' : null), category: ['ACTIVITIES', 'MY_REGISTRATIONS'].includes(intent) ? [null, ...contract.categories][d.categoryIndex] : null,
      timeOption, date: timeOption === 'DATE' ? d.date : null, status: contract.states[intent][d.stateIndex], location: ['ACTIVITIES', 'MY_REGISTRATIONS'].includes(intent) ? d.location.trim() || null : null }
  },
  addFacet() {
    if (this.data.busy) return
    const body = this.facetBody(); if (!body) return
    const queued = this.data.queuedQueries.slice()
    if (this.data.editingIndex >= 0) queued[this.data.editingIndex] = { ...body, _key: queued[this.data.editingIndex]._key }
    else if (queued.length < contract.maxQueries) queued.push(body)
    else return wx.showToast({ title: '每次最多四项独立查询', icon: 'none' })
    this.invalidate(); this.syncQueue(queued)
  },
  editQueued(e) { if (!this.data.busy) { this.invalidate(); const index = Number(e.currentTarget.dataset.index); this.setData({ editingIndex: index, facetOpen: true }); this.applyDraft(this.data.queuedQueries[index]) } },
  removeQueued(e) { if (!this.data.busy) { this.invalidate(); this.syncQueue(this.data.queuedQueries.filter((_, i) => i !== Number(e.currentTarget.dataset.index))) } },
  async searchFacets() {
    if (this.data.busy) return
    if (this.data.editingIndex >= 0) { this.addFacet(); return this.previewQueued() }
    if (this.data.hasPendingQueries) return this.previewQueued()
    const body = this.facetBody(); if (body) { this.syncQueue([]); return this.load('/assistant/query', body) }
  },
  async previewQueued() {
    if (this.data.busy || !this.data.queuedQueries.length) return
    const unresolved = this.data.queuedQueries.findIndex(p => p._needsReview)
    if (unresolved >= 0) { wx.showToast({ title: '第' + (unresolved + 1) + '项仍需补充', icon: 'none' }); this.editQueued({ currentTarget: { dataset: { index: unresolved } } }); return }
    const queries = this.data.queuedQueries.map(p => this.bodyOf(p))
    return this.load(queries.length === 1 ? '/assistant/query' : '/assistant/queries', queries.length === 1 ? queries[0] : { queries })
  },
  chooseAllEntities() {
    if (this.data.busy || this.data.objectIndex < 0) return
    this.invalidate()
    if (this.data.completionDraft) this.applyDraft({ ...this.data.completionDraft, entity: contract.intents[this.data.objectIndex] === 'EQUIPMENT' ? 'ALL' : null, reason: this.data.completionDraft.reason === 'UNKNOWN_ENTITY' ? 'MISSING_INFO' : this.data.completionDraft.reason }, true)
    else this.setData({ entity: '', entityPending: false, facetPending: this.data.facetPending.filter(k => k !== 'entity') })
  },
  applyDraft(p, incomplete = false) {
    if (!p) return
    incomplete = incomplete || !!p._needsReview
    const change = {}, index = contract.intents.indexOf(p.intent)
    if (incomplete) {
      const pending = []
      if (index < 0) pending.push('intent', 'timeOption', 'status')
      else { for (const field of contract.requiredFields[p.intent]) if (p[field] == null || field === 'timeOption' && !(contract.timeOptionsByIntent[p.intent] || contract.times).includes(p[field]) || field === 'status' && !contract.states[p.intent].includes(p[field])) pending.push(field); if (p.timeOption === 'DATE' && !p.date) pending.push('date') }
      if (p.entity == null && p.reason === 'UNKNOWN_ENTITY' && !pending.includes('entity')) pending.push('entity')
      Object.assign(change, { facetPending: pending, completionDraft: { ...p }, entityPending: pending.includes('entity'), objectIndex: index, timeIndex: -1, stateIndex: -1, entity: '', location: '', date: '', categoryIndex: 0 })
    } else Object.assign(change, { facetPending: [], completionDraft: null, entityPending: false })
    if (index >= 0) {
      change.objectIndex = index; change.states = contract.states[p.intent].map(s => contract.stateLabels[s]); change.timeKeys = contract.timeOptionsByIntent[p.intent] || contract.times; change.times = change.timeKeys.map(k => this.timeLabel(k, p.intent))
      if (!incomplete && index !== this.data.objectIndex) Object.assign(change, { timeIndex: 0, categoryIndex: 0, stateIndex: 0, entity: '', location: '', date: '' })
    }
    if (p.entity != null) change.entity = p.entity === 'ALL' ? '' : p.entity
    if (p.category != null) change.categoryIndex = [null, ...contract.categories].indexOf(p.category)
    if (p.location != null) change.location = p.location
    if (p.date != null) change.date = p.date
    if (p.timeOption != null) change.timeIndex = (change.timeKeys || this.data.timeKeys).indexOf(p.timeOption)
    if (p.status != null && index >= 0) change.stateIndex = contract.states[p.intent].indexOf(p.status)
    this.setData(change)
  },
  section(result) {
    const labels = { ...contract.stateLabels, PUBLISHED: '已发布' }
    const displayAnswer = result.status === 'CONFIRM_SCOPE' ? this.readableScope(result.answer) : this.readableAnswer(result)
    return { ...result, displayAnswer, scopeOpen: false, scopeRows: display.scope(result.plan), displayWarnings: (result.warnings || []).map(display.warning), canContinue: !!result.feedbackToken && (!result.gateRound || result.gateRound < (result.maxGateRounds || 2)), warnings: result.warnings || [], items: (result.items || []).map(r => ({ ...r, displayName: r.title || r.equipmentName || r.name, timeText: display.timeRange(r.startTime || r.plannedStart || '', r.endTime || r.plannedEnd || ''), startLabel: (r.startTime || r.plannedStart || '').replace('T', ' '), endLabel: (r.endTime || r.plannedEnd || '').replace('T', ' '), stateLabel: labels[r.registrationStatus || r.status] || '' })) }
  },
  readableAnswer(result) {
    return display.message(result) || result.answer
  },
  readableScope(text) {
    return (text || '').replace('公开活动（已发布且社团正常开放）', '已发布的公开活动（社团正常开放）').replace('每页10条，可继续加载。确认前不会开始查找，条件不对可以修改。', '每页10条，可继续加载。').replace('公开活动（仅已发布、社团启用）', '已发布的公开活动').replace('器材目录与库存（含停用器材，停用不代表可借）', '器材及库存（包含已停用器材）').replace('器材名称或业务别名为「', '器材名称或别名：「').replace('」（不做模糊扩词）', '」（精确匹配）').replace('上海时间：当前库存', '库存时间：当前').replace('全部对象', '名称：不限').replace('上海时间：全部时间；按活动起止时间匹配（非创建、报名或归还日期）', '活动时间：不限（北京时间）').replace('未查业务记录；请核对条件后确认，不对就修改。', '确认前不会开始查找。')
  },
  presentation(status, sections) {
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
  },
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
    if (result.plan && result.status === 'CONFIRM_SCOPE' && result.intent !== 'BATCH') {
      this.setData({ entity: '', location: '', date: '', categoryIndex: 0, stateIndex: 0 }); this.applyDraft(result.plan)
    }
    this.setData({ draftSummaries: [], missingConditions: (result.missingConditions || []).map((condition, i) => ({ ...condition, _key: 'missing-' + condition.queryIndex + '-' + (condition.field || condition.label) + '-' + i })), replyText: '' })
    if (result.draft) {
      if (!result.draft.queries) this.applyDraft(result.draft, true)
      this.setData({ draftSummaries: (result.draft.queries || [result.draft]).map((p, i) => ({ _key: 'draft-' + i, text: this.summaryOf(p) })) })
      if (result.draft.queries) this.syncQueue(result.draft.queries.map(p => ({ ...this.bodyOf(p), _needsReview: p.action !== 'QUERY', reason: p.reason })))
    }
    this.setData({ facetOpen: result.status === 'CLARIFY' && !result.clarificationToken, directoryOpen: false })
  },
  async load(url, body) {
    if ((url === '/assistant/query' || url === '/assistant/queries') && this.manualNewQuery) { this.setData({ question: '' }); this.manualNewQuery = false }
    this.setData({ ...emptyFeedback })
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    if (url === '/assistant/query' || url === '/assistant/queries') this.lastPreviewRequest = { url, body }
    this.setData({ busy: true, busyAction: 'query', answer: '', items: [], sections: [], intent: '', interpretation: '', status: '', ...emptyTokens, ...emptyPresentation, hasMore: false, recoveryMessage: '' })
    this.scrollToStart()
    const timeout = ['/assistant', '/assistant/clarify', '/assistant/retry'].includes(url) ? 20000 : 10000
    try { const result = await request(url, 'POST', body, { timeout }); if (generation === this.queryGeneration) { this.accept(result); this.setData({ busy: false }) } }
    catch (e) { if (generation === this.queryGeneration) this.setData({ status: 'SYSTEM_ERROR', facetOpen: false, directoryOpen: false, recoveryMessage: '这次没能找完，可以再试一次，或自己选择条件。' }) }
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
    if (manual) { this.setData({ directoryOpen: true, facetOpen: false }); return }
    return this.ask()
  },
  async ask() {
    if (manual) return
    this.manualNewQuery = false
    if (this.data.busy) return
    const question = (this.data.composing ? this.data.composeText : this.data.question).trim()
    if (!question) return wx.showToast({ title: '先输入想问的问题吧', icon: 'none' })
    this.setData({ question, composing: false, composeText: '', supplements: [] })
    if (wx.hideKeyboard) wx.hideKeyboard()
    this.lastPreviewRequest = null
    this.setData({ queuedQueries: [], queuedSummaries: [], hasPendingQueries: false, draftSummaries: [], missingConditions: [], facetPending: [], completionDraft: null, entityPending: false, editingIndex: -1, followupQuestion: '', replyText: '', correcting: false, facetOpen: false, directoryOpen: false, entity: '', location: '', date: '', categoryIndex: 0, stateIndex: 0, objectIndex: 0, timeKeys: contract.times, times: contract.timeLabels, timeIndex: 0, states: [contract.stateLabels.ANY] })
    return this.load('/assistant', { question: this.data.question.trim() })
  },
  open(e) { const index = Number(e.currentTarget.dataset.index || 0), intent = this.data.sections[index] && this.data.sections[index].intent || this.data.intent; if (intent === 'ACTIVITIES' || intent === 'MY_REGISTRATIONS') wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) }
} }
module.exports = { createAssistantPage }
