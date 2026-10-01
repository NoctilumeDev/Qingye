function windowHeight() { return wx.getWindowInfo ? wx.getWindowInfo().windowHeight : wx.getSystemInfoSync().windowHeight }

// Keep the viewport compensation and scrolling in one place for native inputs.
function keyboard(page, field) {
  const originalHeight = windowHeight()
  let height = 0, selector = '', context = '', gap = 24, sequence = 0, originalScroll = null, lastScroll = null
  let listening = false, settleTimer, blurTimer
  const listener = result => change({ detail: result })
  function start() {
    if (!listening && wx.onKeyboardHeightChange) { wx.onKeyboardHeightChange(listener); listening = true }
  }
  function reveal() {
    if (!height || !selector || !wx.createSelectorQuery) return
    const current = ++sequence
    const visible = Math.min(windowHeight(), originalHeight - height)
    const query = wx.createSelectorQuery().in(page).select(selector).boundingClientRect().selectViewport().scrollOffset()
    if (context) query.selectAll(context).boundingClientRect()
    query.exec(rows => {
      if (current !== sequence || !height || !rows[0] || !rows[1]) return
      let bottom = rows[0].bottom, top = rows[0].top
      const fields = rows[2] || []
      const index = fields.findIndex(rect => rect.top <= top + 1 && rect.bottom + 1 >= bottom)
      if (index >= 0) {
        top = fields[index].top
        bottom = fields[index + 1] ? fields[index + 1].bottom : bottom
      }
      // Show the next field too when it fits, keeping the current label in view.
      const overflow = Math.min(bottom + gap - visible, Math.max(0, top - 16))
      if (overflow <= 0) return
      const destination = Math.max(0, rows[1].scrollTop + overflow)
      if (lastScroll !== null && Math.abs(lastScroll - destination) < 1) return
      if (originalScroll === null) originalScroll = rows[1].scrollTop
      lastScroll = destination
      wx.pageScrollTo({ scrollTop: lastScroll, duration: 180 })
    })
  }
  function restore() {
    const current = ++sequence, saved = originalScroll, expected = lastScroll
    originalScroll = lastScroll = null
    if (saved === null || !wx.createSelectorQuery) return
    wx.createSelectorQuery().in(page).selectViewport().scrollOffset().exec(rows => {
      // Preserve a position the user deliberately scrolled to while typing.
      if (current === sequence && !height && rows[0] && Math.abs(rows[0].scrollTop - expected) < 2)
        wx.pageScrollTo({ scrollTop: saved, duration: 180 })
    })
  }
  function change(e) {
    height = Math.max(0, Number(e.detail.height) || 0)
    const resized = Math.max(0, originalHeight - windowHeight())
    page.setData({ [field]: Math.max(0, height - resized) }, () => height ? reveal() : restore())
    clearTimeout(settleTimer)
    // Android may report the keyboard before its viewport animation has settled.
    if (height && !e.detail.settled) settleTimer = setTimeout(() => change({ detail: { height, settled: true } }), 220)
  }
  return {
    focus(target = '', spacing = 24, fields = '', detail = {}) {
      clearTimeout(blurTimer); start(); selector = target; gap = spacing; context = fields
      if (detail.height > 0) change({ detail }); else reveal()
    },
    blur() { blurTimer = setTimeout(() => change({ detail: { height: 0 } }), 100) },
    change,
    reset() {
      height = 0; sequence++; originalScroll = lastScroll = null
      clearTimeout(settleTimer); clearTimeout(blurTimer)
      if (listening && wx.offKeyboardHeightChange) wx.offKeyboardHeightChange(listener)
      listening = false; page.setData({ [field]: 0 })
    }
  }
}
module.exports = { keyboard }
