const { request } = require('../../utils/request')
Page({
  data: { accounts: [], busy: false },
  onLoad() { request('/auth/options').then(data => this.setData({ accounts: data.demoUsers })).catch(() => {}) },
  async demo(e) { await this.login('/auth/demo', { userId: Number(e.currentTarget.dataset.id) }) },
  async wechat() {
    if (this.data.busy) return
    wx.login({ success: res => this.login('/auth/wechat', { code: res.code }), fail: () => wx.showToast({ title: '微信登录暂不可用', icon: 'none' }) })
  },
  async login(path, data) {
    if (this.data.busy) return
    this.setData({ busy: true })
    try { getApp().setSession(await request(path, 'POST', data)); wx.switchTab({ url: '/pages/home/index' }) }
    catch (e) {} finally { this.setData({ busy: false }) }
  }
})
