App({
  globalData: { user: null },
  onLaunch() { this.globalData.user = wx.getStorageSync('qingye-user') || null },
  setSession(session) {
    wx.setStorageSync('qingye-token', session.token)
    wx.setStorageSync('qingye-user', session.user)
    this.globalData.user = session.user
    this.globalData.clubReturnTo = null
  },
  logout() {
    wx.removeStorageSync('qingye-token')
    wx.removeStorageSync('qingye-user')
    this.globalData.user = null
    this.globalData.clubReturnTo = null
    wx.reLaunch({ url: '/pages/login/index' })
  }
})
