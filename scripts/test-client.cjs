// Run with Node's built-in test runner; no npm dependencies or alternate frontend.
const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
const client = path.resolve(__dirname, '../miniprogram')
function page(name, request, wx = {}, confirmation = async () => true) {
  let instance; const app = { globalData: {} }
  vm.runInNewContext(fs.readFileSync(path.join(client, 'pages', name, 'index.js'), 'utf8'), {
    Page(value) { instance = value; instance.data = JSON.parse(JSON.stringify(value.data)); instance.getTabBar = () => null; instance.setData = values => { for (const [key, value] of Object.entries(values)) { const fields = key.split('.'); let target = instance.data; for (const field of fields.slice(0, -1)) target = target[field]; target[fields.at(-1)] = value } } },
    require(module) { return module.includes('request') ? { request, guard: () => true, confirm: confirmation } : module.includes('navigation') ? { switchTab: url => wx.switchTab({ url }) } : require(path.join(client, 'utils/view.js')) },
    getApp: () => app, wx: { stopPullDownRefresh() {}, ...wx }, setTimeout, clearTimeout, Date
  })
  return instance
}
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function transport() {
  const state = { token: 'first-test-session', logout: 0, toast: 0 }
  const context = { module: { exports: {} }, require: () => ({ baseUrl: 'http://test.invalid' }), getApp: () => ({ logout: () => state.logout++ }), wx: { getStorageSync: () => state.token, request: options => { state.options = options }, showToast: () => state.toast++ } }
  vm.runInNewContext(fs.readFileSync(path.join(client, 'utils/request.js'), 'utf8'), context)
  return { state, request: context.module.exports.request }
}
test('an old response cannot sign out or return another identity data after switching account', async () => {
  for (const statusCode of [200, 401]) {
    const { state, request } = transport(); const pending = request('/me')
    state.token = 'second-test-session'; state.options.success({ statusCode, data: { code: 0, data: { name: 'previous user' } } })
    await assert.rejects(pending, /身份已切换/)
    assert.equal(state.logout, 0); assert.equal(state.toast, 0)
  }
})
test('an expired current session signs out, and a current network failure shows a retry hint', async () => {
  const expired = transport(); const a = expired.request('/me')
  expired.state.options.success({ statusCode: 401, data: { message: '登录已失效' } })
  await assert.rejects(a, /失效/); assert.equal(expired.state.logout, 1)
  const offline = transport(); const b = offline.request('/me'); offline.state.options.fail()
  await assert.rejects(b, /连接不上/); assert.equal(offline.state.toast, 1)
})
const row = (id, category = 'SPORT') => ({ id, category, status: 'PUBLISHED', capacity: 10, registeredCount: 0, startTime: '2027-01-01T10:00:00', endTime: '2027-01-01T12:00:00', signupDeadline: '2026-12-31T10:00:00' })
test('rapid filter and search changes eventually show the latest query', async () => {
  const first = deferred(), urls = []
  const home = page('home', url => { urls.push(url); return urls.length === 1 ? first.promise : Promise.resolve([row(2, 'ART')]) })
  const loading = home.load(true)
  home.category({ currentTarget: { dataset: { key: 'SPORT' } } })
  home.search({ detail: { value: '摄影' } })
  home.category({ currentTarget: { dataset: { key: 'ART' } } })
  first.resolve([row(1)])
  await loading
  assert.equal(home.data.items[0].id, 2)
  assert.equal(urls.length, 2)
  assert.match(urls[1], /category=ART/)
  assert.match(urls[1], /keyword=%E6%91%84%E5%BD%B1/)
  assert.equal(home.data.page, 1)
})
test('a failed in-flight request still runs the queued refresh', async () => {
  const first = deferred(); let calls = 0
  const home = page('home', () => ++calls === 1 ? first.promise : Promise.resolve([row(3)]))
  const loading = home.load(true)
  home.category({ currentTarget: { dataset: { key: 'SPORT' } } })
  first.reject(new Error('temporary failure')); await loading
  assert.equal(home.data.items[0].id, 3)
  assert.equal(home.data.loading, false)
})
test('pagination appends once and stops at the short final page', async () => {
  const urls = []
  const home = page('home', async url => { urls.push(url); return urls.length === 1 ? Array.from({ length: 20 }, (_, n) => row(n)) : [row(20)] })
  home.data.category = 'SPORT'
  await home.load(true); await home.load(); await home.load()
  assert.equal(home.data.items.length, 21)
  assert.equal(urls.length, 2)
  assert.match(urls[1], /page=1/)
})
test('out-of-order availability responses cannot overwrite the latest interval', async () => {
  const older = deferred(), latest = deferred(); let calls = 0
  const editor = page('editor', () => ++calls === 1 ? older.promise : latest.promise)
  editor.data.form = { equipmentId: 1, startDate: '2026-10-02', endDate: '2026-10-02', startTime: '10:00', endTime: '11:00' }
  const a = editor.check(); editor.data.form.endTime = '12:00'; const b = editor.check()
  latest.resolve({ availableQuantity: 2 }); await b
  older.resolve({ availableQuantity: 5 }); await a
  assert.equal(editor.data.availability.availableQuantity, 2)
})
test('invalid interval clears availability and invalidates a pending response', async () => {
  const pending = deferred(); const editor = page('editor', () => pending.promise)
  editor.data.form = { equipmentId: 1, startDate: '2026-10-02', endDate: '2026-10-02', startTime: '10:00', endTime: '11:00' }
  const a = editor.check(); editor.data.form.endTime = '09:00'; await editor.check()
  pending.resolve({ availableQuantity: 5 }); await a
  assert.equal(editor.data.availability, null)
})
test('registration cannot submit twice while awaiting the backend', async () => {
  const pending = deferred(); let calls = 0
  const activity = page('activity', () => { calls++; return pending.promise }); activity.id = 1
  activity.load = async () => {}
  const a = activity.register(); await activity.register()
  assert.equal(calls, 1); pending.resolve({}); await a
  assert.equal(activity.data.busy, false)
})

