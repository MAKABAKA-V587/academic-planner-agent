<template>
  <div class="chat-page">
    <div class="top-bar">
      <span class="title">学业规划智能Agent</span>
      <div class="nav-links">
        <router-link to="/chat">对话</router-link>
        <router-link to="/profile">档案</router-link>
        <router-link to="/calendar">日历</router-link>
        <el-button type="danger" size="small" plain @click="logout">退出登录</el-button>
      </div>
    </div>

    <!-- 全局横幅：档案页生成周报时，切到本页仍可见 -->
    <div v-if="reportGenerating" class="report-gen-banner">
      <span class="report-gen-icon">📊</span>
      <span>AI 正在后台生成学情周报，请稍候...（可在「档案」页查看进度）</span>
    </div>

    <div class="chat-layout">
      <!-- 左侧：会话列表 + 今日任务 -->
      <div :class="['session-sidebar', { collapsed: sidebarCollapsed }]">
        <template v-if="!sidebarCollapsed">
        <div class="sidebar-header">
          <h3>会话列表</h3>
          <div class="sidebar-header-actions">
            <el-button type="primary" size="small" :icon="Plus" @click="newSession">新建</el-button>
            <el-button text size="small" @click="sidebarCollapsed = true" class="sidebar-collapse-btn">
              <span style="font-size: 16px;">&#171;</span>
            </el-button>
          </div>
        </div>
        <div class="session-search">
          <el-input v-model="sessionKeyword" placeholder="搜索会话标题..." clearable size="small" :prefix-icon="Search" />
        </div>
        <div class="session-list">
          <div v-for="s in filteredSessions" :key="s.sessionId"
               :class="['session-item', { active: currentSession?.sessionId === s.sessionId }]"
               @click="switchSession(s)">
            <div class="session-title">{{ s.title || '新会话' }}</div>
            <el-dropdown trigger="click" @command="(cmd) => handleSessionAction(cmd, s)">
              <el-icon class="session-menu"><MoreFilled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="rename">重命名</el-dropdown-item>
                  <el-dropdown-item command="delete" style="color: #F56C6C">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <el-empty v-if="filteredSessions.length === 0" :description="sessionKeyword ? '未找到匹配会话' : '暂无会话'" :image-size="60" />
        </div>
        <!-- 今日任务面板 -->
        <div class="today-panel">
          <h4>今日任务</h4>
          <div v-if="todayTasks.length === 0" class="today-empty">今天没有安排</div>
          <div v-for="t in todayTasks" :key="t.eventId" class="today-task">
            <el-checkbox :model-value="eventDoneOn(t, todayDateStr())" @change="toggleComplete(t)" />
            <span :class="{ done: eventDoneOn(t, todayDateStr()) }">{{ t.title }}</span>
            <el-tag :type="t.eventType === 'plan' ? 'danger' : 'success'" size="small">{{ t.eventType === 'plan' ? '计划' : '任务' }}</el-tag>
          </div>
        </div>
        </template>
        <template v-else>
          <div class="sidebar-collapsed-strip" @click="sidebarCollapsed = false">
            <span class="sidebar-expand-icon">&#187;</span>
            <span class="sidebar-expand-text">展开</span>
          </div>
        </template>
      </div>

      <!-- 中间：聊天区域 -->
      <div class="chat-main">
        <!-- 未选择会话时显示欢迎页 -->
        <div v-if="!currentSession" class="chat-welcome">
          <div v-if="profileEmpty" class="profile-tip">
            <div class="profile-tip-icon">📝</div>
            <div class="profile-tip-body">
              <div class="profile-tip-title">学习档案还是空的</div>
              <div class="profile-tip-desc">先填写薄弱科目、考试计划和学习目标，AI 给你的建议会更精准</div>
            </div>
            <el-button type="primary" size="small" @click="router.push({ path: '/profile', query: { tab: 'profile' } })">去填写</el-button>
          </div>
          <div class="welcome-icon">👋</div>
          <h2>欢迎使用学业规划助手</h2>
          <p>选择一个已有会话，或点击左侧「新建」开始新对话</p>
        </div>
        <template v-else>
        <div class="chat-header">
          <span>{{ currentSession?.title }}</span>
          <div style="flex:1;text-align:right">
            <el-button size="small" text @click="exportSession" :disabled="messages.length === 0">导出对话</el-button>
          </div>
        </div>
        <!-- 智能复习提醒横幅 -->
        <div v-if="!reminderDismissed && reminders.length > 0" class="reminder-banner">
          <div class="reminder-item" v-for="(r, i) in reminders" :key="i">
            <span class="reminder-icon">🔔</span>
            <span class="reminder-text">{{ r }}</span>
          </div>
          <el-button class="reminder-close" text size="small" @click="dismissReminder">✕</el-button>
        </div>
        <div class="chat-messages" ref="msgContainer" @scroll="onChatScroll" @click="onMessagesClick">
          <div v-for="(msg, idx) in displayMessages" :key="msg.messageId" :class="['message-item', msg.role]">
            <div v-if="msg.role === 'user' || msg.role === 'assistant'">
              <!-- 编辑态：气泡替换为输入框 -->
              <div v-if="editingMsgId === msg.messageId" class="message-bubble message-editing">
                <el-input v-model="editContent" type="textarea" :rows="3" maxlength="1000" show-word-limit />
                <div class="edit-actions">
                  <el-button size="small" @click="editingMsgId = null">取消</el-button>
                  <el-button size="small" type="primary" @click="confirmEdit(msg)">保存</el-button>
                </div>
              </div>
              <template v-else>
                <!-- 版本切换器：重新生成保留的旧版本可切换查看 -->
                <div v-if="msg.role === 'assistant' && versionMeta(msg)?.total > 1" class="version-switcher">
                  <el-button size="small" text :disabled="versionMeta(msg).current === 0" @click="switchVersion(versionMeta(msg).round, -1)">‹ 旧版本</el-button>
                  <span class="version-label">v{{ versionMeta(msg).current + 1 }} / {{ versionMeta(msg).total }}</span>
                  <el-button size="small" text :disabled="versionMeta(msg).current === versionMeta(msg).total - 1" @click="switchVersion(versionMeta(msg).round, 1)">新版本 ›</el-button>
                </div>
                <div class="message-bubble">
                  <div class="message-content" v-html="renderContent(msg.content)"></div>
                </div>
              </template>
              <div class="message-actions">
                <template v-if="msg.role === 'assistant'">
                  <el-button size="small" text @click="copyText(msg.content)">复制</el-button>
                  <el-button size="small" text type="primary" @click="importToCalendar(msg.content)" :loading="importing" :disabled="importing">
                    {{ importing ? 'AI 识别任务中...' : '导入到日历' }}
                  </el-button>
                  <el-button size="small" text type="danger" @click="deleteMessage(msg)" :disabled="sending">删除</el-button>
                  <template v-if="idx === lastAssistantIndex">
                    <el-button size="small" text @click="regenerateStream" :disabled="sending">重新生成</el-button>
                    <el-button size="small" text type="danger" @click="deleteLastRound" :disabled="sending">删除此轮对话</el-button>
                  </template>
                </template>
                <template v-else>
                  <el-button size="small" text @click="startEdit(msg)" :disabled="sending">编辑</el-button>
                  <el-button size="small" text type="danger" @click="deleteMessage(msg)" :disabled="sending">删除</el-button>
                </template>
              </div>
            </div>
          </div>
          <div v-if="sending && !messages.some(m => String(m.messageId).startsWith('stream-'))" class="message-item assistant">
            <!-- 工具调用时的趣味动画 -->
            <div v-if="activeToolType" class="tool-calling-bubble">
              <div class="tool-icon-stage">
                <span class="tool-icon">{{ toolIcons[activeToolType] || '🤖' }}</span>
                <span class="tool-sparkle">✨</span>
              </div>
              <div class="tool-info">
                <div class="tool-name">{{ toolLabels[activeToolType] || '智能工具' }}</div>
                <div class="tool-step">{{ toolStep }}</div>
                <div class="tool-progress">
                  <span class="progress-dot" :class="{ active: toolStepIdx >= 0 }"></span>
                  <span class="progress-dot" :class="{ active: toolStepIdx >= 1 }"></span>
                  <span class="progress-dot" :class="{ active: toolStepIdx >= 2 }"></span>
                </div>
              </div>
            </div>
            <!-- 普通思考中 -->
            <div v-else class="message-bubble typing">
              <span class="typing-dots">思考中<span>.</span><span>.</span><span>.</span></span>
            </div>
          </div>
          <!-- 回到底部：固定在消息区内右下角，不随输入框高度变化，避免遮挡对话框 -->
          <div v-if="showScrollBtn" class="scroll-bottom-btn" @click="scrollToBottom">▼</div>
        </div>
        <div v-if="uploadedFiles.length" class="uploaded-files">
          <span class="uploaded-label">本会话资料：</span>
          <el-tag v-for="f in uploadedFiles" :key="f.materialId" closable size="small" class="uploaded-tag"
                  title="点击小窗预览资料内容" @click="openFloating(f)" @close="deleteUploadedFile(f)">
            {{ f.fileName }}
          </el-tag>
          <span class="uploaded-tip">AI 回答时参考这些资料</span>
        </div>
        <!-- AI 工具快捷引导：点击填入固定格式，提示用户可用的工具能力 -->
        <div class="quick-tools" v-if="!sending">
          <span class="quick-tools-label">快捷功能</span>
          <span v-for="t in quickTools" :key="t.label" class="quick-tool-chip" @click="fillToolPrompt(t)">
            {{ t.label }}
          </span>
        </div>
        <div class="chat-input">
          <el-input ref="inputRef" v-model="inputMsg" type="textarea"
                    :autosize="{ minRows: 5, maxRows: 14 }" placeholder="输入你的问题..."
                    :disabled="sending" :maxlength="1000" show-word-limit
                    @keydown.enter.exact.prevent="sendMessage" />
          <div class="input-actions">
            <el-tooltip content="为当前会话添加参考资料：临时上传，或从资料库选择" placement="top">
              <el-dropdown trigger="click" :disabled="sending || uploading" @command="handleMaterialCommand">
                <el-button size="small" :loading="uploading" :disabled="sending">
                  {{ uploading ? '上传中...' : '资料' }}<span class="material-drop-arrow">▾</span>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="upload">上传临时资料（只在本会话用）</el-dropdown-item>
                    <el-dropdown-item command="select">从资料库选择</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </el-tooltip>
            <el-tooltip :content="webSearchEnabled ? '已开启联网搜索' : '点击开启联网搜索'" placement="top">
              <el-switch v-model="webSearchEnabled" size="small"
                         :disabled="sending" style="--el-switch-on-color: #409EFF" />
            </el-tooltip>
            <span class="websearch-label">{{ webSearchEnabled ? '联网' : '联网' }}</span>
            <el-button v-if="sending" type="danger" plain @click="stopGenerate">停止</el-button>
            <el-button v-else type="primary" @click="sendMessage" :disabled="!inputMsg.trim()">发送</el-button>
          </div>
          <!-- 隐藏的文件选择框：由「资料 → 上传临时资料」触发 -->
          <input ref="fileInputRef" type="file" accept=".txt,.md,.markdown,.csv" class="file-input-hidden" @change="onFileChosen" />
        </div>
        </template>
      </div>

      <!-- 右侧：侧边日历 -->
      <div class="calendar-panel" :class="{ collapsed: calCollapsed }">
        <div class="cal-toggle" @click="calCollapsed = !calCollapsed">
          {{ calCollapsed ? '展开日历' : '收起日历' }}
        </div>
        <div v-show="!calCollapsed">
          <div class="cal-header">
            <el-button size="small" text @click="prevMonth">&#9664;</el-button>
            <span class="cal-month">{{ calYear }}年{{ calMonth }}月</span>
            <el-button size="small" text @click="nextMonth">&#9654;</el-button>
          </div>
          <div class="cal-weekdays">
            <span v-for="d in weekDays" :key="d" class="cal-weekday">{{ d }}</span>
          </div>
          <div class="cal-grid">
            <div v-for="(day, idx) in calDays" :key="idx"
                 :class="['cal-day', { 'other-month': day.otherMonth, 'today': day.isToday }]"
                 @click="openDayDetail(day)">
              <span class="cal-day-num">{{ day.day }}</span>
              <div class="cal-day-events">
                <div v-for="ev in dayEvents(day)" :key="ev.eventId"
                     :class="['cal-event-tag', ev.eventType, { done: eventDoneOn(ev, day.date), review: isReviewEvent(ev) }]"
                     :style="{ background: ev.color }"
                     :title="ev.title">
                  <span v-if="isReviewEvent(ev)" class="cal-event-review-badge">复</span>
                  <span v-if="isExamEvent(ev)" class="cal-event-exam-badge">考</span>
                  {{ ev.title }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 选择资料弹窗：从资料库勾选文件作为本会话参考 -->
    <el-dialog v-model="selectMaterialVisible" title="选择参考资料（来自资料库）" width="560px" destroy-on-close>
      <div v-if="materialLibrary.length === 0" style="text-align:center;color:#909399;padding:24px;">
        资料库暂无资料，可先到「档案 → 资料库」上传
      </div>
      <el-checkbox-group v-else v-model="selectedMaterialIds" class="material-select-list">
        <label v-for="m in materialLibrary" :key="m.materialId" class="material-select-item">
          <el-checkbox :value="m.materialId" />
          <span class="material-select-name">{{ m.fileName }}</span>
          <span class="material-select-meta">{{ m.chars ?? 0 }} 字</span>
        </label>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="selectMaterialVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSelectMaterial">确定</el-button>
      </template>
    </el-dialog>

    <!-- 日期详情弹窗 -->
    <el-dialog v-model="dayDetailVisible" :title="dayDetailTitle" width="480px" destroy-on-close>
      <div v-if="selectedEvents.length === 0" style="text-align:center;padding:20px;color:#909399;">
        当天暂无事件
      </div>
      <div v-for="ev in selectedEvents" :key="ev.eventId" class="detail-event">
        <div class="detail-event-header">
          <span :style="{ color: ev.color, fontWeight: 'bold' }">{{ ev.title }}</span>
          <el-tag v-if="isReviewEvent(ev)" class="review-tag" size="small">复习</el-tag>
          <el-tag v-else :type="ev.eventType === 'plan' ? 'danger' : ev.eventType === 'exam' ? '' : 'success'" size="small">
            {{ ev.eventType === 'plan' ? '计划' : ev.eventType === 'exam' ? '考试' : '任务' }}
          </el-tag>
        </div>
        <div class="detail-event-desc" v-if="ev.description">{{ ev.description }}</div>
        <div class="detail-event-actions">
          <el-button size="small" text type="primary" @click="openEditEvent(ev)">编辑</el-button>
          <el-button size="small" text type="danger" @click="handleDeleteEvent(ev)">删除</el-button>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="openAddEventForDate">添加事件</el-button>
        <el-button @click="dayDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 添加/编辑事件弹窗 -->
    <el-dialog v-model="eventFormVisible" :title="eventFormTitle" width="480px" destroy-on-close>
      <el-form :model="eventForm" label-width="80px" label-position="left">
        <el-form-item label="标题" required>
          <el-input v-model="eventForm.title" placeholder="请输入事件标题" maxlength="100" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="eventForm.eventType">
            <el-radio value="task">任务</el-radio>
            <el-radio value="plan">计划</el-radio>
            <el-radio value="exam">考试</el-radio>
            <el-radio value="review">复习</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="开始日期" required>
          <el-date-picker v-model="eventForm.eventDate" type="date" placeholder="选择开始日期"
                          value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker v-model="eventForm.endDate" type="date" placeholder="可选，跨天事件"
                          value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="eventForm.description" type="textarea" :rows="3" placeholder="可选，事件描述" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="eventFormVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEventForm" :disabled="!eventForm.title.trim() || !eventForm.eventDate">
          {{ isEditing ? '保存修改' : '添加' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- mermaid 图表点击放大 -->
    <div v-if="zoomSvg" class="mermaid-zoom-overlay" @click.self="zoomSvg = null">
      <div class="mermaid-zoom-box">
        <button class="mermaid-zoom-close" title="关闭" @click="zoomSvg = null">✕</button>
        <div class="mermaid-zoom-content" v-html="zoomSvg"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ChatPage' })
import { ref, computed, onMounted, onActivated, nextTick, watch, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, MoreFilled, Search } from '@element-plus/icons-vue'
import { marked } from '@/utils/markdown'
// mermaid 图表渲染（flowchart / pie / sequenceDiagram 等）
import mermaid from 'mermaid'
import request from '@/api/request'
import { isGenerating, activeToolType, reportGenerating } from '@/composables/useGenerationStatus'
import { useMaterialFloating } from '@/composables/useMaterialFloating'

// mermaid 图表块：输出为独立容器（内容实体转义，避免被当 HTML 解析），由 renderMermaid 渲染
marked.use({
  renderer: {
    code({ text, lang }) {
      if (lang && lang.toLowerCase() === 'mermaid') {
        const esc = text.trim()
          .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        // data-code 保存原始代码，渲染失败时用它回退显示原文（避免 mermaid 错误注入污染文本）
        const escAttr = esc.replace(/"/g, '&quot;')
        return `<div class="mermaid" data-code="${escAttr}">${esc}</div>\n`
      }
      return false // 其它语言走默认（highlight.js）渲染
    },
    // 有序列表：渲染为带字面编号的段落，避免编号被 <ol> 的 CSS 列表标记吞掉
    // （否则复制/导出会丢编号，且 "11."/"21." 等非 "1." 开头的行不被识别导致格式不一）
    list({ ordered, start, items }) {
      if (!ordered) return false // 无序列表走默认
      let num = (start && start > 1) ? start : 1
      return items.map(item => {
        // marked v18 的 items 为 ListItem token 对象（旧版为字符串），兼容两者
        const rawBody = typeof item === 'string' ? item : (item.body || item.raw || '')
        const cleaned = rawBody.replace(/^<li>/, '').replace(/<\/li>$/, '').trim()
        // 模型输出常自带字面编号，避免重复添加前缀
        const alreadyNumbered = /^\d+\.\s/.test(cleaned)
        const innerHtml = cleaned ? marked.parseInline(cleaned) : ''
        const prefix = alreadyNumbered ? '' : `${num}. `
        num++
        return `<p class="md-list-item">${prefix}${innerHtml}</p>`
      }).join('')
    }
  }
})

const router = useRouter()

// ============ 会话相关 ============
const sessions = ref([])
const currentSession = ref(null)
const lastUserId = ref('')
const sidebarCollapsed = ref(false)

// 会话搜索（客户端按标题过滤）
const sessionKeyword = ref('')
const filteredSessions = computed(() => {
  const kw = sessionKeyword.value.trim().toLowerCase()
  if (!kw) return sessions.value
  return sessions.value.filter(s => (s.title || '').toLowerCase().includes(kw))
})

// 学习档案是否为空（为空时欢迎页提醒先去填写）
const profileEmpty = ref(false)
const loadProfileEmpty = async () => {
  try {
    const data = await request.get('/profile')
    profileEmpty.value = !(data?.weakSubjects?.trim() || data?.examPlans?.trim() || data?.studyGoals?.trim())
  } catch {
    profileEmpty.value = false
  }
}

const loadSessions = async () => {
  // 检测用户切换，清空旧用户的会话状态
  const currentToken = localStorage.getItem('token') || ''
  if (currentToken !== lastUserId.value) {
    currentSession.value = null
    messages.value = []
    lastUserId.value = currentToken
  }
  try { sessions.value = await request.get('/sessions') } catch {}
  // 同步更新当前会话信息（标题可能已变化）
  if (currentSession.value) {
    const updated = sessions.value.find(s => s.sessionId === currentSession.value.sessionId)
    if (updated) currentSession.value = updated
  }
}

const switchSession = async (s) => {
  currentSession.value = s
  await loadMessages(s.sessionId)
  loadSessionMaterials()
}

const newSession = async () => {
  try {
    const res = await request.post('/session', { title: '新对话' })
    await loadSessions()
    currentSession.value = sessions.value.find(s => s.sessionId === res.sessionId)
    messages.value = []
    loadSessionMaterials()
  } catch {}
}

const handleSessionAction = async (cmd, s) => {
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm('确定删除该会话？', '提示', { type: 'warning' })
      await request.delete(`/session/${s.sessionId}`)
      if (currentSession.value?.sessionId === s.sessionId) {
        currentSession.value = null
        messages.value = []
      }
      await loadSessions()
    } catch {}
  } else if (cmd === 'rename') {
    try {
      const { value } = await ElMessageBox.prompt('新标题', '重命名', { inputValue: s.title })
      if (value) {
        await request.put(`/session/${s.sessionId}/title`, { title: value })
        s.title = value
      }
    } catch {}
  }
}

// ============ 消息相关 ============
const messages = ref([])
const inputMsg = ref('')
const sending = ref(false)
const importing = ref(false)
const webSearchEnabled = ref(false)
const msgContainer = ref(null)
const showScrollBtn = ref(false)
const reminders = ref([])
const reminderDismissed = ref(checkDismissed())
const inputRef = ref(null)

// AI 工具快捷引导：点击后在输入框填入固定格式，示意用户可用的工具能力
const quickTools = [
  { label: '安排任务', prompt: '帮我安排【日期，如明天】的【任务，如复习高数第二章】' },
  { label: '艾宾浩斯复习', prompt: '我刚学了【科目】的【知识点】，帮我按艾宾浩斯安排复习' },
  { label: '查知识点', prompt: '帮我查一下【知识点】相关的知识' },
  { label: '制定计划', prompt: '帮我制定一个【本周/本月】的学习计划' }
]
const fillToolPrompt = (t) => {
  inputMsg.value = t.prompt
  nextTick(() => inputRef.value?.focus())
}

// 停止生成：AbortController 中断进行中的请求
let activeController = null
const stopGenerate = () => { activeController?.abort() }

// ============ 工具调用趣味动画 ============
const toolIcons = { calendar: '📅', plan: '📋', knowledge: '📚', search: '🔍', review: '🔁' }
const toolLabels = { calendar: '日历助手', plan: '学习规划师', knowledge: '知识检索', search: '联网搜索', review: '记忆复习规划' }
const toolStepsMap = {
  calendar: ['正在分析你的日程需求...', '正在安排日历事件...', '正在同步你的日历...'],
  plan: ['正在分析学习目标...', '正在生成学习计划...', '正在提取关键任务...'],
  knowledge: ['正在搜索知识库...', '正在关联相关知识点...', '正在整理答案...'],
  search: ['正在搜索互联网...', '正在筛选最新信息...', '正在整理搜索结果...'],
  review: ['正在梳理本次学过的知识点...', '正在按遗忘曲线计算复习节点...', '正在写入日历复习任务...']
}
const toolStepIdx = ref(0)
const toolStep = computed(() => {
  const steps = toolStepsMap[activeToolType.value] || ['处理中...', '请稍候...', '即将完成...']
  return steps[Math.min(toolStepIdx.value, steps.length - 1)]
})

let toolStepTimer = null

const startToolStepTimer = () => {
  toolStepIdx.value = 0
  toolStepTimer = setInterval(() => {
    toolStepIdx.value = (toolStepIdx.value + 1) % 3
  }, 1500)
}

const stopToolStepTimer = () => {
  if (toolStepTimer) { clearInterval(toolStepTimer); toolStepTimer = null }
  toolStepIdx.value = 0
}

// 监听 activeToolType 变化：工具调用开始/结束
watch(activeToolType, (val) => {
  if (val) {
    startToolStepTimer()
  } else {
    stopToolStepTimer()
  }
})

onUnmounted(() => {
  stopToolStepTimer()
  window.removeEventListener('keydown', onZoomKeydown)
  // 组件卸载时（可能的场景），清理全局状态
  if (isGenerating.value) {
    isGenerating.value = false
    activeToolType.value = null
  }
})

function checkDismissed() {
  // 按 token 隔离关闭状态: 每次登录(token不同)横幅重新弹出
  return localStorage.getItem('reminderDismissedToken') === (localStorage.getItem('token') || '')
}

function dismissReminder() {
  reminderDismissed.value = true
  localStorage.setItem('reminderDismissedToken', localStorage.getItem('token') || '')
}

const loadMessages = async (sessionId) => {
  try {
    messages.value = await request.get(`/session/${sessionId}/messages`)
    // 消息结构可能变化（版本增删/切换会话），重置版本选择与生成中标记
    versionSelection.value = {}
    streamingRoundIdx.value = null
    await nextTick()
    scrollToBottom()
  } catch {
    // 保留现有消息，不清空
  }
}

const scrollToBottom = () => {
  const el = msgContainer.value
  if (el) el.scrollTop = el.scrollHeight
}

// ============ 轮次与版本（重新生成保留旧版本，可在同一对话框切换查看） ============
// 按 user 消息分轮：每条 user 开启一轮，其后的 assistant 为该轮的不同版本（重新生成产生）
const rounds = computed(() => {
  const res = []
  let cur = null
  messages.value.forEach((m, idx) => {
    if (m.role === 'user') {
      cur = { userIdx: idx, versions: [] }
      res.push(cur)
    } else if (m.role === 'assistant' && cur) {
      cur.versions.push(idx)
    }
  })
  return res
})

// 每轮选中的版本索引（key 为轮次在 messages 中的 userIdx），默认最新版本
const versionSelection = ref({})
// 正在重新生成中的轮次（user 在 messages 中的索引），该轮暂时只显示生成中气泡
const streamingRoundIdx = ref(null)

const getSelectedVersion = (round) => {
  const list = round.versions
  if (list.length === 0) return -1
  const sel = versionSelection.value[round.userIdx]
  return (typeof sel === 'number' && sel >= 0 && sel < list.length) ? sel : list.length - 1
}

const switchVersion = (round, dir) => {
  const list = round.versions
  if (list.length < 2) return
  const cur = getSelectedVersion(round)
  const next = Math.min(Math.max(cur + dir, 0), list.length - 1)
  versionSelection.value[round.userIdx] = next
}

// 每轮只显示选中的版本；正在重新生成的轮次显示流式气泡（旧版本暂时隐藏）
const displayMessages = computed(() => {
  const out = []
  for (const round of rounds.value) {
    out.push(messages.value[round.userIdx])
    if (streamingRoundIdx.value === round.userIdx) {
      const streamMsg = messages.value.find(m => String(m.messageId).startsWith('stream-'))
      if (streamMsg) out.push(streamMsg)
      continue
    }
    const sel = getSelectedVersion(round)
    if (sel >= 0) {
      out.push(messages.value[round.versions[sel]])
    }
  }
  return out
})

// 当前 assistant 消息所属轮次的版本信息（用于显示版本切换器）
const versionMeta = (msg) => {
  const idx = messages.value.indexOf(msg)
  if (idx < 0) return null
  for (const r of rounds.value) {
    const i = r.versions.indexOf(idx)
    if (i >= 0) return { current: i, total: r.versions.length, round: r }
  }
  return null
}

const lastAssistantIndex = computed(() => {
  for (let i = displayMessages.value.length - 1; i >= 0; i--) {
    if (displayMessages.value[i].role === 'assistant') return i
  }
  return -1
})

const onChatScroll = () => {
  const el = msgContainer.value
  if (!el) return
  showScrollBtn.value = el.scrollHeight - el.scrollTop - el.clientHeight > 200
}

// 表格块规范化：去掉表格行前导空格 + 在表格块前补空行
// 原因：AI 输出常在表格前紧跟文字行（无空行）且表格行带缩进，marked 会把表格行并入
// 上一块的续行（尤其是有序列表项），而自定义 list renderer 用 parseInline 处理原始文本，
// 只解析行内元素、不渲染表格，导致表格变成纯文本管道符。分离成独立块后即可正常渲染。
const separateTableBlocks = (txt) => {
  const lines = txt.split('\n')
  const out = []
  let inCode = false
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (/^\s*```/.test(line)) { out.push(line); i++; inCode = !inCode; continue }
    // 表格行判定：以 | 开头且行内还有第二个 |（GFM 表格行允许末尾无 |，如 "| a | b | ← 注释"）
    const isPipe = /^\s*\|[^|]*\|/.test(line)
    if (!isPipe || inCode) { out.push(line); i++; continue }
    // 收集连续管道行（一个表格块）
    const run = []
    while (i < lines.length && /^\s*\|[^|]*\|/.test(lines[i])) { run.push(lines[i]); i++ }
    // 与上一段分离（表格前补空行）
    if (out.length > 0 && out[out.length - 1].trim() !== '') out.push('')
    // 去掉表格行前导空格，保证 marked 的 GFM 表格正则能匹配
    out.push(...run.map(l => l.replace(/^\s+/, '')))
  }
  return out.join('\n')
}

// 把连续的 | 管道行自动补成 GFM 表格（缺表头分隔行时自动补一行）
const autoRenderTables = (txt) => {
  const lines = txt.split('\n')
  const out = []
  let inCode = false
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    // 代码块围栏：块内管道行不处理
    if (/^\s*```/.test(line)) { out.push(line); i++; inCode = !inCode; continue }
    const isPipe = /^\s*\|.*\|\s*$/.test(line)
    if (!isPipe || inCode) { out.push(line); i++; continue }
    // 收集连续管道行
    const run = []
    while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) { run.push(lines[i]); i++ }
    // 已有分隔行（|---| --- |）则原样保留
    const hasDelimiter = run.some(l => /^\s*\|[\s:|-]+\|\s*$/.test(l) && /-/.test(l))
    if (!hasDelimiter && run.length >= 2) {
      const cells = run[0].split('|').slice(1, -1)
      out.push('|' + cells.map(() => ' ').join('|') + '|')   // 空表头
      out.push('|' + cells.map(() => '---').join('|') + '|') // 分隔行
      out.push(...run)
    } else {
      out.push(...run)
    }
  }
  return out.join('\n')
}

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

