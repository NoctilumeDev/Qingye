const clubsPath = '/pages/clubs/index'
const workbenchPath = '/pages/workbench/index'
const equipmentPath = '/pages/equipment/index'
const tabs = [
  { path: '/pages/home/index', label: '发现', icon: 'home-5-line-muted', activeIcon: 'home-5-fill-green' },
  { path: clubsPath, label: '社团', icon: 'team-line-muted', activeIcon: 'team-fill-green' },
  { path: '/pages/assistant/index', label: '问青野', icon: 'sparkling-fill-green', activeIcon: 'sparkling-fill-green', assistant: true },
  { path: equipmentPath, label: '器材室', icon: 'box-3-line-muted', activeIcon: 'archive-line-green' },
  { path: workbenchPath, label: '工作台', icon: 'dashboard-line-ink', activeIcon: 'dashboard-line-ink' },
  { path: '/pages/me/index', label: '我的', icon: 'user-3-line-muted', activeIcon: 'user-3-fill-green' }
]

function visibleTabs(user) {
  return tabs.filter(tab => tab.path === workbenchPath ? !!(user && user.workbench) : tab.path === equipmentPath ? !!(user && !user.workbench) : true)
}

function refreshTabBar(page, path, user = getApp().globalData.user) {
  const bar = page.getTabBar && page.getTabBar()
  if (bar) bar.setData({ tabs: visibleTabs(user), selectedPath: path })
}

function switchTab(url) {
  const current = getCurrentPages().slice(-1)[0]
  if (url === equipmentPath) { if (!current || '/' + current.route !== equipmentPath) wx.navigateTo({ url }); return }
  wx.switchTab({ url })
}
module.exports = { switchTab, visibleTabs, refreshTabBar }
