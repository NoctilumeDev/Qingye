const { request, guard, confirm } = require('../../utils/request')
const { club } = require('../../utils/view')
const { refreshTabBar } = require('../../utils/navigation')
Page({
  data: { clubs: [], loading: false },
  onShow() { if (!guard()) return; refreshTabBar(this, '/pages/clubs/index'); this.load() },
  onPullDownRefresh() { this.load() },
  openClub(e) { wx.navigateTo({ url: '/pages/club-detail/index?id=' + e.currentTarget.dataset.id }) },
  async load() { this.setData({ loading: true }); try { this.setData({ clubs: (await request('/clubs')).map(club) }) } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() } },
  async join(e) { try { await request(`/clubs/${e.currentTarget.dataset.id}/join`, 'POST'); wx.showToast({ title: '已提交入社申请', icon: 'none' }); this.load() } catch (e) {} },
  async leave(e) { if (!await confirm('确定退出这个社团吗？')) return; try { await request(`/clubs/${e.currentTarget.dataset.id}/leave`, 'POST'); this.load() } catch (e) {} }
})
