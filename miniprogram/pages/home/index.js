const { request, guard } = require('../../utils/request')
const { activity } = require('../../utils/view')
const { switchTab } = require('../../utils/navigation')
Page({
  data: { items: [], recommendations: [], category: '', keyword: '', page: 0, loading: false, finished: false, categories: [{ key: '', name: '全部' }, { key: 'SPORT', name: '运动' }, { key: 'ART', name: '艺术' }, { key: 'TECH', name: '科技' }, { key: 'VOLUNTEER', name: '志愿' }] },
  onShow() { if (!guard()) return; if (this.getTabBar()) this.getTabBar().setData({ selected: 0 }); return this.load(true, true) },
  async load(reset = false, preservePages = false) {
    if (this.data.loading) { if (reset) this.refreshPending = !preservePages || this.refreshPending === 'reset' ? 'reset' : 'preserve'; return }
    if (!reset && this.data.finished) return
    this.setData({ loading: true })
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
      if (reset && !category && !keyword) { const rows = await request('/recommendations'); if (!this.refreshPending) this.setData({ recommendations: rows.slice(0, 2).map(activity) }) }
      else if (reset) this.setData({ recommendations: [] })
    } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh(); if (this.refreshPending) { const preserve = this.refreshPending === 'preserve'; this.refreshPending = false; await this.load(true, preserve) } }
  },
  onPullDownRefresh() { this.load(true) }, onReachBottom() { this.load() },
  category(e) { this.setData({ category: e.currentTarget.dataset.key }); this.load(true) },
  search(e) { this.setData({ keyword: e.detail.value }); this.load(true) },
  clearSearch() { this.setData({ keyword: '' }); return this.load(true) },
  open(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.detail.id }) },
  equipment() { wx.navigateTo({ url: '/pages/equipment/index' }) },
  assistant() { wx.navigateTo({ url: '/pages/assistant/index' }) },
  clubs() { switchTab('/pages/clubs/index') },
  activities() { wx.pageScrollTo({ selector: '#activity-list', duration: 250 }) }
})
