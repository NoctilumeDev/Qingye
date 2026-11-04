const { request, guard } = require('../../utils/request')
const { display } = require('../../utils/announcements')
Page({
  data: { management: false, archived: false, items: [], loading: false, failed: false, hasMore: false, nextBefore: 0 },
  onLoad(options) { this.setData({ management: options.manage === '1' }); if (this.data.management) wx.setNavigationBarTitle({ title: '公告管理' }) },
  async onShow() {
    if (!guard()) return
    this.visible = true
    if (this.data.management) {
      try { const user = await request('/me'); if (!user.admin) { this.setData({ items: [] }); wx.navigateBack(); return } }
      catch (e) { this.setData({ failed: true }); return }
    }
    return this.load(true)
  },
  onHide() { this.visible = false; this.loadId = (this.loadId || 0) + 1; this.setData({ loading: false }) },
  onUnload() { this.onHide() },
  async load(reset = false) {
    if (this.data.loading || (!reset && !this.data.hasMore)) return
    const generation = this.loadId = (this.loadId || 0) + 1
    this.setData({ loading: true, failed: false })
    try {
      const result = await request('/announcements?archived=' + this.data.archived + '&management=' + this.data.management + '&before=' + (reset ? 0 : this.data.nextBefore))
      if (generation !== this.loadId) return
      this.setData({ items: (reset ? [] : this.data.items).concat(result.items.map(display)), hasMore: result.hasMore, nextBefore: result.nextBefore })
    } catch (e) { if (generation === this.loadId) this.setData({ failed: true }) }
    finally { if (generation === this.loadId) this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  section(e) {
    const archived = e.currentTarget.dataset.section === 'archive'
    if (this.data.loading || archived === this.data.archived) return
    this.setData({ archived, items: [], hasMore: false, nextBefore: 0 }); return this.load(true)
  },
  open(e) { wx.navigateTo({ url: '/pages/announcement/index?id=' + e.currentTarget.dataset.id + (this.data.management ? '&manage=1' : '') }) },
  create() { if (this.data.management) wx.navigateTo({ url: '/pages/announcement-editor/index' }) },
  retry() { return this.load(this.data.items.length === 0) },
  onReachBottom() { return this.load() },
  onPullDownRefresh() { return this.load(true) }
})
