const { request, guard } = require('../../utils/request')
const { activity } = require('../../utils/view')
const history = require('../../utils/search-history')
Page({
  data: { items: [], category: '', keyword: '', searchText: '', searchFocused: false, history: [], page: 0, loading: false, finished: false, failed: false,
    categories: [{ key: '', name: '全部' }, { key: 'SPORT', name: '运动' }, { key: 'ART', name: '艺术' }, { key: 'TECH', name: '科技' }, { key: 'VOLUNTEER', name: '志愿' }] },
  onLoad(options = {}) { this.setData({ searchFocused: options.search === '1' }) },
  onShow() { if (guard()) { this.setData({ history: history.read() }); return this.load(true, true) } },
  async load(reset = false, preservePages = false) {
    if (this.data.loading) { if (reset) this.refreshPending = !preservePages || this.refreshPending === 'reset' ? 'reset' : 'preserve'; return }
    if (!reset && this.data.finished) return
    this.setData({ loading: true, failed: false })
    const page = reset ? 0 : this.data.page
    const { category, keyword } = this.data
    try {
      const count = reset && preservePages ? Math.max(1, this.data.page) : 1
      const rows = []; let nextPage = page, finished = false
      // Refresh the loaded range in one update, so returning does not collapse the list.
      for (let index = 0; index < count; index++) {
        const batch = await request(`/activities?scope=public&page=${nextPage}&category=${category}&keyword=${encodeURIComponent(keyword)}`)
        if (this.refreshPending) return
        rows.push(...batch); nextPage++; finished = batch.length < 20
        if (finished) break
      }
      if (this.refreshPending) return
      this.setData({ items: reset ? rows.map(activity) : this.data.items.concat(rows.map(activity)), page: nextPage, finished })
    } catch (e) { this.setData({ failed: true }) } finally { this.setData({ loading: false }); wx.stopPullDownRefresh(); if (this.refreshPending) { const preserve = this.refreshPending === 'preserve'; this.refreshPending = false; await this.load(true, preserve) } }
  },
  onPullDownRefresh() { return this.load(true) },
  onReachBottom() { return this.load() },
  category(e) { this.setData({ category: e.currentTarget.dataset.key, items: [], page: 0, finished: false }); return this.load(true) },
  input(e) { this.setData({ searchText: e.detail.value }) },
  focusSearch() { this.setData({ searchFocused: true, history: history.read() }) },
  closeHistory() { this.setData({ searchFocused: false }); if (wx.hideKeyboard) wx.hideKeyboard() },
  search(e) {
    const keyword = String(e && e.detail && typeof e.detail.value === 'string' ? e.detail.value : this.data.searchText).trim().slice(0, 60)
    this.setData({ keyword, searchText: keyword, history: history.remember(keyword), searchFocused: false, items: [], page: 0, finished: false })
    if (wx.hideKeyboard) wx.hideKeyboard()
    return this.load(true)
  },
  useHistory(e) { return this.search({ detail: { value: e.currentTarget.dataset.keyword } }) },
  clearHistory() { this.setData({ history: history.clear() }) },
  clearSearch() { this.setData({ keyword: '', searchText: '', searchFocused: false, items: [], page: 0, finished: false }); return this.load(true) },
  retry() { return this.load(true, true) },
  open(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.detail.id }) }
})
