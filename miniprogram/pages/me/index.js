const { request, guard } = require('../../utils/request')
const { activity } = require('../../utils/view')
const { switchTab } = require('../../utils/navigation')
Page({
  data: { user: {}, items: [], unread: 0, avatarFailed: false, renameVisible: false, renameName: '', renameIdentity: '', renameSuffix: '', renameBusy: false, renameError: '' },
  async onShow() {
    if (!guard()) return; if (this.getTabBar()) this.getTabBar().setData({ selected: 2, hidden: this.data.renameVisible })
    try {
      const [user, rows, notices] = await Promise.all([request('/me'), request('/activities?scope=mine'), request('/notifications')])
      getApp().globalData.user = user
      this.setData({ user, initial: user.name.slice(0, 1), items: rows.map(activity), unread: notices.filter(n => !n.readAt).length, avatarFailed: false })
    } catch (e) {}
  },
  avatarError() { this.setData({ avatarFailed: true }) },
  onHide() { this.setRenameVisible(false) },
  myActivities() { wx.pageScrollTo({ selector: '#my-activities', duration: 250 }) },
  discover() { wx.switchTab({ url: '/pages/home/index' }) },
  identity() { if (this.data.user.workbench) this.workbench(); else switchTab('/pages/clubs/index') },
  notifications() { wx.navigateTo({ url: '/pages/notifications/index' }) },
  workbench() { wx.navigateTo({ url: '/pages/workbench/index' }) },
  open(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.detail.id }) },
  logout() { getApp().logout() },
  rename() {
    const user = this.data.user
    if (!user.name) return wx.showToast({ title: '资料还未加载，请稍后再试', icon: 'none' })
    const match = user.name.match(/^(.*?)\s*·\s*(管理员|摄影社负责人|篮球社负责人|同学)$/)
    const identity = user.admin ? '管理员' : user.workbench ? (match && match[2].endsWith('负责人') ? match[2] : '社团负责人') : '同学'
    this.setData({ renameName: match ? match[1].trim() : user.name, renameSuffix: match ? ' · ' + match[2] : '', renameIdentity: identity })
    this.setRenameVisible(true)
  },
  renameInput(e) { this.setData({ renameName: e.detail.value, renameError: '' }) },
  setRenameVisible(visible) { this.setData({ renameVisible: visible, renameError: '' }); if (this.getTabBar()) this.getTabBar().setData({ hidden: visible }) },
  closeRename() { if (!this.data.renameBusy) this.setRenameVisible(false) },
  keepRenameOpen() {},
  async saveRename() {
    if (this.data.renameBusy) return
    const nickname = this.data.renameName.trim(), name = nickname + this.data.renameSuffix
    if (!nickname) { this.setData({ renameError: '请填写你的称呼' }); return }
    if (name.length > 40) { this.setData({ renameError: '称呼太长了，请简短一点' }); return }
    this.setData({ renameBusy: true, renameError: '' })
    try {
      await request('/me', 'PATCH', { name, avatar: this.data.user.avatar || '' })
      const user = { ...this.data.user, name }
      getApp().globalData.user = user
      wx.setStorageSync('qingye-user', user)
      this.setData({ user, initial: user.name.slice(0, 1) })
      this.setRenameVisible(false)
      wx.showToast({ title: '称呼已更新', icon: 'success' })
    } catch (e) { this.setData({ renameError: '暂时没有保存成功，请再试一次' }) }
    finally { this.setData({ renameBusy: false }) }
  }
})
