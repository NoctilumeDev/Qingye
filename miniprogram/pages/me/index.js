const { request, guard } = require('../../utils/request')
const { activity } = require('../../utils/view')
Page({
  data: { user: {}, items: [], unread: 0 },
  async onShow() {
    if (!guard()) return; if (this.getTabBar()) this.getTabBar().setData({ selected: 2 })
    try {
      const [user, rows, notices] = await Promise.all([request('/me'), request('/activities?scope=mine'), request('/notifications')])
      getApp().globalData.user = user
      this.setData({ user, initial: user.name.slice(0, 1), items: rows.map(activity), unread: notices.filter(n => !n.readAt).length })
    } catch (e) {}
  },
  notifications() { wx.navigateTo({ url: '/pages/notifications/index' }) },
  workbench() { wx.navigateTo({ url: '/pages/workbench/index' }) },
  open(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.detail.id }) },
  logout() { getApp().logout() },
  rename() {
    wx.showModal({ title: '怎么称呼你？', editable: true, placeholderText: this.data.user.name, success: async res => {
      if (!res.confirm || !res.content.trim()) return
      try { await request('/me', 'PATCH', { name: res.content.trim(), avatar: this.data.user.avatar || '' }); this.onShow() } catch (e) {}
    } })
  }
})