const renderContent = (text) => {
  if (!text) return ''
  // 过滤掉 MEMORY 块，防止暴露给用户
  text = text.replace(/---MEMORY---[\s\S]*?---END---/g, '')
  // 代码块围栏去缩进：AI 常在列表项内输出缩进围栏（如 "     ```java"），
  // CommonMark 规定围栏缩进最多 3 空格，marked v18 对超缩进围栏闭合识别失败，
  // 会把后续标题/正文吞进代码块（渲染成高亮代码）。统一将围栏行顶格即可正确闭合。
  text = text.split('\n').map(line => /^\s*```/.test(line) ? line.trimStart() : line).join('\n')
  // 将 ```markdown / ```md 代码块还原为普通 markdown（让表格正常渲染）
  text = text.replace(/```(?:markdown|md)\s*\n?([\s\S]*?)\s*```/gi, '$1')
  // 预处理正文：去行尾空白 + 在"段落后紧跟编号行"间插空行
  // 原因：CommonMark 规定只有 "1." 开头的行能中断段落成列表，"11./21." 行会并入上段；
  // 插空行后 marked 才能将 11./21. 正确解析为有序列表。代码块内保持原样。
  text = text.split('```').map((part, i) => {
    if (i % 2 === 1) return part
    const out = []
    for (const line of part.split('\n')) {
      const prevTrimmed = (out[out.length - 1] || '').trim()
      if (/^\d+\.\s/.test(line.trim()) && prevTrimmed &&
          !/^\d+\.\s/.test(prevTrimmed) && !/^[-*+]\s/.test(prevTrimmed) &&
          !/^#{1,6}\s/.test(prevTrimmed) && !/^\|/.test(prevTrimmed)) {
        out.push('')
      }
      out.push(line.trimEnd())
    }
    return out.join('\n')
  }).join('```')
  // 表格块规范化：分离被并入列表/段落的表格行（补空行 + 去缩进）
  text = separateTableBlocks(text)
  // 缺失表头分隔行的管道表格自动补全
  text = autoRenderTables(text)
  try {
    // 分段解析：搜索块用折叠框，非搜索部分正常渲染
    const searchRegex = /<!--search_start-->([\s\S]*?)<!--search_end-->/g
    const parts = []
    let lastIndex = 0
    let match
    while ((match = searchRegex.exec(text)) !== null) {
      if (match.index > lastIndex) {
        parts.push(marked.parse(text.slice(lastIndex, match.index)))
      }
      const innerHtml = marked.parse(match[1].trim())
      parts.push('<details class="search-collapse"><summary>🔍 联网搜索结果（点击展开）</summary><div class="search-result">' + innerHtml + '</div></details>')
      lastIndex = match.index + match[0].length
    }
    if (lastIndex < text.length) {
      parts.push(marked.parse(text.slice(lastIndex)))
    }
    return parts.length > 0 ? parts.join('') : marked.parse(text)
  } catch {
    return text.replace(/\n/g, '<br>')
  }
}

