// 全局界面字号控制：CSS 变量 --app-font-size + localStorage 持久化。
// 个人设置页可调整，影响聊天消息、资料预览、周报等正文区域。
const KEY = 'app_font_size'
const MIN = 12
const MAX = 20
const DEFAULT_SIZE = 15

export function getFontSize() {
  const v = parseInt(localStorage.getItem(KEY), 10)
  return v >= MIN && v <= MAX ? v : DEFAULT_SIZE
}

export function applyFontSize(size) {
  localStorage.setItem(KEY, String(size))
  document.documentElement.style.setProperty('--app-font-size', size + 'px')
}

export function initFontSize() {
  applyFontSize(getFontSize())
}
