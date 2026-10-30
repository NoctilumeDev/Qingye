const { request, guard, confirm } = require('../../utils/request')
const { club } = require('../../utils/view')
Page({
  data: { item: null, loading: true, failed: false, busy: false, leaders: [], leadersFailed: false },
  onLoad(options) { this.id = String(options.id || '') },
  onShow() { if (guard()) return this.load() },
  async load() {
    this.setData({ loading: true, failed: false })
    try {
      const clubs = await request('/clubs')
      const found = clubs.find(item => String(item.id) === this.id)
      this.setData({ item: found ? { ...club(found), createdDate: String(found.createdAt || '').slice(0, 10) } : null, leaders: [], leadersFailed: false })
      if (found) { try { this.setData({ leaders: await request(`/clubs/${this.id}/leaders`) }) } catch (e) { this.setData({ leadersFailed: true }) } }
    } catch (e) { this.setData({ failed: true }) }
    finally { this.setData({ loading: false }) }
  },
  async join() {
    if (this.data.busy || !this.data.item) return
    this.setData({ busy: true })
    try { await request(`/clubs/${this.id}/join`, 'POST'); wx.showToast({ title: '已提交入社申请', icon: 'none' }); await this.load() }
    catch (e) {} finally { this.setData({ busy: false }) }
  },
  async leave() {
    if (this.data.busy || !this.data.item) return
    this.setData({ busy: true })
    try { if (await confirm('确定退出这个社团吗？')) { await request(`/clubs/${this.id}/leave`, 'POST'); await this.load() } }
    catch (e) {} finally { this.setData({ busy: false }) }
  }
})