mermaid.initialize({
  startOnLoad: false,
  securityLevel: 'strict',
  theme: 'default',
  fontFamily: 'inherit'
})

// 渲染消息中的 mermaid 图表：DOM 更新后逐个渲染未处理节点，失败回退为原文代码块
const renderMermaid = async () => {
  await nextTick()
  const nodes = document.querySelectorAll('.chat-messages .mermaid')
  if (!nodes.length) return
  for (const node of nodes) {
    if (node.getAttribute('data-processed') === 'true') continue
    try {
      await mermaid.run({ nodes: [node] })
      // 解析失败时 mermaid 可能不抛异常，而是直接生成「错误提示图」，需要检测并回退
      if (node.querySelector('svg .error-icon, svg .error-text, .error-icon, .error-text')) {
        throw new Error('mermaid parse error')
      }
      node.setAttribute('data-processed', 'true')
    } catch (e) {
      // 语法不支持或解析失败：用 data-code 里保存的原文回退（mermaid 失败时会污染节点文本）
      const code = node.getAttribute('data-code') || escapeHtml((node.textContent || '').trim())
      node.outerHTML = `<pre class="mermaid-fallback"><code>${code}</code></pre>`
    }
  }
}

// 消息内容变化或流式结束后触发 mermaid 渲染（流式中途内容不完整，跳过）
watch(() => displayMessages.value.map(m => String(m.messageId) + '|' + (m.content || '')).join('\n'), (val, old) => {
  if (val === old) return
  if (sending.value) return
  renderMermaid()
})
watch(sending, (v) => {
  if (!v) renderMermaid()
})

