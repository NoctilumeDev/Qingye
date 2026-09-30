Component({
  data: { selected: 0, tabs: [{ path: '/pages/home/index', label: '发现', icon: '◉' }, { path: '/pages/clubs/index', label: '社团', icon: '✳' }, { path: '/pages/me/index', label: '我的', icon: '☺' }] },
  methods: { switchTab(e) { const index = Number(e.currentTarget.dataset.index); wx.switchTab({ url: this.data.tabs[index].path }) } }
})
