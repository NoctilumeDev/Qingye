const { switchTab, visibleTabs } = require('../utils/navigation')
Component({
  data: { selectedPath: '/pages/home/index', hidden: false, tabs: visibleTabs(null) },
  lifetimes: { attached() {
    const current = getCurrentPages().slice(-1)[0]
    this.setData({ tabs: visibleTabs(getApp().globalData.user), selectedPath: current ? '/' + current.route : '/pages/home/index' })
  } },
  methods: { switchTab(e) {
    const index = Number(e.currentTarget.dataset.index)
    const target = this.data.tabs[index]
    if (target && visibleTabs(getApp().globalData.user).some(tab => tab.path === target.path)) switchTab(target.path)
  } }
})
