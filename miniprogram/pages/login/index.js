const { request } = require('../../utils/request')
const { failure } = require('../../utils/request-error')
Page({
  data: { accounts: [], busy: false, failure: null },
  onLoad() { return this.loadAccounts() },
  async loadAccounts() {
    if (this.data.busy) return
    this._retry = () => this.loadAccounts()
    this.setData({ busy: true })
    try {
      const data = await request('/auth/options', 'GET', undefined, { silent: true })
      if (!data || !Array.isArray(data.demoUsers)) throw Object.assign(new Error('Incomplete options'), { kind: 'INVALID_RESPONSE' })
      this.setData({ accounts: data.demoUsers, failure: null })
    } catch (error) { this.setData({ failure: failure(error) }) }
    finally { this.setData({ busy: false }) }
  },
  async demo(e) { await this.login('/auth/demo', { userId: Number(e.currentTarget.dataset.id) }) },
  wechat() {
    return this.runLogin(async () => {
      const result = await new Promise((resolve, reject) => wx.login({ success: resolve, fail: () => reject(Object.assign(new Error('WeChat unavailable'), { kind: 'WECHAT' })) }))
      if (!result.code) throw Object.assign(new Error('Missing login code'), { kind: 'WECHAT' })
      return request('/auth/wechat', 'POST', { code: result.code }, { silent: true })
    })
  },
  login(path, data) { return this.runLogin(() => request(path, 'POST', data, { silent: true })) },
  async runLogin(getSession) {
    if (this.data.busy) return
    this._retry = () => this.runLogin(getSession)
    this.setData({ busy: true })
    try {
      const session = await getSession()
      if (!session || !session.token || !session.user || !session.user.id) throw Object.assign(new Error('Incomplete session'), { kind: 'INVALID_RESPONSE' })
      this.setData({ failure: null }); getApp().setSession(session); wx.switchTab({ url: '/pages/home/index' })
    } catch (error) { this.setData({ failure: failure(error) }) }
    finally { this.setData({ busy: false }) }
  },
  retry() { if (this._retry) return this._retry() },
  backToLogin() { if (!this.data.busy) this.setData({ failure: null }) }
})
