const { request, guard } = require('../../utils/request')
const { activity } = require('../../utils/view')
const { switchTab, refreshTabBar } = require('../../utils/navigation')
const { keyboard } = require('../../utils/keyboard')
Page({
  data: { pageVisible: false, heroImages: ['/assets/campus/campus-friends-v1.jpg', '/assets/campus/campus-hero-v1.jpg'], user: {}, items: [], activityClubs: [], activityClubNames: ['全部社团'], activityClubIndex: 0, activityLoading: false, activityMore: false, activityError: false, unread: 0, avatarFailed: false, renameVisible: false, renameName: '', renameIdentity: '', renameSuffix: '', renameBusy: false, renameError: '', renameKeyboardHeight: 0 },
  async onShow() {
    if (!guard()) return; this.visible = true; this.setData({ pageVisible: true }); refreshTabBar(this, '/pages/me/index'); if (this.getTabBar()) this.getTabBar().setData({ hidden: this.data.renameVisible })
    if (getApp().globalData.openMyActivities) { getApp().globalData.openMyActivities = false; wx.nextTick(() => { if (this.visible) this.myActivities() }) }
    const version = this.activityVersion = (this.activityVersion || 0) + 1
    this.setData({ activityLoading: true, activityError: false })
    try {
      const [user, clubs, notices] = await Promise.all([request('/me'), request('/clubs'), request('/notifications')])
      if (version !== this.activityVersion) return
      const previous = this.data.activityClubs[this.data.activityClubIndex - 1]
      const selected = previous ? clubs.findIndex(c => c.id === previous.id) + 1 : 0
      getApp().globalData.user = user
      refreshTabBar(this, '/pages/me/index', user)
      this.setData({ user, initial: user.name.slice(0, 1), activityClubs: clubs, activityClubNames: ['全部社团', ...clubs.map(c => c.name)], activityClubIndex: selected, unread: notices.filter(n => !n.readAt).length, avatarFailed: false })
      await this.loadActivities(true, version)
    } catch (e) { if (version === this.activityVersion) this.setData({ activityLoading: false, activityError: true }) }
  },
  avatarError() { this.setData({ avatarFailed: true }) },
  onHide() { this.visible = false; this.setData({ pageVisible: false }); this.activityVersion = (this.activityVersion || 0) + 1; this.setRenameVisible(false) },
  onUnload() { if (this.renameKeyboardLayout) this.renameKeyboardLayout.reset() },
  myActivities() { wx.pageScrollTo({ selector: '#my-activities', duration: 250 }) },
  activityUrl(page) { const selected = this.data.activityClubs[this.data.activityClubIndex - 1]; return `/activities?scope=mine&page=${page}` + (selected ? `&clubId=${selected.id}` : '') },
  async loadActivities(reset = false, version = this.activityVersion) {
    if (!reset && (this.data.activityLoading || !this.data.activityMore)) return
    this.setData({ activityLoading: true, activityError: false })
    const page = reset ? 0 : this.activityPage
    try {
      const rows = await request(this.activityUrl(page))
      if (version !== this.activityVersion) return
      this.activityPage = page + 1
      this.setData({ items: reset ? rows.map(activity) : this.data.items.concat(rows.map(activity)), activityMore: rows.length === 20 })
    } catch (e) { if (version === this.activityVersion) this.setData({ activityError: true }) } finally { if (version === this.activityVersion) this.setData({ activityLoading: false }) }
  },
  selectActivityClub(e) { const index = Number(e.detail.value); if (!Number.isInteger(index) || index < 0 || index > this.data.activityClubs.length || index === this.data.activityClubIndex) return; const version = this.activityVersion = (this.activityVersion || 0) + 1; this.setData({ activityClubIndex: index, items: [], activityMore: false }); return this.loadActivities(true, version) },
  moreActivities() { return this.loadActivities() },
  discover() { wx.switchTab({ url: '/pages/home/index' }) },
  identity() { if (this.data.user.workbench) this.workbench(); else switchTab('/pages/clubs/index') },
  notifications() { wx.navigateTo({ url: '/pages/notifications/index' }) },
  workbench() { switchTab('/pages/workbench/index') },
  open(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.detail.id }) },
  logout() { getApp().logout() },
  rename() {
    const user = this.data.user
    if (!user.name) return wx.showToast({ title: '资料还未加载，请稍后再试', icon: 'none' })
    this.renameKeyboardLayout = keyboard(this, 'renameKeyboardHeight')
    const match = user.name.match(/^(.*?)\s*·\s*(管理员|摄影社负责人|篮球社负责人|同学)$/)
    const identity = user.admin ? '管理员' : user.workbench ? (match && match[2].endsWith('负责人') ? match[2] : '社团负责人') : '同学'
    this.setData({ renameName: match ? match[1].trim() : user.name, renameSuffix: match ? ' · ' + match[2] : '', renameIdentity: identity })
    this.setRenameVisible(true)
  },
  renameInput(e) { this.setData({ renameName: e.detail.value, renameError: '' }) },
  renameFocus(e) { this.renameKeyboardLayout.focus('', 24, '', e.detail) },
  renameBlur() { this.renameKeyboardLayout.blur() },
  renameKeyboard(e) {
    if (!this.data.renameVisible) return
    this.renameKeyboardLayout.change(e)
  },
  setRenameVisible(visible) { if (this.renameKeyboardLayout) this.renameKeyboardLayout.reset(); this.setData({ renameVisible: visible, renameError: '' }); if (!visible && wx.hideKeyboard) wx.hideKeyboard(); if (this.getTabBar()) this.getTabBar().setData({ hidden: visible }) },
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
