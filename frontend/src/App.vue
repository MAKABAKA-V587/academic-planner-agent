<template>
  <!-- 全局生成中横幅：切到其他页面时提示 AI 仍在生成 -->
  <div v-if="isGenerating && currentRoute !== '/chat'" class="global-generating-banner">
    <span class="banner-icon">📅</span>
    <span class="banner-text">{{ activeToolType === 'calendar' ? '正在后台导入日程...' : 'AI 正在后台生成中...' }}</span>
    <span class="banner-dot-pulse"></span>
  </div>
  <router-view v-slot="{ Component }">
    <!-- 只缓存对话/档案页保留滚动位置等状态；日历页每次进入重新挂载，避免残留旧页面 -->
    <keep-alive :include="['ChatPage', 'ProfilePage']">
      <component :is="Component" />
    </keep-alive>
  </router-view>
  <!-- 全局资料悬浮小窗：挂在根节点，切换路由不销毁，与档案页预览弹窗共用数据 -->
  <MaterialFloatingWindow />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import MaterialFloatingWindow from '@/components/MaterialFloatingWindow.vue'
import { isGenerating, activeToolType } from '@/composables/useGenerationStatus'
import { initFontSize } from '@/utils/fontSize'

initFontSize()

const route = useRoute()
const currentRoute = computed(() => route.path)
</script>

<style>
html {
  font-size: 17px;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
}

/* 全局生成中横幅：悬浮胶囊，不占位、不遮挡页面 */
.global-generating-banner {
  position: fixed;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 18px;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  color: #409EFF;
  font-size: 13px;
  font-weight: 600;
  border-radius: 18px;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.2);
  animation: slideDown 0.3s ease;
}

.banner-icon {
  font-size: 16px;
  animation: bannerPulse 1.2s ease-in-out infinite;
}
.banner-text {
  color: #409EFF;
}
.banner-dot-pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #409EFF;
  animation: bannerDot 1.2s ease-in-out infinite;
}

@keyframes slideDown {
  from { opacity: 0; transform: translate(-50%, -12px); }
  to { opacity: 1; transform: translate(-50%, 0); }
}
@keyframes bannerPulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.2); }
}
@keyframes bannerDot {
  0%, 100% { opacity: 0.3; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.3); }
}
</style>
