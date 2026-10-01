const { request, guard } = require('../../utils/request')
const { activity } = require('../../utils/view')
Page({
  data: { items: [], recommendations: [], category: '', keyword: '', page: 0, loading: false, finished: false, categories: [{ key: '', name: '全部' }, { key: 'SPORT', name: '运动' }, { key: 'ART', name: '艺术' }, { key: 'TECH', name: '科技' }, { key: 'VOLUNTEER', name: '志愿' }] },
  onShow() { if (!guard()) return; if (this.getTabBar()) this.getTabBar().setData({ selected: 0 }); this.load(true) },
  async load(reset = false) {
    if (this.data.loading) { if (reset) this.refreshPending = true; return }
    if (!reset && this.data.finished) return
    this.setData({ loading: true })
    const page = reset ? 0 : this.data.page
    const { category, keyword } = this.data
    try {
      const rows = await request(`/activities?scope=public&page=${page}&category=${category}&keyword=${encodeURIComponent(keyword)}`)
      if (this.refreshPending) return
      this.setData({ items: reset ? rows.map(activity) : this.data.items.concat(rows.map(activity)), page: page + 1, finished: rows.length < 20 })
      if (reset && !category && !keyword) { const rows = await request('/recommendations'); if (!this.refreshPending) this.setData({ recommendations: rows.slice(0, 2).map(activity) }) }
      else if (reset) this.setData({ recommendations: [] })
    } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh(); if (this.refreshPending) { this.refreshPending = false; await this.load(true) } }
  },
  onPullDownRefresh() { this.load(true) }, onReachBottom() { this.load() },
  category(e) { this.setData({ category: e.currentTarget.dataset.key }); this.load(true) },
  search(e) { this.setData({ keyword: e.detail.value }); this.load(true) },
  open(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.detail.id }) },
  equipment() { wx.navigateTo({ url: '/pages/equipment/index' }) },
  assistant() { wx.navigateTo({ url: '/pages/assistant/index' }) },
  clubs() { wx.switchTab({ url: '/pages/clubs/index' }) },
  activities() { wx.pageScrollTo({ selector: '#activity-list', duration: 250 }) }
})