// ============ mermaid 图表点击放大 ============
const zoomSvg = ref('')

// 点击消息区内的 mermaid 图表 → 打开放大查看（事件委托，覆盖流式后新增的节点）
const onMessagesClick = (e) => {
  const target = e.target.closest('.mermaid')
  if (!target) return
  const svg = target.querySelector('svg')
  if (!svg) return
  // mermaid 渲染的 SVG 内联了 style="max-width:<渲染像素>px"，会锁定原始尺寸导致放大不了；
  // 克隆时移除内联样式，尺寸交给放大层 CSS 按 viewBox 等比缩放
  const clone = svg.cloneNode(true)
  clone.removeAttribute('style')
  zoomSvg.value = clone.outerHTML
}

// Esc 关闭放大层
const onZoomKeydown = (e) => {
  if (e.key === 'Escape' && zoomSvg.value) zoomSvg.value = ''
}

const copyText = (text) => {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

// ============ 上传学习资料 ============
const uploadedFiles = ref([])
// 资料小窗预览：点击会话资料标签打开全局悬浮窗查看内容
const { openFloating } = useMaterialFloating()
const uploading = ref(false)
const materialLibrary = ref([])
const selectMaterialVisible = ref(false)
const selectedMaterialIds = ref([])
const fileInputRef = ref(null)

/** 资料下拉：upload=触发隐藏文件框（临时上传），select=打开资料库选择弹窗 */
const handleMaterialCommand = (cmd) => {
  if (cmd === 'upload') fileInputRef.value?.click()
  else if (cmd === 'select') openSelectMaterial()
}

/** 文件选择框选中后交给 handleUpload（临时上传），并复位以便重复选择同一文件 */
const onFileChosen = (e) => {
  const f = e.target.files?.[0]
  if (f) handleUpload(f)
  e.target.value = ''
}

/** 加载当前会话已启用的参考资料（选择资料库文件 / 临时上传挂到会话的） */
const loadSessionMaterials = async () => {
  const sid = currentSession.value?.sessionId
  if (!sid) { uploadedFiles.value = []; return }
  try {
    uploadedFiles.value = await request.get(`/chat/sessions/${sid}/materials`)
  } catch { uploadedFiles.value = [] }
}

const handleUpload = async (file) => {
  const name = file.name || ''
  const ext = name.split('.').pop()?.toLowerCase() || ''
  if (!['txt', 'md', 'markdown', 'csv'].includes(ext)) {
    ElMessage.warning('仅支持 .txt / .md / .csv 文本文件')
    return false
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 2MB')
    return false
  }
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    form.append('temp', 'true') // 聊天页上传 = 临时，只挂当前会话，不进资料库
    const res = await request.post('/materials?temp=true', form)
    // 临时上传的资料：同时挂到当前会话，AI 在本会话中参考
    await request.post(`/chat/sessions/${currentSession.value?.sessionId}/materials`, { materialId: res.materialId })
    ElMessage.success(`已上传「${res.fileName}」，已加入本会话参考资料（临时）`)
    await loadSessionMaterials()
  } catch {
    // 拦截器已统一提示
  } finally {
    uploading.value = false
  }
  return false
}

const deleteUploadedFile = async (f) => {
  try {
    await request.delete(`/chat/sessions/${currentSession.value?.sessionId}/materials/${f.materialId}`)
    uploadedFiles.value = uploadedFiles.value.filter(x => x.materialId !== f.materialId)
    ElMessage.success(`已从本会话移除「${f.fileName}」`)
  } catch {}
}

/** 打开"选择资料"弹窗：列出资料库全部文件，勾选后挂到当前会话 */
const openSelectMaterial = async () => {
  try {
    materialLibrary.value = await request.get('/materials')
  } catch { materialLibrary.value = [] }
  selectedMaterialIds.value = uploadedFiles.value.map(m => m.materialId)
  selectMaterialVisible.value = true
}

const confirmSelectMaterial = async () => {
  const sid = currentSession.value?.sessionId
  const currentIds = uploadedFiles.value.map(m => m.materialId)
  const wantIds = selectedMaterialIds.value
  try {
    // 新增勾选 → 挂载
    for (const m of materialLibrary.value) {
      if (wantIds.includes(m.materialId) && !currentIds.includes(m.materialId)) {
        await request.post(`/chat/sessions/${sid}/materials`, { materialId: m.materialId })
      }
    }
    // 取消勾选 → 移除
    for (const id of currentIds) {
      if (!wantIds.includes(id)) {
        await request.delete(`/chat/sessions/${sid}/materials/${id}`)
      }
    }
    selectMaterialVisible.value = false
    await loadSessionMaterials()
    ElMessage.success('参考资料已更新')
  } catch {}
}

