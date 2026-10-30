const { request, guard, confirm } = require('../../utils/request')
Page({
  data: { user: {}, items: [], keyword: '', searchText: '', page: 0, finished: false, loading: false, busy: false, failed: false },
  async onShow() {
    if (!guard()) return
    this.visible = true
    this.setData({ items: [] })
    try {
      const user = await request('/me'); getApp().globalData.user = user
      if (!user.admin) { wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/me/index' }) }); return }
      this.setData({ user }); await this.load(true)
    } catch (e) { this.setData({ failed: true }) }
  },
  onHide() { this.visible = false },
  onUnload() { this.visible = false },
  input(e) { this.setData({ searchText: e.detail.value }) },
  search() { if (this.data.loading || this.data.busy) return; this.setData({ keyword: this.data.searchText.trim() }); return this.load(true) },
  async load(reset = false) {
    if (this.data.loading || (!reset && this.data.finished)) return
    this.setData({ loading: true, failed: false })
    const page = reset ? 0 : this.data.page
    try {
      const items = await request('/admin/users?page=' + page + '&keyword=' + encodeURIComponent(this.data.keyword))
      this.setData({ items: reset ? items : this.data.items.concat(items), page: page + 1, finished: items.length < 20 })
    } catch (e) { this.setData({ failed: true }) }
    finally { this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  onPullDownRefresh() { return this.load(true) },
  onReachBottom() { return this.load() },
  retry() { return this.onShow() },
  async changeRole(e) {
    if (this.data.busy || this.data.loading) return
    const item = this.data.items.find(row => row.id === Number(e.currentTarget.dataset.id))
    if (!item || !item.enabled) return
    this.setData({ busy: true })
    const session = wx.getStorageSync('qingye-token')
    try {
      const grant = !item.admin
      if (!await confirm((grant ? '将' : '撤销') + '“' + item.name + '”（账号 ' + item.id + '）' + (grant ? '设为管理员？此账号将能管理全校社团、活动和器材。' : '的管理员权限？社团负责人身份将保留。'), false, { title: '确认权限变更', confirmText: grant ? '授予权限' : '撤销权限' })) return
      if (!this.visible || session !== wx.getStorageSync('qingye-token')) return
      await request('/admin/users/' + item.id + '/administrator', 'PATCH', { admin: grant })
      if (item.id === this.data.user.id && !grant) { getApp().globalData.user = await request('/me'); wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/me/index' }) }); return }
      await this.load(true)
    } catch (e) {} finally { this.setData({ busy: false }) }
  }
})
