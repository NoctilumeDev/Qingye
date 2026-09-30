const { request, guard } = require('../../utils/request')
const { format } = require('../../utils/view')
Page({
  data: { items: [], loading: false }, onShow() { if (guard()) this.load() }, onPullDownRefresh() { this.load() },
  async load() { this.setData({ loading: true }); try { this.setData({ items: (await request('/notifications')).map(n => ({ ...n, displayTime: format(n.createdAt) })) }) } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() } },
  async read(e) { try { await request(`/notifications/${e.currentTarget.dataset.id}/read`, 'POST'); this.load() } catch (e) {} }
})