const importToCalendar = async (text) => {
  importing.value = true
  isGenerating.value = true
  activeToolType.value = 'calendar'
  try {
    const res = await request.post('/calendar/extract-text', { text })
    await refreshBothCalendars()
    await loadTodayTasks()
    const count = res?.count ?? 0
    if (count > 0) {
      ElMessage.success(`已导入 ${count} 个事件到日历`)
    } else {
      ElMessage.info('未识别到可导入的事件')
    }
  } catch (e) {
    const msg = e?.response?.data?.msg || e?.message || '导入失败'
    ElMessage.error(typeof msg === 'string' ? msg : '导入失败')
  } finally {
    importing.value = false
    isGenerating.value = false
    activeToolType.value = null
  }
}

const sendMessage = async () => {
  const msg = inputMsg.value.trim()
  if (!msg || !currentSession.value) return
  inputMsg.value = ''
  // 立即在本地显示用户消息，不等AI回复
  const tempId = 'local-' + Date.now()
  messages.value.push({ messageId: tempId, role: 'user', content: msg, createTime: new Date().toISOString() })
  await nextTick()
  scrollToBottom()
  sending.value = true
  isGenerating.value = true
  activeToolType.value = detectToolType(msg)
  activeController = new AbortController()

  // 意图检测：需要工具操作（日历/知识库/学习计划）或联网搜索 → 走带工具的流式端点
  // （工具多轮在服务端内部执行，最终回答流式输出，首 token 即可见，无需等整段生成完）
  if (webSearchEnabled.value || needsToolExecution(msg)) {
    await streamChat(msg, activeController.signal, '/api/chat/tool-stream')
  } else {
    await streamChat(msg, activeController.signal, '/api/chat/stream')
  }
}

/** 检测用户消息是否需要工具调用 */
const needsToolExecution = (msg) => {
  const lower = msg.toLowerCase()
  const toolKeywords = [
    // 日历操作
    '日历', '日程', '安排', '排期', '导入日历', '添加到日历', '帮我安排',
    '增加', '添加', '新建', '创建', '加一个', '帮我加', '加任务', '添加任务',
    '删除', '删掉', '取消', '清空', '清除',
    '今天有什么', '本周有什么', '今天什么',
    // 学习计划
    '学习计划', '制定计划', '生成计划', '复习计划', '备考计划', '今日计划', '计划',
    // 艾宾浩斯复习排期
    '艾宾浩斯', '遗忘曲线', '安排复习', '帮我复习', '复习一下', '巩固记忆', '加深记忆',
    // 知识库检索
    '怎么做', '怎么学', '什么是', '是什么', '如何', '介绍一下', '解释一下', '检索', '知识点',
    // 联网搜索
    '搜索', '搜一下', '查一下', '查一查',
  ]
  return toolKeywords.some(kw => lower.includes(kw.toLowerCase()))
}

/** 判断当前消息对应的工具类型 */
const detectToolType = (msg) => {
  const lower = msg.toLowerCase()
  // 具体工具意图优先：即使开着联网开关，命中日历/计划/知识库语义也要显示对应动画，
  // 避免"发什么都显示联网搜索动画"覆盖真实工具
  // 复习意图最优先：「安排复习」同时含"安排"（日历词）和"复习"（复习词），必须让复习动画赢
  if (/艾宾浩斯|遗忘曲线|安排复习|帮我复习|复习一下|巩固记忆|加深记忆/.test(lower)) return 'review'
  if (/日历|日程|安排|排期|导入日历|添加到日历|增加|添加|新建|创建|加一个|帮我加|加任务|清空|今天有什么|本周有什么/.test(lower)) return 'calendar'
  if (/学习计划|制定计划|生成计划|复习计划|备考计划|今日计划|计划/.test(lower)) return 'plan'
  if (/怎么做|怎么学|什么是|是什么|介绍一下|解释一下|如何|检索|知识点|查一下|查一查|相关知识|学习一下|了解一下|知识/.test(lower)) return 'knowledge'
  // 无具体意图且开启联网时，才兜底显示联网搜索动画
  if (webSearchEnabled.value) return 'search'
  return null
}

/** 流式端点：纯文本与工具调用共用一个渲染逻辑（url 区分端点）。
 *  首 token 到达才创建流式气泡，此前保留"工具调用中/思考中"动画；rAF 批量渲染。 */
