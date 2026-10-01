const clubsPath = '/pages/clubs/index'

function switchTab(url) {
  const current = getCurrentPages().slice(-1)[0]
  // Tab pages have no native back stack. Remember the actual entry before switching.
  if (url === clubsPath && current && '/' + current.route !== clubsPath) {
    getApp().globalData.clubReturnTo = '/' + current.route
  }
  wx.switchTab({ url })
}

function backFromClubs() {
  wx.switchTab({ url: getApp().globalData.clubReturnTo || '/pages/home/index' })
}

module.exports = { switchTab, backFromClubs }