test('bulk read submits once and reloads the authoritative read state', async () => {
  const pending = deferred(), calls = []
  const notices = page('notifications', (url, verb) => {
    calls.push([url, verb]); return verb === 'POST' ? pending.promise : Promise.resolve([{ id: 1, readAt: '2026-10-01T12:00:00', createdAt: '2026-10-01T11:00:00' }])
  }, { showToast() {} })
  notices.data.items = [{ id: 1 }]; notices.data.unread = 1
  const first = notices.readAll(); await notices.readAll(); await notices.clearSelected()
  assert.equal(calls.length, 1); assert.equal(notices.data.unread, 1)
  pending.resolve({}); await first
  assert.equal(notices.data.unread, 0); assert.equal(notices.data.busy, false)
  assert.equal(calls.length, 2)
})

test('cancelled or failed clearing keeps the current messages visible', async () => {
  let calls = 0
  const cancelled = page('notifications', async () => { calls++; return [] }, {}, async () => false)
  cancelled.data.items = [{ id: 1 }]; cancelled.data.selectedIds = [1]; cancelled.data.selecting = true; await cancelled.clearSelected()
  assert.equal(calls, 0); assert.equal(cancelled.data.items.length, 1); assert.equal(cancelled.data.busy, false)
  const failed = page('notifications', async () => { throw new Error('network failure') })
  failed.data.items = [{ id: 1 }]; failed.data.selectedIds = [1]; await failed.clearSelected()
  assert.equal(failed.data.items.length, 1); assert.equal(failed.data.busy, false)
})

