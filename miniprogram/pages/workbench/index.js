const { request, guard, confirm } = require('../../utils/request')
const { activity, record, states } = require('../../utils/view')
Page({
  data: { user: {}, tab: 'activities', tabs: [{ key: 'activities', name: '活动' }, { key: 'loans', name: '器材借用' }, { key: 'members', name: '社团成员' }], activities: [], loans: [], clubs: [], members: [], clubIndex: 0, busy: false, loading: false },
  async onShow() {
    if (!guard()) return
    try { const user = await request('/me'); if (!user.workbench) { wx.navigateBack(); return }; this.setData({ user }); this.load() } catch (e) {}
  },
  onPullDownRefresh() { this.load() },
  async load() {
    this.setData({ loading: true })
    try {
      if (this.data.tab === 'activities') { this.activityPage = 1; const rows = await request('/activities?scope=work'); this.setData({ activities: rows.map(activity), more: rows.length === 20 }) }
      if (this.data.tab === 'loans') this.setData({ loans: (await request('/loans')).map(record) })
      if (this.data.tab === 'members') {
        const clubs = (await request('/clubs')).filter(c => this.data.user.admin || (c.myRole === 'MANAGER' && c.myStatus === 'ACTIVE'))
        const index = Math.min(this.data.clubIndex, Math.max(0, clubs.length - 1))
        this.setData({ clubs, clubNames: clubs.map(c => c.name), clubIndex: index })
        await this.loadMembers()
      }
    } catch (e) {} finally { this.setData({ loading: false }); wx.stopPullDownRefresh() }
  },
  async more() { if (this.data.loading || !this.data.more) return; this.setData({ loading: true }); try { const rows = await request(`/activities?scope=work&page=${this.activityPage}`); this.activityPage++; this.setData({ activities: this.data.activities.concat(rows.map(activity)), more: rows.length === 20 }) } catch (e) {} finally { this.setData({ loading: false }) } },
  async loadMembers() { const club = this.data.clubs[this.data.clubIndex]; this.setData({ members: club ? (await request(`/clubs/${club.id}/members`)).map(m => ({ ...m, statusText: states[m.status] })) : [] }) },
  tab(e) { this.setData({ tab: e.currentTarget.dataset.key }); this.load() },
  club(e) { this.setData({ clubIndex: Number(e.detail.value) }); this.loadMembers().catch(() => {}) },
  createActivity() { wx.navigateTo({ url: '/pages/editor/index?kind=activity' }) },
  createClub() { wx.navigateTo({ url: '/pages/editor/index?kind=club' }) },
  editClub() { const club = this.data.clubs[this.data.clubIndex]; if (club) wx.navigateTo({ url: '/pages/editor/index?kind=club&id=' + club.id }) },
  equipment() { wx.navigateTo({ url: '/pages/equipment/index' }) },
  detail(e) { wx.navigateTo({ url: '/pages/activity/index?id=' + e.currentTarget.dataset.id }) },
  async operation(e) {
    if (this.data.busy) return
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
    this.setData({ busy: true })
    try { await request(path, 'POST', body); wx.showToast({ title: '已完成' }); await this.load() } catch (e) {} finally { this.setData({ busy: false }) }
  },
  async member(e) {
    if (this.data.busy) return
    const club = this.data.clubs[this.data.clubIndex], { id, action } = e.currentTarget.dataset
    if (!club || !await confirm(action === 'promote' ? '确认将这位同学设为本社团负责人？' : action === 'demote' ? '确认交接这位负责人的身份？社团需要保留至少一位负责人。' : action === 'approve' ? '确认通过入社申请？' : '确认拒绝入社申请？')) return
    this.setData({ busy: true })
    try { await request(`/clubs/${club.id}/members/${id}/decision`, 'POST', { approve: action !== 'reject', role: action === 'promote' ? 'MANAGER' : 'MEMBER' }); await this.loadMembers() } catch (e) {} finally { this.setData({ busy: false }) }
  }
})
