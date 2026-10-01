const { request, guard, confirm } = require('../../utils/request')
const { club } = require('../../utils/view')
Page({
  data: { clubs: [], loading: false },
  onShow() { if (!guard()) return; if (this.getTabBar()) this.getTabBar().setData({ selected: 1 }); this.load() },
  onPullDownRefresh() { this.load() },
  async load() { this.setData({ loading: true }); try { this.setData({ clubs: (await request('/clubs')).map(club) }) } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() } },
  async join(e) { try { await request(`/clubs/${e.currentTarget.dataset.id}/join`, 'POST'); wx.showToast({ title: '已提交入社申请', icon: 'none' }); this.load() } catch (e) {} },
  async leave(e) { if (!await confirm('确定退出这个社团吗？')) return; try { await request(`/clubs/${e.currentTarget.dataset.id}/leave`, 'POST'); this.load() } catch (e) {} }
})
