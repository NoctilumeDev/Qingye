const { request, guard, confirm } = require('../../utils/request')
const { keyboard } = require('../../utils/keyboard')
const contract = require('../../utils/assistant-contract')
const emptyTokens = { confirmationToken: '', nextToken: '', clarificationToken: '', amendmentToken: '', retryToken: '' }
Page({
  data: { question: '', answer: '', busy: false, status: '', interpretation: '', corrections: [], correcting: false, ...emptyTokens,
    hasMore: false, facetOpen: false, objects: contract.intentLabels, objectIndex: 0, times: contract.timeLabels, timeKeys: contract.times, timeIndex: 0,
    categories: ['全部分类', ...contract.categoryLabels], categoryIndex: 0, states: ['全部状态'], stateIndex: 0,
    entity: '', location: '', date: '', suggestions: ['查全部活动', '后天有什么活动？', '我的报名活动有哪些？', '相机现在还有多少？'],
    items: [], sections: [], queuedQueries: [], queuedSummaries: [], hasPendingQueries: false, draftSummaries: [], missingConditions: [], facetPending: [], completionDraft: null, entityPending: false, editingIndex: -1, followupQuestion: '', replyText: '', recoveryMessage: '', keyboardHeight: 0 },
  onLoad() { if (guard()) this.keyboardLayout = keyboard(this, 'keyboardHeight') },
  input(e) { if (!this.data.busy) { this.lastPreviewRequest = null; this.setData({ question: e.detail.value, ...emptyTokens, sections: [], items: [], answer: '', interpretation: '', corrections: [], intent: '', status: '', hasMore: false, recoveryMessage: '' }) } },
  replyInput(e) { if (!this.data.busy) this.setData({ replyText: e.detail.value }) },
  focusQuestion(e) { this.keyboardLayout.focus('#assistant-query', 16, '', e && e.detail) },
  blurQuestion() { this.keyboardLayout.blur() }, keyboardChange(e) { this.keyboardLayout.change(e) },
  onHide() {
    this.queryGeneration = (this.queryGeneration || 0) + 1
    if (this.data.busy) this.setData({ ...emptyTokens, sections: this.data.sections.map(s => ({ ...s, ...emptyTokens })), busy: false, recoveryMessage: '页面离开时请求尚未完成，请重新核对查询范围后继续。' })
    if (this.keyboardLayout) this.keyboardLayout.reset()
  },
  onUnload() { this.onHide(); this.setData({ ...emptyTokens, sections: [] }) },
  suggestion(e) { if (this.data.busy) return; this.setData({ question: e.currentTarget.dataset.question }); return this.ask() },
  invalidate() { this.setData({ ...emptyTokens, sections: this.data.sections.map(s => ({ ...s, ...emptyTokens })), recoveryMessage: '' }) },
  correct() {
    if (this.data.busy) return
    this.invalidate()
    if (this.data.sections.length > 1) this.syncQueue(this.data.sections.filter(s => s.plan).map(s => this.bodyOf(s.plan)), 0)
    this.setData({ correcting: true, facetOpen: true })
    if (this.data.queuedQueries.length) this.applyDraft(this.data.queuedQueries[0])
  },
  toggleFacets() { if (!this.data.busy) this.setData({ facetOpen: !this.data.facetOpen }) },
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
  syncQueue(queries, editingIndex = -1) { this.setData({ queuedQueries: queries, queuedSummaries: queries.map(p => this.summaryOf(p)), hasPendingQueries: queries.some(p => p._needsReview), editingIndex }) },
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
    if (this.data.editingIndex >= 0) queued[this.data.editingIndex] = body
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
    return { ...result, items: (result.items || []).map(r => ({ ...r, displayName: r.title || r.equipmentName || r.name, startLabel: (r.startTime || r.plannedStart || '').replace('T', ' '), endLabel: (r.endTime || r.plannedEnd || '').replace('T', ' '), stateLabel: labels[r.registrationStatus || r.status] || '' })) }
  },
  accept(result, append = false, index = null) {
    if (result.contractVersion && result.contractVersion !== contract.version) { this.invalidate(); this.setData({ status: 'SYSTEM_ERROR', items: [], sections: [], answer: '业务能力版本不一致，请更新小程序后重新核对。' }); return }
    const s = this.section(result)
    if (index != null && this.data.intent === 'BATCH') {
      const sections = this.data.sections.slice(), previous = sections[index]
      sections[index] = { ...s, key: previous.key, items: append ? previous.items.concat(s.items) : s.items }
      this.setData({ sections, busy: false }); return
    }
    const sections = result.sections ? result.sections.map((value, i) => ({ ...this.section(value), key: 'query-' + i })) : [{ ...s, key: 'query-0', items: append ? this.data.items.concat(s.items) : s.items }]
    const change = { answer: result.answer, items: result.sections ? [] : sections[0].items, sections, intent: result.intent, status: result.status || '', interpretation: result.interpretation || '', corrections: result.corrections || [], ...emptyTokens,
      confirmationToken: result.confirmationToken || '', nextToken: result.nextToken || '', clarificationToken: result.clarificationToken || '', amendmentToken: result.amendmentToken || '', retryToken: result.retryToken || '', hasMore: !!result.hasMore, followupQuestion: result.question || '', recoveryMessage: '' }
    this.setData(change)
    if (result.plan && result.status === 'CONFIRM_SCOPE' && result.intent !== 'BATCH') {
      this.setData({ entity: '', location: '', date: '', categoryIndex: 0, stateIndex: 0 }); this.applyDraft(result.plan)
    }
    this.setData({ draftSummaries: [], missingConditions: result.missingConditions || [], replyText: '' })
    if (result.draft) {
      if (!result.draft.queries) this.applyDraft(result.draft, true)
      this.setData({ draftSummaries: (result.draft.queries || [result.draft]).map(p => this.summaryOf(p)) })
      if (result.draft.queries) this.syncQueue(result.draft.queries.map(p => ({ ...this.bodyOf(p), _needsReview: p.action !== 'QUERY', reason: p.reason })))
    }
    if (['CLARIFY', 'NOT_UNDERSTOOD', 'UNSUPPORTED', 'SYSTEM_ERROR'].includes(result.status)) this.setData({ facetOpen: true })
  },
  async load(url, body) {
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    if (url === '/assistant/query' || url === '/assistant/queries') this.lastPreviewRequest = { url, body }
    this.setData({ busy: true, answer: '', items: [], sections: [], intent: '', interpretation: '', corrections: [], status: '', ...emptyTokens, hasMore: false, recoveryMessage: '' })
    try { const result = await request(url, 'POST', body); if (generation === this.queryGeneration) { this.accept(result); this.setData({ busy: false }); if (result.status === 'CLARIFY' && result.clarificationToken) await this.promptClarification(generation) } }
    catch (e) { if (generation === this.queryGeneration) this.setData({ status: 'SYSTEM_ERROR', recoveryMessage: '本次请求未能完成，请重新发起或手选分区；不会假装已查完。' }) }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  async reply() { const token = this.data.clarificationToken || this.data.amendmentToken; if (this.data.busy || !token) return; const answer = this.data.replyText.trim(); if (!answer) return wx.showToast({ title: '请填写要补充或修改的条件', icon: 'none' }); return this.load('/assistant/clarify', { token, answer }) },
  async promptClarification(generation = this.queryGeneration) {
    if (typeof generation !== 'number') generation = this.queryGeneration
    const token = this.data.clarificationToken
    if (this.data.busy || !token || this.clarificationPromptOpen) return
    this.clarificationPromptOpen = true
    try {
      const answer = await confirm((this.data.followupQuestion || this.data.answer) + '\n尚未查询。取消将保留草稿，不会执行。', true,
        { title: '请补充查询条件', placeholderText: '填写缺少的条件，已明确内容会保留', confirmText: '继续判断', cancelText: '暂不补充' })
      if (generation !== this.queryGeneration || token !== this.data.clarificationToken || this.data.busy) return
      if (typeof answer !== 'string' || !answer.trim()) return
      this.setData({ replyText: answer.trim() })
    } finally { this.clarificationPromptOpen = false }
    if (generation === this.queryGeneration && token === this.data.clarificationToken && this.data.replyText.trim()) return this.reply()
  },
  async retry() { if (!this.data.busy && this.data.retryToken) return this.load('/assistant/retry', { token: this.data.retryToken }) },
  async confirmScope(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset.index || 0), section = this.data.sections[index]
    const token = section && section.confirmationToken || this.data.confirmationToken
    if (this.data.busy || !token) return
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true })
    try {
      const agreed = await confirm(section && section.answer || this.data.answer)
      if (!agreed || generation !== this.queryGeneration) return
      const current = this.data.sections[index]; if (token !== (current && current.confirmationToken || this.data.confirmationToken)) return
      const result = await request('/assistant/confirm', 'POST', { token }); if (generation === this.queryGeneration) this.accept(result, false, index)
    } catch (e) { if (generation === this.queryGeneration) { this.invalidate(); this.setData({ recoveryMessage: '未能确认本次查询是否完成，请重新核对范围，不会重复消费旧凭据。' }) } }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
  },
  async more(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset.index || 0), section = this.data.sections[index]
    const token = section && section.nextToken || this.data.nextToken
    if (this.data.busy || !token) return
    const generation = this.queryGeneration = (this.queryGeneration || 0) + 1
    this.setData({ busy: true })
    try { const result = await request('/assistant/more', 'POST', { token }); if (generation === this.queryGeneration) this.accept(result, result.status === 'QUERY', index) }
    catch (e) { if (generation === this.queryGeneration) { this.invalidate(); this.setData({ recoveryMessage: '下一页未完成，当前仅展示已收到的记录。请重新核对查询，不能把这些当作全部。' }) } }
    finally { if (generation === this.queryGeneration) this.setData({ busy: false }) }
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
    if (!this.data.question.trim()) return wx.showToast({ title: '先输入想问的问题吧', icon: 'none' })
    if (wx.hideKeyboard) wx.hideKeyboard()
    this.lastPreviewRequest = null
    this.setData({ queuedQueries: [], queuedSummaries: [], hasPendingQueries: false, draftSummaries: [], missingConditions: [], facetPending: [], completionDraft: null, entityPending: false, editingIndex: -1, followupQuestion: '', replyText: '', correcting: false, entity: '', location: '', date: '', categoryIndex: 0, stateIndex: 0, objectIndex: 0, timeKeys: contract.times, times: contract.timeLabels, timeIndex: 0, states: [contract.stateLabels.ANY] })
    return this.load('/assistant', { question: this.data.question.trim() })
  },
  open(e) { const index = Number(e.currentTarget.dataset.index || 0), intent = this.data.sections[index] && this.data.sections[index].intent || this.data.intent; if (intent === 'ACTIVITIES' || intent === 'MY_REGISTRATIONS') wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) }
})
