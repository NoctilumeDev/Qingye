const { request, guard, confirm } = require('../../utils/request')
const { activity, states, format } = require('../../utils/view')
Page({
  data: { item: null, registrationText: '', busy: false, participants: null },
  onLoad(options) { this.id = Number(options.id) },
  onShow() { if (guard()) this.load() },
  async load() {
    try {
      const raw = await request('/activities/' + this.id), item = activity(raw)
      const status = raw.myRegistration && raw.myRegistration.status
      this.setData({ item, registrationText: status && status !== 'CANCELLED' ? states[status] : '', canRegister: ['正在报名', '候补报名'].includes(item.phase), canCancel: !['进行中', '已结束', '已取消'].includes(item.phase), displayEnd: format(raw.endTime), displayDeadline: format(raw.signupDeadline) })
    } catch (e) {}
  },
  async register() { await this.action(() => request(`/activities/${this.id}/registration`, 'POST')) },
  async cancel() { if (await confirm('确定取消这次报名吗？空出的名额会给最早的候补同学。')) await this.action(() => request(`/activities/${this.id}/registration`, 'DELETE')) },
  async action(operation) { if (this.data.busy) return; this.setData({ busy: true }); try { await operation(); await this.load() } catch (e) {} finally { this.setData({ busy: false }) } },
  edit() { wx.navigateTo({ url: `/pages/editor/index?kind=activity&id=${this.id}` }) },
  async participants() { try { this.setData({ participants: await request(`/activities/${this.id}/participants`) }) } catch (e) {} }
})
