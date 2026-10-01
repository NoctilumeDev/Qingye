// Run with Node's built-in test runner; no npm dependencies or alternate frontend.
const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')
const client = path.resolve(__dirname, '../miniprogram')
function page(name, request, wx = {}) {
  let instance
  vm.runInNewContext(fs.readFileSync(path.join(client, 'pages', name, 'index.js'), 'utf8'), {
    Page(value) { instance = value; instance.data = JSON.parse(JSON.stringify(value.data)); instance.setData = values => { for (const [key, value] of Object.entries(values)) { const fields = key.split('.'); let target = instance.data; for (const field of fields.slice(0, -1)) target = target[field]; target[fields.at(-1)] = value } } },
    require(module) { return module.includes('request') ? { request, guard: () => true, confirm: async () => true } : require(path.join(client, 'utils/view.js')) },
    wx: { stopPullDownRefresh() {}, ...wx }, setTimeout, clearTimeout, Date
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
