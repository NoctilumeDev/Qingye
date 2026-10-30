const { request, guard } = require('../../utils/request')
const { activity } = require('../../utils/view')
const { switchTab, refreshTabBar } = require('../../utils/navigation')
Page({
  data: { recommendations: [], recommendationPages: [], recommendationIndex: 0, visible: false, loading: false, failed: false, recommendationsPlaying: true,
    heroImages: ['/assets/campus/campus-hero-v1.jpg', '/assets/campus/campus-friends-v1.jpg'] },
  onShow() { if (!guard()) return; refreshTabBar(this, '/pages/home/index'); this.setData({ visible: true }); return this.load() },
  onHide() { this.setData({ visible: false }) },
  pauseRecommendations() { this.setData({ recommendationsPlaying: false }) },
  recommendationChange(e) { this.setData({ recommendationIndex: e.detail.current }) },
  async load() {
    if (this.data.loading) return
    this.setData({ loading: true, failed: false })
    try {
      const recommendations = (await request('/recommendations')).map(activity), recommendationPages = []
      for (let i = 0; i < recommendations.length; i += 2) recommendationPages.push(recommendations.slice(i, i + 2))
      this.setData({ recommendations, recommendationPages, recommendationIndex: Math.max(0, Math.min(this.data.recommendationIndex, recommendationPages.length - 1)) })
    } catch (e) { this.setData({ failed: true }) }
    finally { this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  onPullDownRefresh() { return this.load() },
  open(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.detail.id }) },
  assistant() { switchTab('/pages/assistant/index') },
  clubs() { switchTab('/pages/clubs/index') },
  myActivities() { const app = getApp(); app.globalData.openMyActivities = true; wx.switchTab({ url: '/pages/me/index', fail() { app.globalData.openMyActivities = false } }) },
  activities() { wx.navigateTo({ url: '/pages/activities/index' }) },
  search() { wx.navigateTo({ url: '/pages/activities/index?search=1' }) }
})