const streamChat = async (msg, signal, url, options = {}) => {
  // insertAfter：流式气泡插入到该消息之后（重新生成时插到轮次末尾），null 表示追加到消息流末尾
  // isRegenerate：重新生成模式，结束/失败/停止时清除"生成中"标记
  const { insertAfter = null, isRegenerate = false } = options
  const token = localStorage.getItem('token') || ''
  const body = JSON.stringify({
    sessionId: currentSession.value.sessionId,
    message: msg,
    webSearch: webSearchEnabled.value
  })

  let streamBuffer = ''
  let streamMsgId = null
  let rafId = null

  // 懒创建流式气泡：首个 token 到达时创建（工具阶段无 token，动画继续显示）
  const ensureStreamMsg = () => {
    if (streamMsgId) {
      return messages.value.find(m => m.messageId === streamMsgId)
    }
    streamMsgId = 'stream-' + Date.now()
    const streamMsg = { messageId: streamMsgId, role: 'assistant', content: '', createTime: new Date().toISOString() }
    if (insertAfter !== null && insertAfter >= 0 && insertAfter < messages.value.length) {
      messages.value.splice(insertAfter + 1, 0, streamMsg)
    } else {
      messages.value.push(streamMsg)
    }
    return messages.value.find(m => m.messageId === streamMsgId)
  }

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
      body,
      signal
    })

    if (!response.ok) {
      const errText = await response.text()
      console.error('SSE 连接失败:', response.status, errText)
      throw new Error(`服务器错误 ${response.status}: ${errText.slice(0, 100)}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      const chunk = decoder.decode(value, { stream: true })

      // 检测结束标记 / 错误标记
      if (chunk.includes('[DONE]')) {
        streamBuffer += chunk.replace('[DONE]', '').replace(/^\n/, '')
        break
      }
      if (chunk.includes('[ERROR]')) {
        streamBuffer += chunk.replace(/\[ERROR\][^\n]*/g, '').replace(/^\n/, '')
        ElMessage.error('生成失败，请稍后重试')
        break
      }

      streamBuffer += chunk
      // 首个非空 token：创建流式气泡
      if (!streamMsgId && chunk.trim()) {
        ensureStreamMsg()
      }
      // requestAnimationFrame 批量渲染，每帧最多一次 DOM 更新
      if (streamMsgId && !rafId) {
        rafId = requestAnimationFrame(() => {
          const m = messages.value.find(mm => mm.messageId === streamMsgId)
          if (m) m.content = streamBuffer
          rafId = null
          scrollToBottom()
        })
      }
    }

    // 最后一帧刷干净
    const lastMsg = streamMsgId ? messages.value.find(m => m.messageId === streamMsgId) : null
    if (lastMsg) lastMsg.content = streamBuffer

    // 流结束：从服务器加载完整消息（替换流式临时消息）
    await loadMessages(currentSession.value.sessionId)
    await loadSessions()
    await fetchSideEvents()
    await loadTodayTasks()
  } catch (e) {
    const canceled = e?.name === 'AbortError'
    if (canceled) {
      // 用户手动停止：保留已生成部分并标记
      const lastStream = messages.value.find(m => String(m.messageId).startsWith('stream-'))
      if (lastStream) {
        const partial = (lastStream.content || streamBuffer || '').replace(/[（(]已停止[)）]$/, '')
        lastStream.content = partial ? partial + '\n\n*(已手动停止)*' : '*(已手动停止)*'
        scrollToBottom()
      }
    } else {
      // 非手动停止的网络/服务端中断：若已有部分内容则保留并标注，避免"流式输出突然消失/一次性闪现"
      const lastStream = messages.value.find(m => String(m.messageId).startsWith('stream-'))
      if (lastStream && lastStream.content.trim()) {
        lastStream.content = lastStream.content.replace(/[（(]已中断[)）]$/, '') + '\n\n*(生成中断，可点击重新生成)*'
        scrollToBottom()
      } else {
        console.error('发送消息失败:', e)
        ElMessage.error('发送失败，请重试')
        // 失败时移除临时消息 + 流式消息
        messages.value = messages.value.filter(m => !String(m.messageId).startsWith('local-') && !String(m.messageId).startsWith('stream-'))
        await loadMessages(currentSession.value.sessionId)
      }
    }
  }
  finally {
    sending.value = false
    isGenerating.value = false
    activeToolType.value = null
    activeController = null
    if (isRegenerate) streamingRoundIdx.value = null
  }
}

/** 重新生成回复（流式，带工具）：基于最后一条用户消息重新回答。
 *  生成期间旧版本暂时隐藏，完成后可通过版本切换器查看旧版本 */
const regenerateStream = async () => {
  if (!currentSession.value) return
  sending.value = true
  isGenerating.value = true
  const roundList = rounds.value
  const lastRound = roundList[roundList.length - 1]
  const lastUserMsg = lastRound ? messages.value[lastRound.userIdx] : null
  activeToolType.value = lastUserMsg ? detectToolType(lastUserMsg.content) : null
  activeController = new AbortController()
  if (!lastRound) {
    sending.value = false
    isGenerating.value = false
    activeToolType.value = null
    activeController = null
    return
  }
  // 该轮进入"生成中"状态：旧版本暂时隐藏，新回复就位后可通过切换器查看
  streamingRoundIdx.value = lastRound.userIdx
  const insertAfter = lastRound.versions.length
    ? lastRound.versions[lastRound.versions.length - 1]
    : lastRound.userIdx
  // 服务端自行取最后一条用户消息，无需传 message
  await streamChat(null, activeController.signal, '/api/chat/regenerate/stream', { insertAfter, isRegenerate: true })
}

const deleteLastRound = async () => {
  if (!currentSession.value) return
  try {
    await ElMessageBox.confirm('确定删除最后一轮对话？', '提示', { type: 'warning' })
    await request.delete(`/session/${currentSession.value.sessionId}/last-round`)
    await loadMessages(currentSession.value.sessionId)
    ElMessage.success('已删除')
  } catch {}
}

// ============ 消息编辑 / 删除 ============
const editingMsgId = ref(null)
const editContent = ref('')

const startEdit = (msg) => {
  editingMsgId.value = msg.messageId
  editContent.value = msg.content
}

// 消息是否尚未保存到后端（本地临时 / 流式中产生的临时 ID）
const isLocalMessage = (id) => String(id).startsWith('local-') || String(id).startsWith('stream-')

const confirmEdit = async (msg) => {
  const content = editContent.value.trim()
  if (!content) { ElMessage.warning('内容不能为空'); return }
  editingMsgId.value = null
  // 本地临时消息未入库（如生成中途被停止）：无法调用后端编辑接口，
  // 直接移除临时消息，用编辑后的内容作为新问题重新提问
  if (isLocalMessage(msg.messageId)) {
    messages.value = messages.value.filter(m => !String(m.messageId).startsWith('local-') && !String(m.messageId).startsWith('stream-'))
    inputMsg.value = content
    if (currentSession.value) await sendMessage()
    return
  }
  try {
    await request.put(`/message/${msg.messageId}/edit`, { content })
    await loadMessages(currentSession.value.sessionId)
    // 编辑后旧回复已被级联删除，自动基于新问题流式重新生成
    await regenerateStream()
  } catch (e) {
    ElMessage.error('修改失败，请重试')
  }
}

const deleteMessage = async (msg) => {
  try {
    await ElMessageBox.confirm('将删除该消息及之后的所有消息，确定？', '提示', { type: 'warning' })
    if (isLocalMessage(msg.messageId)) {
      // 本地临时消息：仅从界面移除，无需调后端
      messages.value = messages.value.filter(m => m.messageId !== msg.messageId)
      return
    }
    await request.delete(`/message/${msg.messageId}`)
    await loadMessages(currentSession.value.sessionId)
    ElMessage.success('已删除')
  } catch {}
}

// ============ 导出对话 ============
const exportSession = () => {
  if (!currentSession.value || messages.value.length === 0) return
  const lines = []
  lines.push(`# ${currentSession.value.title || '对话记录'}`)
  lines.push(`导出时间：${new Date().toLocaleString('zh-CN')}`)
  lines.push('')
  for (const m of messages.value) {
    if (m.role !== 'user' && m.role !== 'assistant') continue
    lines.push(`## ${m.role === 'user' ? '我' : 'AI'}`)
    lines.push(m.content)
    lines.push('')
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${currentSession.value.title || '会话'}.md`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出为 Markdown 文件')
}

// ============ 日历共享数据 ============
const weekDays = ['一', '二', '三', '四', '五', '六', '日']
const today = new Date()
const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const pad = (n) => String(n).padStart(2, '0')

const buildCalDays = (year, month) => {
  const firstDay = new Date(year, month - 1, 1)
  const startDayOfWeek = firstDay.getDay() || 7
  const daysInMonth = new Date(year, month, 0).getDate()
  const prevMonthDays = new Date(year, month - 1, 0).getDate()
  const days = []
  for (let i = startDayOfWeek - 2; i >= 0; i--) {
    const d = prevMonthDays - i
    const m = month === 1 ? 12 : month - 1
    const y = month === 1 ? year - 1 : year
    days.push({ day: d, date: `${y}-${pad(m)}-${pad(d)}`, otherMonth: true, isToday: false })
  }
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${year}-${pad(month)}-${pad(d)}`
    days.push({ day: d, date: dateStr, otherMonth: false, isToday: dateStr === todayStr })
  }
  const remaining = 42 - days.length
  for (let d = 1; d <= remaining; d++) {
    const m = month === 12 ? 1 : month + 1
    const y = month === 12 ? year + 1 : year
    days.push({ day: d, date: `${y}-${pad(m)}-${pad(d)}`, otherMonth: true, isToday: false })
  }
  return days
}

const filterEvents = (eventsArr, day) => {
  if (day.otherMonth) return []
  return eventsArr.filter(ev => {
    const start = ev.eventDate
    const end = ev.endDate || ev.eventDate
    return day.date >= start && day.date <= end
  })
}

const fetchMonth = async (monthStr) => {
  try {
    return await request.get('/calendar', { params: { month: monthStr } })
  } catch { return [] }
}

// ============ 侧边日历 ============
const calCollapsed = ref(true)
const calYear = ref(new Date().getFullYear())
const calMonth = ref(new Date().getMonth() + 1)
const calEvents = ref([])

const calDays = computed(() => buildCalDays(calYear.value, calMonth.value))
const dayEvents = (day) => filterEvents(calEvents.value, day)

/** 复习任务识别：标题以「复习·」开头（艾宾浩斯排期工具创建） */
const isReviewEvent = (ev) => !!ev && (ev.eventType === 'review' || (typeof ev.title === 'string' && ev.title.startsWith('复习·')))

/** 跨天任务（endDate 非空）按天打卡：完成状态看 completedDates 是否含当天；单日任务看 completed */
const isMultiDayEvent = (ev) => !!ev && !!ev.endDate
const doneDateList = (ev) => (typeof ev?.completedDates === 'string' && ev.completedDates)
  ? ev.completedDates.split(',').filter(Boolean) : []
const eventDoneOn = (ev, date) => isMultiDayEvent(ev) ? doneDateList(ev).includes(date) : !!ev?.completed
const todayDateStr = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 考试任务识别：type=exam */
const isExamEvent = (ev) => !!ev && ev.eventType === 'exam'

const prevMonth = () => {
  if (calMonth.value === 1) { calYear.value--; calMonth.value = 12 } else calMonth.value--
  fetchSideEvents()
}
const nextMonth = () => {
  if (calMonth.value === 12) { calYear.value++; calMonth.value = 1 } else calMonth.value++
  fetchSideEvents()
}
const fetchSideEvents = async () => {
  calEvents.value = await fetchMonth(`${calYear.value}-${pad(calMonth.value)}`)
}

// 大日历已独立为 /calendar 页面；此处仅刷新侧边日历
const refreshBothCalendars = async () => {
  await fetchSideEvents()
}

const getEventsByDate = (date) => {
  return calEvents.value.filter(ev => {
    const start = ev.eventDate
    const end = ev.endDate || ev.eventDate
    return date >= start && date <= end
  })
}

// ============ 日期详情弹窗 ============
const dayDetailVisible = ref(false)
const selectedDate = ref('')
const selectedEvents = ref([])
const dayDetailTitle = computed(() => selectedDate.value ? `${selectedDate.value} 详情` : '')

const openDayDetail = (day) => {
  if (day.otherMonth) return
  selectedDate.value = day.date
  selectedEvents.value = getEventsByDate(day.date)
  dayDetailVisible.value = true
}

const handleDeleteEvent = async (ev) => {
  try {
    await ElMessageBox.confirm(`确定删除事件「${ev.title}」？`, '提示', { type: 'warning' })
    await request.delete(`/calendar/${ev.eventId}`)
    dayDetailVisible.value = false
    await refreshBothCalendars()
    await loadTodayTasks() // 今日任务面板同步刷新，避免残留已删除任务点击报错
  } catch {}
}

const clearAllEvents = async () => {
  try {
    await ElMessageBox.confirm('确定清空所有日历事件？此操作不可恢复。', '警告', { type: 'warning' })
    await request.delete('/calendar/all')
    ElMessage.success('已清空')
    await refreshBothCalendars()
    await loadTodayTasks()
  } catch {}
}

// ============ 添加/编辑事件 ============
const eventFormVisible = ref(false)
const isEditing = ref(false)
const editingEventId = ref(null)

const eventForm = ref({
  title: '',
  eventType: 'task',
  eventDate: '',
  endDate: '',
  description: ''
})

const eventFormTitle = computed(() => isEditing.value ? '修改事件' : '添加事件')

const resetEventForm = () => {
  eventForm.value = { title: '', eventType: 'task', eventDate: '', endDate: '', description: '' }
}

const openAddEvent = () => {
  resetEventForm()
  isEditing.value = false
  editingEventId.value = null
  eventFormVisible.value = true
}

const openAddEventForDate = () => {
  resetEventForm()
  if (selectedDate.value) eventForm.value.eventDate = selectedDate.value
  isEditing.value = false
  editingEventId.value = null
  eventFormVisible.value = true
}

const openEditEvent = (ev) => {
  eventForm.value = {
    title: ev.title || '',
    eventType: ev.eventType || 'task',
    eventDate: ev.eventDate || '',
    endDate: ev.endDate || '',
    description: ev.description || ''
  }
  isEditing.value = true
  editingEventId.value = ev.eventId
  eventFormVisible.value = true
}

const submitEventForm = async () => {
  if (eventForm.value.endDate && eventForm.value.eventDate > eventForm.value.endDate) {
    ElMessage.error('开始日期不能晚于结束日期')
    return
  }
  const payload = {
    title: eventForm.value.title.trim(),
    eventType: eventForm.value.eventType,
    eventDate: eventForm.value.eventDate,
    endDate: eventForm.value.endDate || null,
    description: eventForm.value.description.trim() || null
  }
  try {
    if (isEditing.value) {
      await request.put(`/calendar/${editingEventId.value}`, payload)
      ElMessage.success('事件已修改')
    } else {
      await request.post('/calendar', payload)
      ElMessage.success('事件已添加')
    }
    eventFormVisible.value = false
    dayDetailVisible.value = false
    await refreshBothCalendars()
    await loadTodayTasks()
  } catch (e) {
    const msg = e?.response?.data?.msg || e?.message || '操作失败'
    ElMessage.error(typeof msg === 'string' ? msg : '操作失败')
  }
}

// ============ 今日任务 ============
const todayTasks = ref([])

const loadTodayTasks = async () => {
  try {
    todayTasks.value = await request.get('/calendar/today')
  } catch { todayTasks.value = [] }
}

const toggleComplete = async (t) => {
  try {
    const target = !eventDoneOn(t, todayDateStr())
    await request.put(`/calendar/${t.eventId}/complete`, { completed: target })
    if (isMultiDayEvent(t)) {
      // 跨天任务：本地更新打卡日期串，只影响今天
      const set = new Set(doneDateList(t))
      if (target) set.add(todayDateStr()); else set.delete(todayDateStr())
      t.completedDates = [...set].sort().join(',')
    } else {
      t.completed = !t.completed
    }
    await refreshBothCalendars()
  } catch {}
}

// ============ 其他 ============
const logout = () => { localStorage.removeItem('token'); router.push('/login') }

const loadReminders = async () => {
  reminderDismissed.value = checkDismissed()
  try {
    reminders.value = await request.get('/reminder/check')
  } catch { reminders.value = [] }
}

onMounted(async () => {
  await loadSessions()
  await fetchSideEvents()
  await loadTodayTasks()
  await loadReminders()
  loadProfileEmpty()
  loadSessionMaterials()
  renderMermaid()
  window.addEventListener('keydown', onZoomKeydown)
})

onActivated(async () => {
  await loadSessions()
  await fetchSideEvents()
  await loadTodayTasks()
  await loadReminders()
  loadProfileEmpty()
  loadSessionMaterials()
  renderMermaid()
})
</script>

<style scoped>
/* ============ 工具调用趣味动画 ============ */
.tool-calling-bubble {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 14px;
  border-bottom-left-radius: 6px;
  padding: 14px 20px;
  max-width: 360px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  animation: fadeInUp 0.3s ease;
}
.tool-icon-stage {
  position: relative;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ecf5ff;
  border-radius: 12px;
  flex-shrink: 0;
}
.tool-icon {
  font-size: 24px;
  animation: toolBounce 1s ease-in-out infinite;
}
.tool-sparkle {
  position: absolute;
  top: -2px;
  right: -6px;
  font-size: 12px;
  animation: sparkle 0.8s ease-in-out infinite;
}
.tool-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.tool-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.tool-step {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tool-progress {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}
.progress-dot {
  width: 16px;
  height: 4px;
  border-radius: 2px;
  background: #e4e7ed;
  transition: all 0.4s ease;
}
.progress-dot.active {
  background: #409EFF;
  width: 28px;
}

@keyframes toolBounce {
  0%, 100% { transform: translateY(0) scale(1); }
  50% { transform: translateY(-5px) scale(1.06); }
}
@keyframes sparkle {
  0%, 100% { opacity: 0; transform: scale(0.5) rotate(-20deg); }
  50% { opacity: 1; transform: scale(1.1) rotate(10deg); }
}
@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 手机端适配 */
@media (max-width: 640px) {
  .tool-calling-bubble {
    max-width: 260px;
    padding: 10px 14px;
    gap: 10px;
  }
  .tool-icon-stage {
    width: 36px;
    height: 36px;
    border-radius: 10px;
  }
  .tool-icon { font-size: 20px; }
  .tool-name { font-size: 12px; }
  .tool-step { font-size: 11px; }
}

/* ============ 原有样式 ============ */
/* 页面：浅天空蓝底 + 角落低饱和装饰色块（纯色无渐变），与档案页风格统一 */
.chat-page { display: flex; flex-direction: column; height: 100vh; background: #f4f9ff; position: relative; overflow: hidden; }
.chat-page::before,
.chat-page::after {
  content: ''; position: fixed; border-radius: 50%; pointer-events: none; z-index: 0;
}
.chat-page::before { width: 380px; height: 380px; background: #dcebff; top: -150px; right: -120px; }
.chat-page::after { width: 300px; height: 300px; background: #ddf2ea; bottom: -110px; left: -110px; }
.top-bar { position: relative; z-index: 10; display: flex; justify-content: space-between; align-items: center; padding: 0 24px; height: 56px; background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,0.06); flex-shrink: 0; }
.title { font-size: 18px; font-weight: 700; color: #303133; letter-spacing: 0.5px; }
.nav-links { display: flex; align-items: center; gap: 20px; }
.nav-links a { text-decoration: none; color: #606266; font-size: 16px; }
.nav-links a:hover, .nav-links a.active { color: #409eff; }
/* 全局周报生成横幅 */
.report-gen-banner {
  position: relative; z-index: 10; display: flex; align-items: center; gap: 8px;
  padding: 8px 24px; background: #e6f4ff; border-bottom: 1px solid #bcdcff;
  color: #409eff; font-size: 13px; flex-shrink: 0;
}
.report-gen-icon { font-size: 15px; }
.chat-layout { position: relative; z-index: 1; display: flex; flex: 1; overflow: hidden; }

/* 侧边栏 */
.session-sidebar { width: 240px; min-width: 180px; max-width: 420px; background: #fff; border-right: 1px solid #edf0f4; display: flex; flex-direction: column; flex-shrink: 0; resize: horizontal; overflow: hidden; transition: width 0.2s; }
.session-sidebar.collapsed { width: 36px; min-width: 36px; max-width: 36px; resize: none; }
.sidebar-header { display: flex; align-items: center; justify-content: space-between; padding: 16px 12px 12px; border-bottom: 1px solid #edf0f4; }
.sidebar-header h3 { margin: 0; font-size: 15px; font-weight: 600; color: #303133; }
.sidebar-header-actions { display: flex; align-items: center; gap: 4px; }
.sidebar-collapse-btn { padding: 2px 4px; color: #909399; }
.sidebar-collapse-btn:hover { color: #409EFF; }
/* 折叠态 */
.sidebar-collapsed-strip { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; height: 100%; cursor: pointer; color: #909399; transition: color 0.2s; }
.sidebar-collapsed-strip:hover { color: #409EFF; background: #f0f4ff; }
.sidebar-expand-icon { font-size: 18px; line-height: 1; }
.sidebar-expand-text { font-size: 11px; writing-mode: vertical-rl; letter-spacing: 2px; }
.session-list { flex: 1; overflow-y: auto; padding: 8px; }
.session-item { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; margin-bottom: 2px; border-radius: 8px; cursor: pointer; transition: all 0.2s; }
.session-item:hover { background: #f0f4ff; }
.session-item.active { background: #ecf3ff; font-weight: 500; }
.session-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; }
.session-menu { cursor: pointer; color: #909399; }

/* 今日任务 */
.today-panel { padding: 14px 12px; border-top: 1px solid #edf0f4; max-height: 180px; overflow-y: auto; flex-shrink: 0; }
.today-panel h4 { margin: 0 0 10px 0; font-size: 14px; font-weight: 600; color: #303133; }
.today-empty { font-size: 12px; color: #909399; text-align: center; padding: 8px; }
.today-task { display: flex; align-items: center; gap: 6px; padding: 4px 0; font-size: 13px; }
.today-task .done { text-decoration: line-through; color: #909399; }

/* 聊天区域（透明露出页面底与装饰色块） */
.chat-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; position: relative; background: transparent; }
.chat-welcome { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: #909399; }
/* 档案为空提醒卡片 */
.profile-tip {
  display: flex; align-items: center; gap: 12px;
  background: #fff; border: 1px solid #d9ecff; border-radius: 12px;
  padding: 14px 18px; margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.08);
  max-width: 520px;
}
.profile-tip-icon { font-size: 26px; flex-shrink: 0; }
.profile-tip-body { flex: 1; text-align: left; }
.profile-tip-title { font-size: 14px; font-weight: 600; color: #303133; }
.profile-tip-desc { font-size: 12px; color: #909399; margin-top: 3px; line-height: 1.5; }
.chat-welcome .welcome-icon { font-size: 56px; margin-bottom: 8px; }
.chat-welcome h2 { font-size: 20px; color: #606266; margin: 0; }
.chat-welcome p { font-size: 14px; margin: 0; }
.chat-header { display: flex; align-items: center; padding: 14px 20px; border-bottom: 1px solid #edf0f4; font-size: 15px; font-weight: 500; background: #fff; margin: 8px; border-radius: 10px; }
/* 智能复习提醒横幅 */
.reminder-banner { margin: 0 12px; display: flex; flex-direction: column; gap: 4px; position: relative; }
.reminder-item { display: flex; align-items: center; padding: 10px 28px 10px 16px; background: #fffbe6; border: 1px solid #ffe58f; border-radius: 8px; font-size: 14px; color: #8c6d00; }
.reminder-icon { margin-right: 8px; font-size: 16px; flex-shrink: 0; }
.reminder-text { line-height: 1.5; }
.reminder-close { position: absolute; top: 4px; right: 6px; color: #8c6d00; font-size: 14px; padding: 2px 6px; }
.chat-messages { flex: 1; overflow-y: auto; padding: 20px 24px; position: relative; }
.chat-messages::-webkit-scrollbar { width: 6px; }
.chat-messages::-webkit-scrollbar-thumb { background: #d8dce3; border-radius: 3px; }
.chat-messages::-webkit-scrollbar-track { background: transparent; }
.message-item { margin-bottom: 18px; display: flex; align-items: flex-start; }
.message-item.user { justify-content: flex-end; }
.message-item.assistant { justify-content: flex-start; }
.message-bubble { max-width: 90%; padding: 12px 16px; border-radius: 14px; line-height: 1.7; font-size: var(--app-font-size, 15px); word-break: break-word; font-family: 'Segoe UI Emoji', 'Segoe UI', system-ui, -apple-system, sans-serif; }
.message-item.user .message-bubble { background: linear-gradient(135deg, #4a9dff 0%, #3d8bfd 100%); color: #fff; border-bottom-right-radius: 4px; box-shadow: 0 2px 10px rgba(61, 139, 253, 0.18); }
.message-item.assistant .message-bubble { background: #fff; color: #303133; border: 1px solid #ebedf1; border-bottom-left-radius: 4px; box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05); max-width: 90%; }
.message-bubble.typing { color: #909399; padding: 14px 20px; }
.typing-dots span { animation: blink 1.4s infinite; }
.typing-dots span:nth-child(2) { animation-delay: 0.2s; }
.typing-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0; } 40% { opacity: 1; } }
.message-actions { margin-top: 6px; display: flex; gap: 4px; flex-wrap: wrap; min-height: 28px; opacity: 0; transition: opacity 0.2s ease; }
.message-item:hover .message-actions { opacity: 1; }
.message-item.assistant .message-actions { justify-content: flex-start; }
.message-item.user .message-actions { justify-content: flex-end; }
/* 版本切换器：重新生成保留的旧版本可在同一轮内切换查看 */
.version-switcher { display: flex; align-items: center; gap: 4px; margin-bottom: 4px; font-size: 12px; }
.version-switcher .el-button { padding: 0 4px; height: 20px; font-size: 12px; }
.version-label { color: #909399; font-size: 12px; user-select: none; }
/* 消息编辑态 */
.message-editing { background: #fff; box-shadow: 0 1px 6px rgba(0,0,0,0.1); }
.edit-actions { margin-top: 8px; display: flex; justify-content: flex-end; gap: 8px; }
.message-content :deep(table) { border-collapse: collapse; margin: 8px 0; font-size: calc(var(--app-font-size, 15px) - 2px); }
.message-content :deep(th), .message-content :deep(td) { border: 1px solid #dcdfe6; padding: 6px 10px; text-align: left; }
.message-content :deep(th) { background: #f5f7fa; }
.message-content :deep(p) { margin: 4px 0; }
.message-content :deep(.md-list-item) { margin: 3px 0 3px 6px; padding-left: 4px; }
.message-content :deep(pre) { background: #f5f7fa; padding: 8px 12px; border-radius: 6px; overflow-x: auto; }
.message-content :deep(code) { font-size: calc(var(--app-font-size, 15px) - 2px); }
/* 联网搜索折叠 */
.message-content :deep(.search-collapse) { margin: 8px 0; padding: 10px 14px; background: #f0f9eb; border: 1px solid #c8e6c9; border-radius: 8px; font-size: calc(var(--app-font-size, 15px) - 2px); max-width: 600px; }
.message-content :deep(.search-collapse summary) { cursor: pointer; font-weight: 600; color: #2e7d32; padding: 4px 0; outline: none; }
.message-content :deep(.search-result) { margin-top: 8px; line-height: 1.6; }
.message-content :deep(.search-result p) { margin: 4px 0; }
.message-content :deep(.search-result a) { color: #409EFF; word-break: break-all; }
.message-content :deep(.mermaid) { margin: 10px 0; text-align: center; background: #fff; border: 1px solid #e4e7ed; border-radius: 8px; padding: 10px; overflow-x: auto; }
.message-content :deep(.mermaid svg) { max-width: 100%; height: auto; }
.message-content :deep(.mermaid-fallback) { background: #f5f7fa; padding: 8px 12px; border-radius: 6px; overflow-x: auto; color: #c7254e; }
/* mermaid 图表点击放大 */
.mermaid-zoom-overlay { position: fixed; inset: 0; z-index: 3000; background: rgba(0, 0, 0, 0.65); display: flex; align-items: center; justify-content: center; padding: 40px; animation: zoomFadeIn 0.2s ease; }
.mermaid-zoom-box { position: relative; width: 90vw; max-width: 1100px; max-height: 88vh; background: #fff; border-radius: 10px; padding: 20px; box-shadow: 0 8px 40px rgba(0, 0, 0, 0.3); overflow: auto; }
.mermaid-zoom-close { position: absolute; top: 8px; right: 8px; width: 32px; height: 32px; border: none; border-radius: 50%; background: rgba(0, 0, 0, 0.06); color: #606266; font-size: 14px; cursor: pointer; z-index: 1; transition: background 0.2s; }
.mermaid-zoom-close:hover { background: rgba(0, 0, 0, 0.12); color: #303133; }
.mermaid-zoom-content { text-align: center; }
.mermaid-zoom-content :deep(svg) { width: 100% !important; height: auto !important; }
@keyframes zoomFadeIn { from { opacity: 0; } to { opacity: 1; } }
.scroll-bottom-btn { position: sticky; bottom: 20px; float: right; margin: -48px 4px 8px 0; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; background: #409eff; color: #fff; border-radius: 50%; font-size: 14px; cursor: pointer; z-index: 5; box-shadow: 0 2px 8px rgba(0,0,0,0.15); opacity: 0.9; transition: opacity 0.2s; }
.scroll-bottom-btn:hover { opacity: 1; }
.uploaded-files { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin: 10px 16px 0; padding: 8px 12px; background: #fff; border: 1px solid #e6e9ef; border-radius: 12px; }
/* 选择资料弹窗 */
.material-select-list { max-height: 50vh; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; line-height: normal; font-size: 14px; }
.material-select-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; cursor: pointer; transition: background 0.15s ease; }
.material-select-item:hover { background: #f5f8ff; }
.material-select-name { flex: 1; font-size: 13px; color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; line-height: 18px; }
.material-select-meta { font-size: 12px; color: #c0c4cc; line-height: 18px; }
.uploaded-label { font-size: 12px; color: #909399; }
.uploaded-tip { font-size: 12px; color: #c0c4cc; }
.uploaded-tag { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.uploaded-tag:hover { border-color: #409eff; color: #409eff; }
.uploaded-tag .el-tag__close { cursor: pointer; }
/* AI 工具快捷引导：浅色胶囊，点击填入固定格式 */
.quick-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 4px 16px 0; }
.quick-tools-label { font-size: 12px; color: #a0a6b3; margin-right: 2px; }
.quick-tool-chip { font-size: 12px; color: #5a6a85; background: #eef3fb; border: 1px solid #e2eaf6; border-radius: 12px; padding: 3px 12px; cursor: pointer; transition: all 0.2s ease; user-select: none; }
.quick-tool-chip:hover { color: #409EFF; background: #e3f0ff; border-color: #bcd9ff; transform: translateY(-1px); }
.chat-input { display: flex; gap: 10px; padding: 12px 16px; background: #fff; margin: 10px 16px 14px; border-radius: 12px; align-items: flex-end; border: 1px solid #e6e9ef; box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05); transition: border-color 0.2s ease, box-shadow 0.2s ease; }
.chat-input:focus-within { border-color: #409eff; box-shadow: 0 2px 14px rgba(64, 158, 255, 0.14); }
.chat-input .el-textarea { flex: 1; }
.input-actions { display: flex; flex-direction: column; align-items: center; gap: 6px; flex-shrink: 0; }
.material-drop-arrow { font-size: 11px; margin-left: 3px; opacity: .7; }
.file-input-hidden { display: none; }
.websearch-label { font-size: 11px; color: #909399; user-select: none; }

/* 侧边日历 */
.calendar-panel { width: 310px; min-width: 200px; max-width: 500px; background: #f4f9ff; border-left: 1px solid #e4e7ed; display: flex; flex-direction: column; flex-shrink: 0; overflow-y: auto; transition: width 0.2s; resize: horizontal; }
.calendar-panel.collapsed { width: 48px; min-width: 48px; max-width: 48px; resize: none; }
.cal-toggle { padding: 8px; text-align: center; font-size: 12px; color: #409EFF; cursor: pointer; border-bottom: 1px solid #e4e7ed; }
.cal-header { display: flex; align-items: center; justify-content: space-between; padding: 8px; }
.cal-month { font-weight: 600; font-size: 15px; }
.cal-weekdays { display: grid; grid-template-columns: repeat(7, 1fr); text-align: center; font-size: 12px; color: #909399; padding: 4px 0; border-bottom: 1px solid #ebeef5; }
.cal-weekday { font-weight: 500; }
.cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px; padding: 4px; }
.cal-day { height: 72px; border-radius: 6px; padding: 2px; cursor: pointer; font-size: 12px; display: flex; flex-direction: column; overflow: hidden; transition: background 0.15s; }
.cal-day:hover { background: #ecf5ff; }
.cal-day.today { background: #ecf5ff; border: 1px solid #409EFF; }
.cal-day.other-month { opacity: 0.35; }
.cal-day-num { font-weight: 500; text-align: center; padding: 2px 0; }
.cal-day-events { flex: 1; overflow: hidden; }
.cal-event-tag { font-size: 10px; color: #fff; padding: 1px 4px; border-radius: 3px; margin: 1px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 复习任务：紫色徽标 + 描边，一眼可辨 */
.cal-event-tag.review { box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.55); }
.cal-event-review-badge { display: inline-block; font-size: 8px; line-height: 1; padding: 1px 2px; margin-right: 3px; border-radius: 2px; background: rgba(255, 255, 255, 0.25); }
/* 考试任务：「考」徽标 */
.cal-event-exam-badge { display: inline-block; font-size: 8px; line-height: 1; padding: 1px 2px; margin-right: 3px; border-radius: 2px; background: rgba(255, 255, 255, 0.25); }
.cal-event-tag.done { opacity: 0.4; text-decoration: line-through; }

/* 弹窗 */
.detail-event { padding: 12px; margin-bottom: 8px; background: #f5f7fa; border-radius: 8px; }
/* 复习标签：紫色，与任务(绿)/计划(红)/考试(蓝)区分 */
.detail-event .el-tag.review-tag { --el-tag-bg-color: #9B59B6; --el-tag-border-color: #9B59B6; --el-tag-text-color: #fff; }
.detail-event-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.detail-event-desc { font-size: 13px; color: #606266; line-height: 1.6; white-space: pre-wrap; word-break: break-word; max-height: 240px; overflow-y: auto; }
.detail-event-actions { margin-top: 6px; text-align: right; }
</style>
