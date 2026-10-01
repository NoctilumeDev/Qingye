const { request, guard, confirm } = require('../../utils/request')
const { activity, record, states } = require('../../utils/view')
Page({
  data: { user: {}, tab: 'activities', tabs: [{ key: 'activities', name: '活动' }, { key: 'loans', name: '器材借用' }, { key: 'members', name: '社团成员' }], activities: [], loans: [], clubs: [], members: [], clubIndex: 0, busy: false, loading: false },
  async onShow() {
    if (!guard()) return
    try { const user = await request('/me'); if (!user.workbench) { wx.navigateBack(); return }; this.setData({ user }); await this.load(true) } catch (e) {}
  },
  onPullDownRefresh() { this.load() },
  async load(preservePages = false) {
    const version = this.loadVersion = (this.loadVersion || 0) + 1, tab = this.data.tab
    this.setData({ loading: true })
    try {
      if (tab === 'activities') {
        const count = preservePages ? Math.max(1, this.activityPage || 0) : 1, rows = []
        let nextPage = 0, more = false
        for (let index = 0; index < count; index++) {
          const batch = await request(`/activities?scope=work&page=${nextPage}`)
          if (version !== this.loadVersion) return
          rows.push(...batch); nextPage++; more = batch.length === 20
          if (!more) break
        }
        this.activityPage = nextPage; this.setData({ activities: rows.map(activity), more })
      }
      if (tab === 'loans') { const rows = await request('/loans'); if (version === this.loadVersion) this.setData({ loans: rows.map(record) }) }
      if (tab === 'members') {
        const selected = this.data.clubs[this.data.clubIndex]
        const clubs = (await request('/clubs')).filter(c => this.data.user.admin || (c.myRole === 'MANAGER' && c.myStatus === 'ACTIVE'))
        if (version !== this.loadVersion) return
        const existing = selected ? clubs.findIndex(c => c.id === selected.id) : -1
        const index = existing >= 0 ? existing : Math.min(this.data.clubIndex, Math.max(0, clubs.length - 1))
        this.setData({ clubs, clubNames: clubs.map(c => c.name), clubIndex: index })
        await this.loadMembers()
      }
    } catch (e) {} finally { if (version === this.loadVersion) this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  async more() { if (this.data.loading || !this.data.more) return; const version = this.loadVersion; this.setData({ loading: true }); try { const rows = await request(`/activities?scope=work&page=${this.activityPage}`); if (version !== this.loadVersion) return; this.activityPage++; this.setData({ activities: this.data.activities.concat(rows.map(activity)), more: rows.length === 20 }) } catch (e) {} finally { if (version === this.loadVersion) this.setData({ loading: false }) } },
  async loadMembers() { const club = this.data.clubs[this.data.clubIndex], version = this.memberVersion = (this.memberVersion || 0) + 1; const rows = club ? await request(`/clubs/${club.id}/members`) : []; if (version === this.memberVersion && this.data.tab === 'members' && this.data.clubs[this.data.clubIndex] === club) this.setData({ members: rows.map(m => ({ ...m, statusText: states[m.status] })) }) },
  tab(e) { const tab = e.currentTarget.dataset.key; if (tab === this.data.tab) return; this.setData({ tab }); this.load(true) },
  club(e) { this.setData({ clubIndex: Number(e.detail.value) }); this.loadMembers().catch(() => {}) },
  createActivity() { wx.navigateTo({ url: '/pages/editor/index?kind=activity' }) },
  createClub() { wx.navigateTo({ url: '/pages/editor/index?kind=club' }) },
  editClub() { const club = this.data.clubs[this.data.clubIndex]; if (club) wx.navigateTo({ url: '/pages/editor/index?kind=club&id=' + club.id }) },
  equipment() { wx.navigateTo({ url: '/pages/equipment/index' }) },
  detail(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) },
  async operation(e) {
    if (this.data.busy) return
    this.setData({ busy: true })
    try {
      const { kind, id, action } = e.currentTarget.dataset
      let body = {}, prompt = '确定执行这项操作吗？', path
      if (action === 'approve' || action === 'reject') {
        const result = await confirm(action === 'approve' ? '确认批准？可以填写审核意见。' : '确认拒绝？请填写原因。', true)
        if (!result) return
        body = { approve: action === 'approve', note: typeof result === 'string' ? result : '' }; path = `/${kind}/${id}/decision`
      } else {
        if (action === 'checkout') prompt = '请确认器材已经交给申请人。系统将再次核对实物数量。'
        if (action === 'return') prompt = '请确认这一单的器材已全部归还。'
        if (action === 'cancel') prompt = kind === 'activities' ? '取消活动后会通知报名同学，并释放尚未领取的器材预约。' : '确认取消这条尚未领取的器材申请？'
        if (!await confirm(prompt)) return
        path = `/${kind}/${id}/${action}`
      }
      await request(path, 'POST', body); wx.showToast({ title: '已完成' }); await this.load(true)
    } catch (e) {} finally { this.setData({ busy: false }) }
  },
  async member(e) {
    if (this.data.busy) return
    const club = this.data.clubs[this.data.clubIndex], { id, action } = e.currentTarget.dataset
    if (!club) return
    this.setData({ busy: true })
    try {
      if (!await confirm(action === 'promote' ? '确认将这位同学设为本社团负责人？' : action === 'demote' ? '确认交接这位负责人的身份？社团需要保留至少一位负责人。' : action === 'approve' ? '确认通过入社申请？' : '确认拒绝入社申请？')) return
      await request(`/clubs/${club.id}/members/${id}/decision`, 'POST', { approve: action !== 'reject', role: action === 'promote' ? 'MANAGER' : 'MEMBER' }); await this.loadMembers()
    } catch (e) {} finally { this.setData({ busy: false }) }
  }
})
