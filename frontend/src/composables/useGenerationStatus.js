import { ref } from 'vue'

/** 全局生成状态 —— 跨页面持续追踪 */
export const isGenerating = ref(false)
export const activeToolType = ref(null) // 'calendar' | 'plan' | 'knowledge' | null
/** 学情周报生成中 —— 跨页面显示，切页不丢状态 */
export const reportGenerating = ref(false)