test('selecting messages sends only chosen IDs and never marks them read', async () => {
  const calls = []
  const notices = page('notifications', async (url, verb, body) => {
    calls.push({ url, verb, body }); return verb === 'POST' ? {} : [{ id: 1, createdAt: '2026-10-01T11:00:00' }]
  }, { showToast() {} })
  notices.data.items = [{ id: 1 }, { id: 2 }]
  notices.beginClear(); notices.tapNotice({ currentTarget: { dataset: { id: 2 } } })
  assert.equal(calls.length, 0)
  assert.deepEqual(Array.from(notices.data.selectedIds), [2])
  notices.toggleAll(); assert.equal(notices.data.selectedIds.length, 2)
  notices.toggleAll(); assert.equal(notices.data.selectedIds.length, 0)
  notices.tapNotice({ currentTarget: { dataset: { id: 2 } } }); await notices.clearSelected()
  assert.equal(calls[0].url, '/notifications/clear'); assert.equal(calls[0].verb, 'POST')
  assert.deepEqual(Array.from(calls[0].body.ids), [2])
  assert.equal(notices.data.items.length, 1); assert.equal(notices.data.items[0].id, 1)
  assert.equal(notices.data.selecting, false); assert.equal(notices.data.unread, 1)
})

test('returning to a filtered activity list keeps all loaded pages and refreshes their facts', async () => {
  const urls = [], rows = Array.from({ length: 41 }, (_, n) => row(n))
  const home = page('home', async url => { urls.push(url); const index = Number(new URL(url, 'http://test.invalid').searchParams.get('page')); return rows.slice(index * 20, (index + 1) * 20) })
  home.data.category = 'SPORT'; home.data.keyword = '篮球'
  await home.load(true); await home.load(); await home.load()
  rows[40].registeredCount = 1; urls.length = 0
  await home.onShow()
  assert.equal(home.data.items.length, 41); assert.equal(home.data.items.at(-1).registeredCount, 1)
  assert.equal(home.data.page, 3); assert.equal(home.data.finished, true)
  assert.equal(home.data.category, 'SPORT'); assert.equal(home.data.keyword, '篮球')
  assert.equal(urls.length, 3); assert.ok(urls.every(url => url.includes('category=SPORT') && url.includes('keyword=%E7%AF%AE%E7%90%83')))
})

test('a failed return refresh does not discard the existing long list', async () => {
  const home = page('home', async url => { if (url.includes('page=1')) throw new Error('offline'); return Array.from({ length: 20 }, (_, n) => row(n)) })
  home.data.items = Array.from({ length: 41 }, (_, n) => row(n)); home.data.page = 3; home.data.category = 'SPORT'
  await home.onShow()
  assert.equal(home.data.items.length, 41); assert.equal(home.data.page, 3); assert.equal(home.data.loading, false)
})

test('a new filter takes priority over a queued return refresh', async () => {
  const first = deferred(), urls = []
  const home = page('home', url => { urls.push(url); return urls.length === 1 ? first.promise : Promise.resolve([row(99, 'ART')]) })
  home.data.items = Array.from({ length: 41 }, (_, n) => row(n)); home.data.page = 3; home.data.category = 'SPORT'
  const returning = home.onShow(); home.category({ currentTarget: { dataset: { key: 'ART' } } }); home.onShow()
  first.resolve(Array.from({ length: 20 }, (_, n) => row(n))); await returning
  assert.equal(urls.length, 2); assert.equal(home.data.items.length, 1); assert.equal(home.data.items[0].id, 99); assert.equal(home.data.page, 1)
})

test('workbench return keeps the loaded activity range and selected section', async () => {
  const rows = Array.from({ length: 41 }, (_, n) => row(n))
  const workbench = page('workbench', async url => url === '/me' ? { workbench: true } : rows.slice(Number(new URL(url, 'http://test.invalid').searchParams.get('page')) * 20, (Number(new URL(url, 'http://test.invalid').searchParams.get('page')) + 1) * 20))
  await workbench.load(); await workbench.more(); await workbench.more(); await workbench.onShow()
  assert.equal(workbench.data.activities.length, 41); assert.equal(workbench.activityPage, 3); assert.equal(workbench.data.more, false)
  assert.equal(workbench.data.tab, 'activities')
})

