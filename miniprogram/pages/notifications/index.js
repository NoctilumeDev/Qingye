const { request, guard, confirm } = require('../../utils/request')
const { format } = require('../../utils/view')
Page({
  data: { items: [], unread: 0, trash: false, retentionDays: 7, loading: false, busy: false, selecting: false, selectedIds: [], allSelected: false },
  onShow() { if (guard()) this.load() },
  onPullDownRefresh() { if (!this.data.busy) this.load(); else wx.stopPullDownRefresh() },
  async load() {
    if (this.data.loading) return
    this.setData({ loading: true })
    try {
      const result = await request(this.data.trash ? '/notifications/trash' : '/notifications')
      const items = (this.data.trash ? result.items : result).map(n => ({ ...n, displayTime: format(n.createdAt), expiryTime: n.expiresAt ? format(n.expiresAt) : '' }))
      const selectedIds = this.data.selectedIds.filter(id => items.some(n => n.id === id))
      this.setData({ items: items.map(n => ({ ...n, selected: selectedIds.includes(n.id) })), unread: this.data.trash ? 0 : items.filter(n => !n.readAt).length, retentionDays: this.data.trash ? result.retentionDays : this.data.retentionDays, selectedIds, allSelected: items.length > 0 && selectedIds.length === items.length })
    } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  switchSection(e) {
    const trash = e.currentTarget.dataset.key === 'trash'
    if (this.data.busy || this.data.loading || trash === this.data.trash) return
    this.cancelSelection()
    this.setData({ trash, items: [], unread: 0 })
    return this.load()
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
  read(e) { if (!this.data.trash) return this.update(`/notifications/${e.currentTarget.dataset.id}/read`, 'POST') },
  readAll() { if (!this.data.trash && this.data.unread) return this.update('/notifications/read-all', 'POST', '全部已读') },
  tapNotice(e) {
    if (this.data.trash) this.beginClear()
    return this.data.selecting ? this.select(e) : this.read(e)
  },
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
  submitSelected() { return this.data.trash ? this.restoreSelected() : this.clearSelected() },
  async clearSelected() {
    if (this.data.trash || this.data.busy || this.data.loading || !this.data.items.length) return
    const ids = [...this.data.selectedIds]
    if (!ids.length) return
    this.setData({ busy: true })
    try {
      if (!await confirm(`将选中的 ${ids.length} 条消息移入回收站？${this.data.retentionDays} 天内可恢复，到期自动清理。活动报名和器材借用记录会保留。`)) return
      await request('/notifications/clear', 'POST', { ids })
      this.cancelSelection()
      await this.load()
      wx.showToast({ title: '已移入回收站', icon: 'success' })
    } catch (e) {} finally { this.setData({ busy: false }) }
  },
  async restoreSelected() {
    if (!this.data.trash || this.data.busy || this.data.loading || !this.data.selectedIds.length) return
    const ids = [...this.data.selectedIds]
    this.setData({ busy: true })
    try {
      const result = await request('/notifications/restore', 'POST', { ids })
      this.cancelSelection()
      await this.load()
      wx.showToast({ title: result.restored ? `已恢复 ${result.restored} 条` : '消息已过期或已恢复', icon: result.restored ? 'success' : 'none' })
    } catch (e) {} finally { this.setData({ busy: false }) }
  }
})
