const { request, guard, confirm } = require('../../utils/request')
const { activity, states, format } = require('../../utils/view')
Page({
  data: { item: null, registrationText: '', busy: false, participants: null, participantsExpanded: false, participantsLoading: false },
  onLoad(options) { this.id = Number(options.id) },
  onShow() { if (guard()) this.load() },
  onHide() { this.hideParticipants() },
  onUnload() { this.hideParticipants() },
  async load() {
    try {
      const raw = await request('/activities/' + this.id), item = activity(raw)
      const status = raw.myRegistration && raw.myRegistration.status
      this.setData({ item, registrationText: status && status !== 'CANCELLED' ? states[status] : '', canRegister: ['正在报名', '候补报名'].includes(item.phase), canCancel: !['进行中', '已结束', '已取消'].includes(item.phase), displayEnd: format(raw.endTime), displayDeadline: format(raw.signupDeadline) })
      if (!item.canManage) this.hideParticipants()
    } catch (e) {}
  },
  imageError() { const item = this.data.item; if (item && item.cover !== item.defaultCover) this.setData({ 'item.cover': item.defaultCover }) },
  async register() { await this.action(() => request(`/activities/${this.id}/registration`, 'POST')) },
  async cancel() { if (await confirm('确定取消这次报名吗？空出的名额会给最早的候补同学。')) await this.action(() => request(`/activities/${this.id}/registration`, 'DELETE')) },
  async action(operation) { if (this.data.busy) return; this.setData({ busy: true }); try { await operation(); await this.load() } catch (e) {} finally { this.setData({ busy: false }) } },
  edit() { wx.navigateTo({ url: `/pages/editor/index?kind=activity&id=${this.id}` }) },
  hideParticipants(scroll = false) {
    this.participantsGeneration = (this.participantsGeneration || 0) + 1
    this.setData({ participantsExpanded: false, participantsLoading: false, participants: null }, () => { if (scroll && wx.pageScrollTo) wx.pageScrollTo({ selector: '#participants-actions', duration: 200 }) })
  },
  async toggleParticipants() {
    if (this.data.participantsExpanded) { this.hideParticipants(true); return }
    if (!this.data.item || !this.data.item.canManage) return
    const generation = this.participantsGeneration = (this.participantsGeneration || 0) + 1
    this.setData({ participantsExpanded: true, participantsLoading: true })
    try {
      const participants = await request(`/activities/${this.id}/participants`)
      if (generation === this.participantsGeneration) this.setData({ participants, participantsLoading: false }, () => { if (wx.pageScrollTo) wx.pageScrollTo({ selector: '#participants-list', duration: 250 }) })
    } catch (e) { if (generation === this.participantsGeneration) this.hideParticipants() }
  }
})
