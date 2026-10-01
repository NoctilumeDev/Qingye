Component({
  data: { selected: 0, tabs: [{ path: '/pages/home/index', label: '发现', icon: 'home-5-line-muted', activeIcon: 'home-5-fill-green' }, { path: '/pages/clubs/index', label: '社团', icon: 'team-line-muted', activeIcon: 'team-fill-green' }, { path: '/pages/me/index', label: '我的', icon: 'user-3-line-muted', activeIcon: 'user-3-fill-green' }] },
  methods: { switchTab(e) { const index = Number(e.currentTarget.dataset.index); wx.switchTab({ url: this.data.tabs[index].path }) } }
})
