const { request, guard, confirm } = require('../../utils/request')
const { format } = require('../../utils/view')
Page({
  data: { items: [], unread: 0, loading: false, busy: false, selecting: false, selectedIds: [], allSelected: false },
  onShow() { if (guard()) this.load() },
  onPullDownRefresh() { if (!this.data.busy) this.load(); else wx.stopPullDownRefresh() },
  async load() {
    if (this.data.loading) return
    this.setData({ loading: true })
    try {
      const items = (await request('/notifications')).map(n => ({ ...n, displayTime: format(n.createdAt) }))
      const selectedIds = this.data.selectedIds.filter(id => items.some(n => n.id === id))
      this.setData({ items: items.map(n => ({ ...n, selected: selectedIds.includes(n.id) })), unread: items.filter(n => !n.readAt).length, selectedIds, allSelected: items.length > 0 && selectedIds.length === items.length })
    } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  async update(path, verb, message) {
    if (this.data.busy || this.data.loading) return
    this.setData({ busy: true })
    try {
      await request(path, verb)
      await this.load()
      if (message) wx.showToast({ title: message, icon: 'success' })
    } catch (e) {} finally { this.setData({ busy: false }) }
  },
  read(e) { return this.update(`/notifications/${e.currentTarget.dataset.id}/read`, 'POST') },
  readAll() { if (this.data.unread) return this.update('/notifications/read-all', 'POST', '全部已读') },
  tapNotice(e) { return this.data.selecting ? this.select(e) : this.read(e) },
  beginClear() {
    if (!this.data.busy && !this.data.loading && this.data.items.length) this.setData({ selecting: true })
  },
  selection(ids) {
    this.setData({ selectedIds: ids, allSelected: this.data.items.length > 0 && ids.length === this.data.items.length, items: this.data.items.map(n => ({ ...n, selected: ids.includes(n.id) })) })
  },
  select(e) {
    if (this.data.busy || this.data.loading) return
    const id = e.currentTarget.dataset.id, ids = this.data.selectedIds
    this.selection(ids.includes(id) ? ids.filter(n => n !== id) : [...ids, id])
  },
  toggleAll() {
    if (!this.data.busy && !this.data.loading) this.selection(this.data.allSelected ? [] : this.data.items.map(n => n.id))
  },
  cancelSelection() { this.setData({ selecting: false }); this.selection([]) },
  async clearSelected() {
    if (this.data.busy || this.data.loading || !this.data.items.length) return
    const ids = [...this.data.selectedIds]
    if (!ids.length) return
    this.setData({ busy: true })
    try {
      if (!await confirm(`清理选中的 ${ids.length} 条消息？清理后将不再显示，活动报名和器材借用记录会保留。`)) return
      await request('/notifications/clear', 'POST', { ids })
      this.cancelSelection()
      await this.load()
      wx.showToast({ title: '消息已清理', icon: 'success' })
    } catch (e) {} finally { this.setData({ busy: false }) }
  }
})
