const { baseUrl } = require('./config')
const modalColors = { confirmColor: '#187553', cancelColor: '#65767d' }
function request(path, method = 'GET', data, options = {}) {
  const token = wx.getStorageSync('qingye-token') || ''
  return new Promise((resolve, reject) => {
    wx.request({
      url: baseUrl + '/api' + path, method, data, timeout: 10000,
      header: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
      success(res) {
        if (token !== (wx.getStorageSync('qingye-token') || '')) return reject(new Error('体验身份已切换'))
        const payload = res.data && typeof res.data === 'object' ? res.data : {}
        if (res.statusCode >= 200 && res.statusCode < 300 && payload.code === 0) return resolve(payload.data)
        // A failed public login must stay on the same page so its fault can be shown.
        if (res.statusCode === 401 && token && !path.startsWith('/auth/')) getApp().logout()
        const error = Object.assign(new Error(payload.message || '操作没有完成，请稍后重试'), { status: res.statusCode })
        if (res.statusCode >= 200 && res.statusCode < 300) error.kind = typeof payload.code !== 'number' ? 'INVALID_RESPONSE' : 'BUSINESS'
        if (!options.silent) wx.showToast({ title: error.message, icon: 'none', duration: 3000 }); reject(error)
      },
      fail(cause) { if (token !== (wx.getStorageSync('qingye-token') || '')) return reject(new Error('体验身份已切换')); const error = Object.assign(new Error('暂时连接不上青野，请稍后重试'), { kind: /timeout/i.test(cause && cause.errMsg || '') ? 'TIMEOUT' : 'NETWORK' }); if (!options.silent) wx.showToast({ title: error.message, icon: 'none' }); reject(error) }
    })
  })
}
function guard() {
  if (!wx.getStorageSync('qingye-token')) { wx.reLaunch({ url: '/pages/login/index' }); return false }
  return true
}
function confirm(content, editable = false) {
  return new Promise(resolve => wx.showModal({ ...modalColors, title: '青野', content, editable, placeholderText: '可以填写审核意见', success: res => resolve(res.confirm ? (res.content || true) : false), fail: () => resolve(false) }))
}
module.exports = { request, guard, confirm }
