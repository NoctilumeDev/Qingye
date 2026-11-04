const categories = [{ value: 'UPDATE', label: '功能更新' }, { value: 'MAINTENANCE', label: '维护通知' }, { value: 'GUIDE', label: '使用说明' }]
function display(item) {
  const category = categories.find(row => row.value === item.category)
  return { ...item, categoryLabel: category ? category.label : '公告', dateLabel: String(item.updatedAt || '').slice(0, 10), builtin: !!item.builtinKey }
}
module.exports = { categories, display }
