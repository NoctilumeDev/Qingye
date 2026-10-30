const { request, guard } = require('./request')
const contract = require('./assistant-contract')
const display = require('./assistant-display')
const results = require('./assistant-results')

const fields = ['intent', 'entity', 'timeOption', 'date', 'status', 'category', 'location']
const titles = ['校园活动', '器材库存', '我的报名', '我的借用']
const descriptions = ['找活动、看时间地点', '看器材和可借数量', '查看报名和候补', '查看借用和归还']
const icons = ['calendar-event-line-blue', 'archive-line-green', 'team-line-green', 'time-line-ink']
const copy = value => JSON.parse(JSON.stringify(value))
const bodyOf = value => Object.fromEntries(fields.map(key => [key, value[key] == null ? null : value[key]]))
const defaults = intent => ({ intent, entity: intent === 'EQUIPMENT' ? 'ALL' : null, timeOption: intent === 'EQUIPMENT' ? 'CURRENT' : 'ANY', date: null, status: 'ANY', category: null, location: null })
const choices = selected => contract.intents.map((intent, i) => ({ intent, title: titles[i], description: descriptions[i], icon: icons[i], selected: selected.includes(intent) }))

function createManualPage() { return {
  data: { manual: true, directoryOpen: true, choices: choices([]), selectedCount: 0, tabs: [], activeKey: '', activeTitle: '',
    filterOpen: false, form: {}, nameInput: '', timeChoices: [], stateChoices: [], categoryChoices: ['全部分类', ...contract.categoryLabels],
    timeIndex: 0, stateIndex: 0, categoryIndex: 0, timeText: '', stateText: '', categoryText: '',
    appliedSummary: '', formDirty: false, nameRequired: false, pendingMessage: '', busy: false, busyAction: '', sections: [], status: '', statusTitle: '', statusHint: '',
    answer: '', recoveryMessage: '', feedbackNote: '', clarificationToken: '', facetOpen: false },
  onLoad() {
    if (!guard()) return
    this.pageSession = wx.getStorageSync ? wx.getStorageSync('qingye-token') : ''
    this.entries = []
    if (this.getOpenerEventChannel) this.getOpenerEventChannel().on('queryDraft', payload => {
      if (payload.session === this.pageSession) this.receiveDraft(payload)
    })
  },
  onShow() {
    if (!guard()) return
    const session = wx.getStorageSync ? wx.getStorageSync('qingye-token') : ''
    if (this.pageSession != null && session !== this.pageSession) {
      this.onHide(); this.entries = []; this.unknownDraft = null
      this.setData({ ...createManualPage().data })
    }
    this.pageSession = session
  },
  onHide() {
    this.generation = (this.generation || 0) + 1
    if (this.data.busy) {
      const entry = this.current()
      if (entry) { entry.recoveryMessage = '刚才还没查完，可以再试一次。'; entry.sections.forEach(s => { s.nextToken = ''; s.confirmationToken = '' }) }
      this.setData({ busy: false, busyAction: '', recoveryMessage: entry ? entry.recoveryMessage : '' })
    }
    if (wx.hideKeyboard) wx.hideKeyboard()
  },
  onUnload() { this.onHide(); this.entries = []; this.unknownDraft = null },
  current() { return (this.entries || []).find(entry => entry.key === this.data.activeKey) },
  makeEntry(intent, draft) {
    return { key: 'directory-' + (this.sequence = (this.sequence || 0) + 1), intent, form: draft ? bodyOf({ ...draft, intent }) : defaults(intent),
      imported: !!draft, nameRequired: !!draft && (draft.reason === 'UNKNOWN_ENTITY' || (intent === 'EQUIPMENT' && !draft.entity)), attempted: false, applied: null, sections: [], status: '', answer: '', recoveryMessage: '' }
  },
  toggleChoice(e) {
    const intent = e.currentTarget.dataset.intent
    if (!contract.intents.includes(intent)) return
    const selected = this.data.choices.filter(c => c.selected).map(c => c.intent)
    const next = selected.includes(intent) ? selected.filter(i => i !== intent) : this.unknownDraft ? [intent] : selected.concat(intent)
    this.setData({ choices: choices(next), selectedCount: next.length })
  },
  async startLookup() {
    if (this.data.busy || !this.data.selectedCount) return
    const selected = this.data.choices.filter(c => c.selected).map(c => c.intent)
    const previous = this.entries || []
    this.entries = selected.flatMap(intent => {
      const saved = previous.filter(entry => entry.intent === intent)
      return saved.length ? saved : [this.makeEntry(intent, this.unknownDraft)]
    })
    this.unknownDraft = null
    const active = this.entries.find(e => e.key === this.data.activeKey) || this.entries[0]
    this.setData({ directoryOpen: false, pendingMessage: '' })
    this.showEntry(active)
    return this.loadIfNeeded(active)
  },
  backToChoices() {
    if (this.data.busy) return
    this.setData({ directoryOpen: true, filterOpen: false })
    this.scrollTop()
  },
  async switchDirectory(e) {
    if (this.data.busy) return
    const entry = (this.entries || []).find(item => item.key === e.currentTarget.dataset.key)
    if (!entry || entry.key === this.data.activeKey) return
    this.showEntry(entry)
    return this.loadIfNeeded(entry)
  },
  loadIfNeeded(entry) {
    if (entry.imported && !entry.attempted) { this.openFilters(); return }
    if (!entry.attempted) return this.search()
  },
  showEntry(entry) {
    const tabs = this.entries.map(item => {
      const siblings = this.entries.filter(other => other.intent === item.intent)
      return { key: item.key, title: titles[contract.intents.indexOf(item.intent)] + (siblings.length > 1 ? ' ' + (siblings.indexOf(item) + 1) : '') }
    })
    const activeTitle = tabs.find(tab => tab.key === entry.key).title
    this.setData({ tabs, activeKey: entry.key, activeTitle, directoryOpen: false, filterOpen: false, pendingMessage: '',
      sections: entry.sections, status: entry.status, answer: entry.answer, recoveryMessage: entry.recoveryMessage,
      ...results.presentation(entry.status, entry.sections) })
    this.showForm(entry)
    this.scrollTop()
  },
  showForm(entry) {
    const form = entry.form, timeKeys = contract.timeOptionsByIntent[entry.intent] || contract.times
    const timeChoices = timeKeys.map(value => ({ value, label: (contract.timeLabelsByIntent[entry.intent] || {})[value] || contract.timeLabels[contract.times.indexOf(value)] }))
    const stateChoices = contract.states[entry.intent].map(value => ({ value, label: contract.stateLabels[value] }))
    const timeIndex = timeKeys.indexOf(form.timeOption), stateIndex = contract.states[entry.intent].indexOf(form.status)
    const categoryIndex = [null, ...contract.categories].indexOf(form.category)
    const appliedSummary = entry.applied ? display.scope(entry.applied)
      .filter(row => row.label !== '查找内容' && row.value !== '不限' && !(row.label === '状态' && entry.applied.status === 'ANY'))
      .map(row => row.value).join(' · ') : ''
    this.setData({ form: copy(form), nameInput: form.entity === 'ALL' ? '' : form.entity || '', timeChoices, stateChoices, timeIndex, stateIndex, categoryIndex,
      timeText: timeIndex >= 0 ? timeChoices[timeIndex].label : '请选择时间', stateText: stateIndex >= 0 ? stateChoices[stateIndex].label : '请选择状态',
      categoryText: categoryIndex >= 0 ? this.data.categoryChoices[categoryIndex] : '请选择分类', appliedSummary,
      nameRequired: entry.nameRequired || (entry.intent === 'EQUIPMENT' && !form.entity),
      formDirty: !!entry.applied && JSON.stringify(form) !== JSON.stringify(entry.applied) })
  },
  receiveDraft(payload) {
    const drafts = payload.queries || []
    if (!drafts.length) return
    // A draft opens for editing; it never starts an automatic wide query.
    if (drafts.some(draft => !contract.intents.includes(draft.intent))) {
      if (drafts.length !== 1) { this.setData({ pendingMessage: '这次有内容还没确定，请回到青野补充后再试。' }); return }
      this.unknownDraft = { ...bodyOf(drafts[0]), reason: drafts[0].reason }; this.entries = []
      this.setData({ directoryOpen: true, choices: choices([]), selectedCount: 0, pendingMessage: '先选这次要查的内容，已有条件会保留。' }); return
    }
    this.entries = drafts.map(draft => this.makeEntry(draft.intent, draft))
    const selected = [...new Set(drafts.map(draft => draft.intent))]
    this.setData({ choices: choices(selected), selectedCount: selected.length })
    this.showEntry(this.entries[0]); this.openFilters()
  },
  scrollTop() { if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 150 }) },
  field(e) {
    if (this.data.busy) return
    const entry = this.current(), field = e.currentTarget.dataset.field
    if (!entry || !['entity', 'location', 'date'].includes(field)) return
    const value = e.detail.value
    entry.form[field] = value || (field === 'entity' && entry.intent === 'EQUIPMENT' && !entry.imported ? 'ALL' : null)
    this.showForm(entry)
  },
  pick(e) {
    if (this.data.busy) return
    const entry = this.current(), field = e.currentTarget.dataset.field, index = Number(e.detail.value)
    if (!entry) return
    if (field === 'timeOption' && this.data.timeChoices[index]) { entry.form.timeOption = this.data.timeChoices[index].value; if (entry.form.timeOption !== 'DATE') entry.form.date = null }
    else if (field === 'status' && this.data.stateChoices[index]) entry.form.status = this.data.stateChoices[index].value
    else if (field === 'category' && index >= 0 && index <= contract.categories.length) entry.form.category = [null, ...contract.categories][index]
    this.showForm(entry)
  },
  openFilters() {
    if (this.data.busy || !this.current()) return
    this.filterSnapshot = { form: copy(this.current().form), nameRequired: this.current().nameRequired }
    this.setData({ filterOpen: true, pendingMessage: this.current().imported && !this.current().attempted ? '已带入刚才的条件，选好后查看结果。' : '' })
  },
  cancelFilters() {
    if (this.data.busy) return
    const entry = this.current()
    if (entry && this.filterSnapshot) { entry.form = copy(this.filterSnapshot.form); entry.nameRequired = this.filterSnapshot.nameRequired; this.showForm(entry) }
    this.setData({ filterOpen: false, pendingMessage: '' })
  },
  resetFilters() {
    if (this.data.busy) return
    const entry = this.current(); if (!entry) return
    entry.form = defaults(entry.intent); entry.nameRequired = false; this.showForm(entry)
  },
  chooseAllNames() {
    const entry = this.current(); if (!entry || this.data.busy) return
    entry.form.entity = entry.intent === 'EQUIPMENT' ? 'ALL' : null; entry.nameRequired = false; this.showForm(entry)
  },
  validForm() {
    const entry = this.current(); if (!entry) return null
    const form = bodyOf(entry.form), times = contract.timeOptionsByIntent[entry.intent] || contract.times
    for (const field of ['entity', 'location', 'date']) if (typeof form[field] === 'string') form[field] = form[field].trim() || null
    let message = ''
    if (!times.includes(form.timeOption)) message = '请选择要查看的时间。'
    else if (!contract.states[entry.intent].includes(form.status)) message = '请选择要查看的状态。'
    else if (form.timeOption === 'DATE' && !form.date) message = '请选择具体日期。'
    else if ((entry.intent === 'EQUIPMENT' || entry.nameRequired) && !form.entity) message = '请填写名称，或点“不限名称”。'
    else if (form.category && !contract.categories.includes(form.category)) message = '请重新选择分类。'
    else if (!['ACTIVITIES', 'MY_REGISTRATIONS'].includes(entry.intent) && (form.category || form.location)) message = '这类记录不能按活动分类或地点查找，请先清除这两个条件。'
    if (message) { this.setData({ pendingMessage: message, filterOpen: true }); return null }
    return form
  },
  async search() {
    if (this.data.busy) return
    const body = this.validForm(); if (!body) return
    const entry = this.current(); entry.form = copy(body); entry.applied = copy(body); entry.attempted = true
    this.setData({ filterOpen: false, pendingMessage: '' })
    return this.run('/assistant/query', body)
  },
  async run(url, body, append = false) {
    const entry = this.current(); if (!entry || this.data.busy) return
    const generation = this.generation = (this.generation || 0) + 1, session = this.pageSession
    const isQuery = url === '/assistant/query'
    if (isQuery) { entry.sections = []; entry.status = ''; entry.answer = '' }
    entry.recoveryMessage = ''
    this.setData({ busy: true, busyAction: append ? 'more' : 'query', sections: entry.sections, status: entry.status, recoveryMessage: '', statusTitle: '' })
    if (wx.hideKeyboard) wx.hideKeyboard()
    try {
      const result = await request(url, 'POST', body)
      if (generation !== this.generation || session !== this.pageSession) return
      if (result.contractVersion && result.contractVersion !== contract.version) throw Error('contract version mismatch')
      const section = results.section(result)
      section.key = entry.key
      if (append && result.status === 'QUERY') section.items = (entry.sections[0] ? entry.sections[0].items : []).concat(section.items)
      section.resultTitle = display.resultTitle(section.intent || entry.intent, section.items.length)
      entry.sections = [section]; entry.status = result.status; entry.answer = results.readableAnswer(result)
      if (result.draft) { entry.form = bodyOf({ ...result.draft, intent: entry.intent }); entry.nameRequired = result.draft.reason === 'UNKNOWN_ENTITY'; entry.imported = true }
      this.setData({ sections: entry.sections, status: entry.status, answer: entry.answer, ...results.presentation(entry.status, entry.sections) })
      this.showForm(entry)
      if (result.status === 'CLARIFY') this.openRequiredFilters()
    } catch (error) {
      if (generation !== this.generation || session !== this.pageSession) return
      entry.sections.forEach(s => { s.nextToken = ''; s.confirmationToken = '' })
      entry.recoveryMessage = append ? '下一页没加载出来，已经找到的记录还在。可以重新查找。' : '这次没能查完，请再试一次。'
      this.setData({ recoveryMessage: entry.recoveryMessage, sections: entry.sections })
    } finally { if (generation === this.generation) this.setData({ busy: false, busyAction: '' }) }
  },
  openRequiredFilters() { this.filterSnapshot = { form: copy(this.current().form), nameRequired: this.current().nameRequired }; this.setData({ filterOpen: true, pendingMessage: '还差一点条件，补充后再查找。' }) },
  restart() { return this.search() },
  confirmScope() {
    const entry = this.current(), token = entry && entry.sections[0] && entry.sections[0].confirmationToken
    if (token && !this.data.busy && !this.data.formDirty) return this.run('/assistant/confirm', { token })
  },
  more() {
    const entry = this.current(), token = entry && entry.sections[0] && entry.sections[0].nextToken
    if (token && !this.data.busy && !this.data.formDirty) return this.run('/assistant/more', { token }, true)
  },
  toggleScope() {
    const entry = this.current(); if (!entry || !entry.sections[0]) return
    entry.sections[0].scopeOpen = !entry.sections[0].scopeOpen; this.setData({ sections: entry.sections })
  },
  open(e) {
    if (this.data.busy) return
    const entry = this.current()
    if (entry && ['ACTIVITIES', 'MY_REGISTRATIONS'].includes(entry.intent)) wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id })
  }
} }

module.exports = { createManualPage }
