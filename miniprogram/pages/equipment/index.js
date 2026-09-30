const { request, guard } = require('../../utils/request')
Page({
  data: { items: [], user: {}, loading: false },
  onShow() { if (guard()) this.load() }, onPullDownRefresh() { this.load() },
  async load() {
    this.setData({ loading: true })
    try { const [rows, user] = await Promise.all([request('/equipment'), request('/me')]); this.setData({ user, items: rows.map(r => ({ ...r, available: Math.max(0, r.totalQuantity - r.borrowedQuantity), symbol: /相机/.test(r.name) ? '📷' : /投影/.test(r.name) ? '▰' : /三脚/.test(r.name) ? '△' : '✦' })) }) }
    catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  apply(e) { wx.navigateTo({ url: '/pages/editor/index?kind=loan&equipmentId=' + e.currentTarget.dataset.id }) },
  edit(e) { wx.navigateTo({ url: '/pages/editor/index?kind=equipment&id=' + e.currentTarget.dataset.id }) },
  create() { wx.navigateTo({ url: '/pages/editor/index?kind=equipment' }) }
})
