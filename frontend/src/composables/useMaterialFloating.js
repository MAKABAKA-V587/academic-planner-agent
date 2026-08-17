import { reactive } from 'vue'
import { marked } from '@/utils/markdown'
import DOMPurify from 'dompurify'
import request from '@/api/request'

/**
 * 资料查看数据源（模块级单例）。
 * dialog 与 floating 是两个互相独立的槽位：
 * - dialog：档案页「资料库」预览弹窗，只属于档案页
 * - floating：全局悬浮小窗（挂 App.vue 根节点，切换路由不销毁），聊天页/档案页都可打开
 * 两者各自显示各自打开的文件，可同时对比两份不同资料；
 * 「转为小窗」通过 copyDialogToFloating 把弹窗当前文件复制到小窗。
 */
const state = reactive({
  dialog: {
    materialId: null,
    fileName: '',
    fileType: 'txt',
    content: '',
    html: '',
    loading: false
  },
  floating: {
    materialId: null,
    fileName: '',
    fileType: 'txt',
    content: '',
    html: '',
    loading: false,
    // 悬浮窗 UI 状态
    visible: false,
    minified: false,
    left: 80,
    top: 80,
    width: 420,
    height: 520
  }
})

// 每个槽位独立序号，避免 dialog 与 floating 并发加载时互相覆盖
const seqMap = { dialog: 0, floating: 0 }

/** 加载资料内容到指定槽位（'dialog' 或 'floating'） */
const loadMaterial = async (slot, material) => {
  const target = state[slot]
  const seq = ++seqMap[slot]
  target.materialId = material.materialId
  target.fileName = material.fileName || ''
  target.fileType = material.fileType || (material.fileName || '').toLowerCase().split('.').pop() || 'txt'
  target.content = ''
  target.html = ''
  target.loading = true
  try {
    const res = await request.get(`/materials/${material.materialId}`)
    if (seq !== seqMap[slot]) return // 已被更新的请求覆盖
    target.content = res.content || '（无内容）'
    target.html = target.fileType === 'md' ? DOMPurify.sanitize(marked.parse(target.content, { breaks: true })) : ''
  } catch {
    if (seq !== seqMap[slot]) return
    target.content = '（加载失败）'
  } finally {
    if (seq === seqMap[slot]) target.loading = false
  }
}

/** 打开悬浮小窗显示指定资料（独立于弹窗）；若小窗已在预览同一份则不重复请求 */
const openFloating = (material) => {
  if (state.floating.materialId !== material.materialId || state.floating.content === '') {
    loadMaterial('floating', material)
  }
  state.floating.visible = true
}

/** 把弹窗当前文件复制到悬浮小窗（不重复请求），并打开小窗 */
const copyDialogToFloating = () => {
  const d = state.dialog
  Object.assign(state.floating, {
    materialId: d.materialId,
    fileName: d.fileName,
    fileType: d.fileType,
    content: d.content,
    html: d.html,
    loading: false
  })
  state.floating.visible = true
}

const closeFloating = () => {
  state.floating.visible = false
}

const toggleMinified = () => {
  state.floating.minified = !state.floating.minified
}

const setPos = (x, y) => {
  state.floating.left = x
  state.floating.top = y
}

const setSize = (w, h) => {
  state.floating.width = w
  state.floating.height = h
}

export function useMaterialFloating() {
  return { state, loadMaterial, openFloating, copyDialogToFloating, closeFloating, toggleMinified, setPos, setSize }
}
