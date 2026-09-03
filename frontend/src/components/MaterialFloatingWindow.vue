<template>
  <div v-if="f.visible" class="mat-floating"
       :style="floatingStyle"
       :class="{ minified: f.minified }">
    <!-- 标题栏：拖拽移动 -->
    <div class="mat-floating-header" @mousedown="startDrag">
      <span class="mat-floating-title">📄 {{ f.fileName }}</span>
      <span class="mat-floating-actions">
        <button class="mf-btn" title="最小化/还原" @mousedown.stop @click="toggleMinified">{{ f.minified ? '□' : '─' }}</button>
        <button class="mf-btn" title="关闭" @mousedown.stop @click="closeFloating">✕</button>
      </span>
    </div>
    <!-- 内容区 -->
    <div v-show="!f.minified" class="mat-floating-body" v-loading="f.loading">
      <div v-if="f.html" class="md-content" v-html="f.html"></div>
      <pre v-else class="mf-text">{{ f.content }}</pre>
    </div>
    <!-- 缩放手柄 -->
    <div v-show="!f.minified" class="mat-floating-resize" @mousedown.stop="startResize"></div>
  </div>
</template>

<script setup>
import { computed, reactive, onBeforeUnmount } from 'vue'
import { useMaterialFloating } from '@/composables/useMaterialFloating'

const { state, closeFloating, toggleMinified, setPos, setSize } = useMaterialFloating()
const f = computed(() => state.floating)

const floatingStyle = computed(() => ({
  left: f.value.left + 'px',
  top: f.value.top + 'px',
  width: f.value.width + 'px',
  height: f.value.minified ? 'auto' : f.value.height + 'px'
}))

// ===== 拖拽 / 缩放 =====
const drag = reactive({ mode: null, startX: 0, startY: 0, originLeft: 0, originTop: 0, originW: 0, originH: 0 })

const startDrag = (e) => {
  drag.mode = 'move'
  drag.startX = e.clientX
  drag.startY = e.clientY
  drag.originLeft = f.value.left
  drag.originTop = f.value.top
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

const startResize = (e) => {
  drag.mode = 'resize'
  drag.startX = e.clientX
  drag.startY = e.clientY
  drag.originW = f.value.width
  drag.originH = f.value.height
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

const onMove = (e) => {
  if (drag.mode === 'move') {
    const x = drag.originLeft + (e.clientX - drag.startX)
    const y = drag.originTop + (e.clientY - drag.startY)
    setPos(Math.max(0, Math.min(x, window.innerWidth - 60)), Math.max(0, Math.min(y, window.innerHeight - 40)))
  } else if (drag.mode === 'resize') {
    setSize(Math.max(320, drag.originW + (e.clientX - drag.startX)), Math.max(240, drag.originH + (e.clientY - drag.startY)))
  }
}

const onUp = () => {
  drag.mode = null
  window.removeEventListener('mousemove', onMove)
  window.removeEventListener('mouseup', onUp)
}

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onMove)
  window.removeEventListener('mouseup', onUp)
})
</script>

<style scoped>
.mat-floating {
  position: fixed;
  z-index: 3000;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: var(--app-radius-lg, 12px);
  box-shadow: var(--app-shadow-float, 0 8px 28px rgba(0, 0, 0, 0.18));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 320px;
  min-height: 240px;
  cursor: default;
  user-select: none;
}
.mat-floating-header {
  flex: 0 0 auto;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px 0 12px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
  cursor: move;
}
.mat-floating-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-right: 8px;
}
.mat-floating-actions {
  flex: 0 0 auto;
  display: flex;
  gap: 2px;
}
.mf-btn {
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  border-radius: 4px;
  color: #606266;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}
.mf-btn:hover {
  background: #e4e7ed;
}
.mat-floating-body {
  flex: 1 1 auto;
  overflow: auto;
  /* 底部预留缩放手柄空间，避免最后一行内容被遮挡 */
  padding: 10px 12px 28px;
  user-select: text;
  min-height: 0;
}
.mf-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #303133;
  line-height: 1.7;
}
.mat-floating.minified {
  min-height: 0;
}
.mat-floating-resize {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 16px;
  height: 16px;
  cursor: nwse-resize;
  background: linear-gradient(135deg, transparent 50%, #c0c4cc 50%);
  border-bottom-right-radius: 8px;
}
/* 资料 md 内容样式（与档案页弹窗保持一致） */
.mat-floating :deep(.md-content) {
  font-size: var(--app-font-size, 14px);
  line-height: 1.8;
  color: #303133;
  word-break: break-word;
}
.mat-floating :deep(.md-content h1),
.mat-floating :deep(.md-content h2),
.mat-floating :deep(.md-content h3),
.mat-floating :deep(.md-content h4) {
  margin: 14px 0 8px;
  line-height: 1.4;
  color: #1f2d3d;
}
.mat-floating :deep(.md-content h1) { font-size: 22px; }
.mat-floating :deep(.md-content h2) { font-size: 19px; }
.mat-floating :deep(.md-content h3) { font-size: 17px; }
.mat-floating :deep(.md-content h4) { font-size: 15px; }
.mat-floating :deep(.md-content p) { margin: 6px 0; }
.mat-floating :deep(.md-content ul),
.mat-floating :deep(.md-content ol) { padding-left: 22px; margin: 6px 0; }
.mat-floating :deep(.md-content table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
}
.mat-floating :deep(.md-content th),
.mat-floating :deep(.md-content td) {
  border: 1px solid #dcdfe6;
  padding: 6px 10px;
  font-size: calc(var(--app-font-size, 14px) - 1px);
}
.mat-floating :deep(.md-content th) {
  background: #f5f7fa;
  font-weight: 600;
}
.mat-floating :deep(.md-content code) {
  background: #f5f7fa;
  color: #c7254e;
  padding: 2px 5px;
  border-radius: 3px;
  font-size: calc(var(--app-font-size, 14px) - 2px);
}
/* 代码块用 hljs 深色主题（github-dark），行内 code 保持浅色 */
.mat-floating :deep(.md-content pre) {
  background: #0d1117;
  padding: 10px 12px;
  border-radius: 6px;
  overflow: auto;
}
.mat-floating :deep(.md-content pre code) {
  background: transparent;
  color: #c9d1d9;
  padding: 0;
}
.mat-floating :deep(.md-content blockquote) {
  border-left: 3px solid #409eff;
  margin: 8px 0;
  padding: 4px 12px;
  color: #606266;
  background: #f8faff;
}
</style>
