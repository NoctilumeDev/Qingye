function failure(error) {
  const status = Number(error.status) || 0
  const copy = {
    403: ['登录请求被拒绝', '当前身份或登录入口不允许访问。请返回重新选择身份，或联系管理员。'],
    404: ['找不到登录服务', '登录接口不存在或地址已变更。请联系管理员检查服务地址。'],
    429: ['登录请求过于频繁', '请稍等片刻再尝试，避免连续提交。'],
    500: ['登录服务出现故障', '服务暂时无法完成登录，请稍后重试。'],
    502: ['登录服务暂不可达', '服务连接暂时异常，请稍后重试。'],
    503: ['登录服务暂不可用', '服务可能正在维护，请稍后重试。'],
    504: ['登录服务响应超时', '服务没有及时响应，请稍后重试。']
  }
  if (copy[status]) return { code: String(status), title: copy[status][0], description: copy[status][1] }
  if (error.kind === 'WECHAT') return { code: '微信登录', title: '微信登录暂不可用', description: '请重新发起微信登录，或返回选择演示身份。' }
  if (error.kind === 'INVALID_RESPONSE') return { code: '响应异常', title: '登录响应不完整', description: '服务没有返回有效的登录信息。请重试，或联系管理员检查服务。' }
  if (error.kind === 'TIMEOUT') return { code: '连接超时', title: '登录服务响应超时', description: '请检查网络连接，稍后重试。' }
  if (error.kind === 'BUSINESS') return { code: '请求未完成', title: '登录没有完成', description: error.message || '请返回重新选择身份，或稍后重试。' }
  if (status) return { code: String(status), title: '暂时无法登录', description: status === 401 ? '登录信息已失效，请返回后重新登录。' : '登录请求没有完成，请返回重新选择身份，或稍后重试。' }
  return { code: '连接失败', title: '暂时连接不上青野', description: '请检查网络连接；本地运行时请确认后端已启动。' }
}
module.exports = { failure }
