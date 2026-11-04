const { request, guard, confirm } = require('../../utils/request')
const { categories } = require('../../utils/announcements')
Page({
  data: { ready: false, loading: false, busy: false, failed: false, conflict: false, title: '', body: '', categoryIndex: 0, categoryNames: categories.map(item => item.label), version: 1 },
  onLoad(options) { this.id = Number(options.id) || 0; this.setData({ id: this.id }); wx.setNavigationBarTitle({ title: this.id ? '修改公告' : '发布公告' }); if (guard()) return this.load() },
  onShow() { this.visible = true },
  onHide() { this.visible = false },
  onUnload() { this.visible = false },
  async load() {
    if (this.data.loading) return
    this.setData({ loading: true, failed: false })
    try {
      const user = await request('/me')
      if (!user.admin) { wx.navigateBack(); return }
      if (this.id) {
        const item = await request('/announcements/' + this.id)
        if (item.builtinKey) { wx.navigateBack(); return }
        this.setData({ title: item.title, body: item.body, categoryIndex: Math.max(0, categories.findIndex(row => row.value === item.category)), version: item.version })
      }
      this.setData({ ready: true, conflict: false })
    } catch (e) { this.setData({ failed: true }) }
    finally { this.setData({ loading: false }) }
  },
  field(e) { const key = e.currentTarget.dataset.field; if (['title', 'body'].includes(key)) this.setData({ [key]: e.detail.value }) },
  category(e) { this.setData({ categoryIndex: Number(e.detail.value) }) },
  async reload() {
    if (this.data.busy || this.data.loading) return
    if (await confirm('重新打开将替换当前填写的内容。请先复制你想保留的文字。', false, { title: '打开最新公告', confirmText: '重新打开' })) return this.load()
  },
  async save() {
    if (!this.data.ready || this.data.loading || this.data.busy || this.data.conflict) return
    const title = this.data.title.trim(), body = this.data.body.trim()
    if (!title || !body) { wx.showToast({ title: '请填写标题和正文', icon: 'none' }); return }
    this.setData({ busy: true })
    try {
      await request('/admin/announcements' + (this.id ? '/' + this.id : ''), this.id ? 'PUT' : 'POST', { title, body, category: categories[this.data.categoryIndex].value, version: this.data.version })
      wx.showToast({ title: this.id ? '已更新' : '已发布', icon: 'success' }); wx.navigateBack()
    } catch (e) { if (e.status === 409) this.setData({ conflict: true }) }
    finally { this.setData({ busy: false }) }
  }
})
