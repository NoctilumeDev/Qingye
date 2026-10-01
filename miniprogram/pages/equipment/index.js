const { request, guard } = require('../../utils/request')
const { equipment } = require('../../utils/view')
Page({
  data: { items: [], user: {}, loading: false },
  onShow() { if (guard()) this.load() }, onPullDownRefresh() { this.load() },
  async load() {
    this.setData({ loading: true })
    try { const [rows, user] = await Promise.all([request('/equipment'), request('/me')]); this.setData({ user, items: rows.map(equipment) }) }
    catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  imageError(e) { const index = e.currentTarget.dataset.index, item = this.data.items[index]; if (item && item.displayImage !== item.defaultImage) this.setData({ [`items[${index}].displayImage`]: item.defaultImage }) },
  apply(e) { wx.navigateTo({ url: '/pages/editor/index?kind=loan&equipmentId=' + e.currentTarget.dataset.id }) },
  edit(e) { wx.navigateTo({ url: '/pages/editor/index?kind=equipment&id=' + e.currentTarget.dataset.id }) },
  create() { wx.navigateTo({ url: '/pages/editor/index?kind=equipment' }) }
})