test('returning from club editing retains the selected club even if the list order changes', async () => {
  const workbench = page('workbench', async url => url === '/me' ? { workbench: true, admin: true } : url === '/clubs' ? [{ id: 2, name: 'B' }, { id: 1, name: 'A' }] : [{ id: 20, status: 'ACTIVE' }])
  workbench.data.tab = 'members'; workbench.data.clubs = [{ id: 1 }, { id: 2 }]; workbench.data.clubIndex = 1
  await workbench.onShow()
  assert.equal(workbench.data.tab, 'members'); assert.equal(workbench.data.clubs[workbench.data.clubIndex].id, 2); assert.equal(workbench.data.members[0].id, 20)
})

test('an earlier workbench request cannot replace the active section after a quick switch', async () => {
  const pending = deferred()
  const workbench = page('workbench', url => url === '/clubs' ? pending.promise : Promise.resolve([row(7)]))
  workbench.data.user.admin = true; workbench.data.tab = 'members'; const previous = workbench.load()
  workbench.data.tab = 'activities'; await workbench.load(); pending.resolve([{ id: 1 }]); await previous
  assert.equal(workbench.data.activities[0].id, 7); assert.equal(workbench.data.clubs.length, 0); assert.equal(workbench.data.loading, false)
})

test('clubs return follows the latest actual tab entry and does not overwrite it on repeated taps', () => {
  const app = { globalData: {} }, urls = [], context = { module: { exports: {} }, getApp: () => app, getCurrentPages: () => [{ route: context.route }], wx: { switchTab: ({ url }) => urls.push(url) } }
  vm.runInNewContext(fs.readFileSync(path.join(client, 'utils/navigation.js'), 'utf8'), context)
  const navigation = context.module.exports
  for (const source of ['pages/me/index', 'pages/home/index', 'pages/me/index']) {
    context.route = source; navigation.switchTab('/pages/clubs/index')
    context.route = 'pages/clubs/index'; navigation.switchTab('/pages/clubs/index'); navigation.backFromClubs()
    assert.equal(urls.at(-1), '/' + source)
  }
})

test('account switching clears the previous clubs entry', () => {
  let app; const context = { App(value) { app = value }, wx: { setStorageSync() {}, removeStorageSync() {}, reLaunch() {} } }
  vm.runInNewContext(fs.readFileSync(path.join(client, 'app.js'), 'utf8'), context)
  app.globalData.clubReturnTo = '/pages/me/index'; app.logout(); assert.equal(app.globalData.clubReturnTo, null)
  app.globalData.clubReturnTo = '/pages/me/index'; app.setSession({ user: { id: 2 }, token: 'test' }); assert.equal(app.globalData.clubReturnTo, null)
})

test('every demo identity edits only its nickname and cancelling leaves the profile intact', async () => {
  for (const name of ['林老师 · 管理员', '张三 · 摄影社负责人', '李四 · 同学', '王五 · 篮球社负责人', '赵六 · 同学']) {
    let writes = 0; const me = page('me', async () => { writes++ })
    me.data.user = { name, admin: name.includes('管理员'), workbench: name.includes('负责人') || name.includes('管理员') }; me.rename()
    assert.equal(me.data.renameName, name.split(' · ')[0]); assert.equal(me.data.renameIdentity, name.split(' · ')[1]); assert.equal(me.data.renameVisible, true)
    me.renameInput({ detail: { value: '新称呼' } }); me.closeRename()
    assert.equal(writes, 0); assert.equal(me.data.user.name, name); assert.equal(me.data.renameVisible, false)
  }
})

test('nickname save validates, prevents duplicate writes, preserves identity and avatar, and handles an empty API reply', async () => {
  const pending = deferred(), calls = [], stored = []
  const me = page('me', (url, verb, body) => { calls.push({ url, verb, body }); return pending.promise }, { setStorageSync(key,value) { stored.push({ key, value }) }, showToast() {} })
  me.data.user = { id: 2, name: '张三 · 摄影社负责人', avatar: 'https://example.test/avatar.jpg', admin: false, workbench: true }; me.rename()
  me.renameInput({ detail: { value: '  ' } }); await me.saveRename(); assert.equal(calls.length, 0); assert.ok(me.data.renameError)
  me.renameInput({ detail: { value: '新称呼' } }); const saving = me.saveRename(); await me.saveRename(); me.closeRename()
  assert.equal(calls.length, 1); assert.equal(me.data.renameVisible, true)
  assert.equal(calls[0].body.name, '新称呼 · 摄影社负责人'); assert.equal(calls[0].body.avatar, 'https://example.test/avatar.jpg')
  pending.resolve(null); await saving
  assert.equal(me.data.user.name, '新称呼 · 摄影社负责人'); assert.equal(me.data.user.workbench, true); assert.equal(me.data.user.admin, false)
  assert.equal(stored[0].value.name, me.data.user.name); assert.equal(me.data.renameVisible, false); assert.equal(me.data.renameBusy, false)
})

