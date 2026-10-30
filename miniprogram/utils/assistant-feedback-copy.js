// Qingye's presentation copy is independent of the model's protocol and prompt.
module.exports = {
  title: '这些是你想找的吗？', found: '找到了', missed: '没找对', thanks: '找到就好。',
  question: '你原本想找什么？',
  placeholder: '例如：我找的是摄影活动，不是讲座。',
  note: '只点“还没找到”不会提交。写好补充再继续，也可以先离开。',
  proceed: '再帮我找找', cancel: '返回结果', missing: '说说你想找什么吧',
  failed: '这次没能完成，请重新提问或选择查询条件。刚才的结果还在。',
  states: { PENDING: '谢谢你，补充内容核实后才会用于改进。',
    DISABLED: '继续按你的补充查找，本次未保存改进反馈。',
    UNAVAILABLE: '继续查找；改进反馈暂时没能保存。' }
}
