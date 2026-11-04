const { request, guard, confirm } = require('../../utils/request')
const { display } = require('../../utils/announcements')
Page({
  data: { item: null, management: false, loading: false, busy: false, failed: false, missing: false },
  onLoad(options) { this.id = Number(options.id); this.manageRequested = options.manage === '1' },
  onShow() { if (!guard()) return; this.visible = true; return this.load() },
  onHide() { this.visible = false },
  onUnload() { this.visible = false },
  async load() {
    if (this.data.loading) return
    this.setData({ loading: true, failed: false, missing: false })
    try {
      const item = await request('/announcements/' + this.id)
      const user = this.manageRequested ? await request('/me') : null
      if (!this.visible) return
      this.setData({ item: display(item), management: !!(user && user.admin) })
    } catch (e) { this.setData({ failed: true, missing: e.status === 404, item: null }) }
    finally { this.setData({ loading: false }) }
  },
  async archive() {
    if (this.data.busy || this.data.loading || !this.data.item) return
    const item = this.data.item; this.setData({ busy: true })
    try {
      await request('/announcements/' + this.id + '/archive', 'PUT', { archived: !item.archived, version: item.version })
      this.setData({ item: { ...item, archived: !item.archived } })
      wx.showToast({ title: item.archived ? '已恢复到公告' : '已归档', icon: 'success' })
    } catch (e) { if (e.status === 409 || e.status === 404) await this.load() }
    finally { this.setData({ busy: false }) }
  },
  edit() { if (this.data.management && this.data.item && !this.data.item.builtin) wx.navigateTo({ url: '/pages/announcement-editor/index?id=' + this.id }) },
  async remove() {
    if (this.data.busy || this.data.loading || !this.data.management || !this.data.item || this.data.item.builtin) return
    const item = this.data.item, session = wx.getStorageSync('qingye-token')
    this.setData({ busy: true })
    try {
      if (!await confirm('删除后，所有人都无法再查看这条公告，包括已归档的人。确定删除“' + item.title + '”吗？', false, { title: '删除公告', confirmText: '删除' })) return
      if (!this.visible || session !== wx.getStorageSync('qingye-token')) return
      await request('/admin/announcements/' + this.id + '?version=' + item.version, 'DELETE')
      wx.navigateBack()
    } catch (e) { if (e.status === 409 || e.status === 404) await this.load() }
    finally { this.setData({ busy: false }) }
  }
})
