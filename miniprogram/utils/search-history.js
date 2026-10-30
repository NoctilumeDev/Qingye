const limit = 5
function key() { const user = getApp().globalData.user; return 'qingye-activity-search-' + (user && user.id || 'guest') }
function read() {
  try { const rows = wx.getStorageSync(key()); return Array.isArray(rows) ? [...new Set(rows.filter(x => typeof x === 'string' && x.trim()).map(x => x.trim().slice(0, 60)))].slice(0, limit) : [] } catch (e) { return [] }
}
function remember(value) {
  const text = String(value || '').trim().slice(0, 60)
  const rows = text ? [text, ...read().filter(x => x !== text)].slice(0, limit) : read()
  try { wx.setStorageSync(key(), rows) } catch (e) {}
  return rows
}
function clear() { try { wx.removeStorageSync(key()) } catch (e) {} return [] }
module.exports = { read, remember, clear }