test('failed nickname save keeps the editable draft and current profile for retry', async () => {
  const me = page('me', async () => { throw new Error('offline') })
  me.data.user = { name: '自定义名字' }; me.rename(); me.renameInput({ detail: { value: '另一个名字' } }); await me.saveRename()
  assert.equal(me.data.user.name, '自定义名字'); assert.equal(me.data.renameName, '另一个名字'); assert.equal(me.data.renameVisible, true)
  assert.ok(me.data.renameError); assert.equal(me.data.renameBusy, false)
})

test('opening nickname editor before the profile arrives gives a retry hint', () => {
  const hints = [], me = page('me', async () => {}, { showToast: hint => hints.push(hint) })
  me.rename()
  assert.equal(me.data.renameVisible, false); assert.match(hints[0].title, /资料还未加载/)
})

test('an empty assistant question gives feedback without sending a query', async () => {
  let calls = 0; const hints = []
  const assistant = page('assistant', async () => { calls++ }, { showToast: hint => hints.push(hint) })
  assistant.input({ detail: { value: '  ' } }); await assistant.ask()
  assert.equal(calls, 0); assert.equal(assistant.data.busy, false); assert.match(hints[0].title, /先输入/)
})

test('loan defaults follow the selected activity and retain subsequent manual adjustments', async () => {
  const first = { ...row(11), title: '摄影活动' }, second = { ...row(12), startTime: '2027-01-02T16:30:00', endTime: '2027-01-02T18:30:00' }, queries = []
  const editor = page('editor', async url => {
    if (url === '/me') return { workbench: true }
    if (url === '/equipment') return [{ id: 1, name: '相机' }]
    if (url.startsWith('/activities')) return [first, second]
    queries.push(url); return { availableQuantity: 5 }
  })
  await editor.onLoad({ kind: 'loan', equipmentId: '1' }); await editor.check()
  assert.equal(editor.data.form.startDate, '2027-01-01'); assert.equal(editor.data.form.startTime, '10:00')
  assert.equal(editor.data.form.endTime, '12:00'); assert.match(editor.data.activityTime, /01-01 10:00/)
  const previousKey = editor.requestKey; editor.choice({ detail: { value: '1' } }); await editor.check()
  assert.equal(editor.data.form.activityId, 12); assert.equal(editor.data.form.startDate, '2027-01-02'); assert.equal(editor.data.form.startTime, '16:30')
  assert.notEqual(editor.requestKey, previousKey); assert.match(queries.at(-1), /2027-01-02T16%3A30/)
  editor.field({ currentTarget: { dataset: { field: 'startTime' } }, detail: { value: '16:00' } }); await editor.check()
  assert.equal(editor.data.form.startTime, '16:00'); assert.match(queries.at(-1), /2027-01-02T16%3A00/)
  editor.onUnload()
})

test('an empty search can return to the activity list while keeping the chosen category', async () => {
  const urls = [], home = page('home', async url => { urls.push(url); return url.includes('keyword=%E7%B4%A0123456') ? [] : [row(2)] })
  home.data.category = 'SPORT'; home.data.keyword = '素123456'; await home.load(true)
  assert.equal(home.data.items.length, 0); assert.equal(home.data.finished, true)
  await home.clearSearch()
  assert.equal(home.data.keyword, ''); assert.equal(home.data.category, 'SPORT'); assert.equal(home.data.items[0].id, 2)
  assert.match(urls.at(-1), /category=SPORT&keyword=$/)
})
