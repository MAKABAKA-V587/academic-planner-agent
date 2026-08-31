<template>
  <div class="calendar-page">
    <div class="top-bar">
      <span class="title">学业规划智能Agent</span>
      <div class="nav-links">
        <router-link to="/chat">对话</router-link>
        <router-link to="/profile">档案</router-link>
        <router-link to="/calendar" class="active">日历</router-link>
        <el-button type="danger" size="small" plain @click="logout">退出登录</el-button>
      </div>
    </div>

    <!-- 全局横幅：档案页生成周报时，切到本页仍可见 -->
    <div v-if="reportGenerating" class="report-gen-banner">
      <span class="report-gen-icon">📊</span>
      <span>AI 正在后台生成学情周报，请稍候...</span>
    </div>

    <!-- 大日历 -->
    <div class="calendar-fullscreen">
      <div class="cal-fs-header">
        <h3>学习日历</h3>
      </div>
      <div class="cal-fs-toolbar">
        <el-button size="small" text @click="calFsPrevMonth">&#9664;</el-button>
        <span class="cal-fs-month">{{ calFsYear }}年{{ calFsMonth }}月</span>
        <el-button size="small" text @click="calFsNextMonth">&#9654;</el-button>
        <div style="flex:1;text-align:right">
          <el-button size="small" type="primary" @click="openAddEvent">添加事件</el-button>
          <el-button size="small" type="danger" plain @click="clearAllEvents">清空所有事件</el-button>
        </div>
      </div>
      <div class="cal-fs-weekdays">
        <span v-for="d in weekDays" :key="d" class="cal-fs-weekday">{{ d }}</span>
      </div>
      <div class="cal-fs-grid">
        <div v-for="(day, idx) in calFsDays" :key="idx"
             :class="['cal-fs-day', { 'other-month': day.otherMonth, 'today': day.isToday }]"
             @click="openDayDetail(day)">
          <span class="cal-fs-day-num">{{ day.day }}</span>
          <div class="cal-fs-events">
            <div v-for="ev in dayFsEvents(day)" :key="ev.eventId"
                 :class="['cal-fs-tag', ev.eventType, { done: eventDoneOn(ev, day.date), review: isReviewEvent(ev) }]"
                 :style="{ background: ev.color }"
                 :title="ev.title">
              <span v-if="isReviewEvent(ev)" class="cal-fs-review-badge">复</span>
              <span v-if="isExamEvent(ev)" class="cal-fs-exam-badge">考</span>
              {{ ev.title }}
            </div>
          </div>
        </div>
      </div>
    </div>

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
        <el-button v-if="isEditing" type="success" plain
                   style="margin-right:auto"
                   @click="markEditingEventDone">
          {{ editingEventCompleted ? '取消完成' : '完成任务' }}
        </el-button>
        <el-button @click="eventFormVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEventForm" :disabled="!eventForm.title.trim() || !eventForm.eventDate">
          {{ isEditing ? '保存修改' : '添加' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
defineOptions({ name: 'CalendarPage' })
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportGenerating } from '@/composables/useGenerationStatus'

const router = useRouter()

const logout = () => { localStorage.removeItem('token'); router.push('/login') }

// ==================== 日历功能 ====================
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
  try { return await request.get('/calendar', { params: { month: monthStr } }) }
  catch { return [] }
}

const calFsYear = ref(new Date().getFullYear())
const calFsMonth = ref(new Date().getMonth() + 1)
const calFsEvents = ref([])

const calFsDays = computed(() => buildCalDays(calFsYear.value, calFsMonth.value))
const dayFsEvents = (day) => filterEvents(calFsEvents.value, day)

/** 复习任务识别：type=review 或标题以「复习·」开头（兼容旧数据） */
const isReviewEvent = (ev) => !!ev && (ev.eventType === 'review' || (typeof ev.title === 'string' && ev.title.startsWith('复习·')))

/** 考试任务识别：type=exam */
const isExamEvent = (ev) => !!ev && ev.eventType === 'exam'

/** 跨天任务（endDate 非空）按天打卡：完成状态看 completedDates 是否含当天；单日任务看 completed */
const isMultiDayEvent = (ev) => !!ev && !!ev.endDate
const doneDateList = (ev) => (typeof ev?.completedDates === 'string' && ev.completedDates)
  ? ev.completedDates.split(',').filter(Boolean) : []
const eventDoneOn = (ev, date) => isMultiDayEvent(ev) ? doneDateList(ev).includes(date) : !!ev?.completed
const todayDateStr = () => {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const calFsPrevMonth = () => {
  if (calFsMonth.value === 1) { calFsYear.value--; calFsMonth.value = 12 } else calFsMonth.value--
  fetchFsEvents()
}
const calFsNextMonth = () => {
  if (calFsMonth.value === 12) { calFsYear.value++; calFsMonth.value = 1 } else calFsMonth.value++
  fetchFsEvents()
}
const fetchFsEvents = async () => {
  calFsEvents.value = await fetchMonth(`${calFsYear.value}-${pad(calFsMonth.value)}`)
}

// ============ 日期详情弹窗 ============
const dayDetailVisible = ref(false)
const selectedDate = ref('')
const selectedEvents = ref([])
const dayDetailTitle = computed(() => selectedDate.value ? `${selectedDate.value} 详情` : '')

const openDayDetail = (day) => {
  if (day.otherMonth) return
  selectedDate.value = day.date
  selectedEvents.value = calFsEvents.value.filter(ev => {
    const start = ev.eventDate
    const end = ev.endDate || ev.eventDate
    return day.date >= start && day.date <= end
  })
  dayDetailVisible.value = true
}

const handleDeleteEvent = async (ev) => {
  try {
    await ElMessageBox.confirm(`确定删除事件「${ev.title}」？`, '提示', { type: 'warning' })
    await request.delete(`/calendar/${ev.eventId}`)
    dayDetailVisible.value = false
    await fetchFsEvents()
  } catch {}
}

const clearAllEvents = async () => {
  try {
    await ElMessageBox.confirm('确定清空所有日历事件？此操作不可恢复。', '警告', { type: 'warning' })
    await request.delete('/calendar/all')
    ElMessage.success('已清空')
    await fetchFsEvents()
  } catch {}
}

// ============ 添加/编辑事件 ============
const eventFormVisible = ref(false)
const isEditing = ref(false)
const editingEventId = ref(null)
const editingEventCompleted = ref(false)

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
  // 跨天任务按天打卡：编辑弹窗展示的是「今天」的打卡状态
  editingEventCompleted.value = eventDoneOn(ev, todayDateStr())
  eventFormVisible.value = true
}

// 编辑弹窗内一键完成任务（可补完成历史任务 / 提前完成未来任务）
const markEditingEventDone = async () => {
  const target = !editingEventCompleted.value
  try {
    await request.put(`/calendar/${editingEventId.value}/complete`, { completed: target })
    ElMessage.success(target ? '任务已完成' : '已取消完成')
    eventFormVisible.value = false
    dayDetailVisible.value = false
    await fetchFsEvents()
  } catch (e) {
    const msg = e?.response?.data?.msg || e?.message || '操作失败'
    ElMessage.error(typeof msg === 'string' ? msg : '操作失败')
  }
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
    await fetchFsEvents()
  } catch (e) {
    const msg = e?.response?.data?.msg || e?.message || '操作失败'
    ElMessage.error(typeof msg === 'string' ? msg : '操作失败')
  }
}

onMounted(() => {
  fetchFsEvents()
})
</script>

<style scoped>
/* 页面：浅天空蓝底 + 角落低饱和装饰色块，与对话/档案页风格统一 */
.calendar-page { display: flex; flex-direction: column; height: 100vh; background: #f4f9ff; position: relative; overflow: hidden; }
.calendar-page::before,
.calendar-page::after {
  content: ''; position: fixed; border-radius: 50%; pointer-events: none; z-index: 0;
}
.calendar-page::before { width: 380px; height: 380px; background: #dcebff; top: -150px; right: -120px; }
.calendar-page::after { width: 300px; height: 300px; background: #ddf2ea; bottom: -110px; left: -110px; }
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

/* 大日历 */
.calendar-fullscreen { position: relative; z-index: 1; flex: 1; display: flex; flex-direction: column; padding: 16px 24px 32px; overflow-y: auto; background: #f4f9ff; }
.cal-fs-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.cal-fs-header h3 { margin: 0; font-size: 20px; }
.cal-fs-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.cal-fs-month { font-weight: 600; font-size: 16px; min-width: 100px; text-align: center; }
.cal-fs-weekdays { display: grid; grid-template-columns: repeat(7, 1fr); text-align: center; font-size: 13px; color: #909399; padding: 8px 0; border-bottom: 1px solid #ebeef5; }
.cal-fs-weekday { font-weight: 500; }
.cal-fs-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; padding: 8px 0; }
.cal-fs-day { min-height: 90px; border-radius: 8px; padding: 6px; cursor: pointer; background: #fff; border: 1px solid #ebeef5; font-size: 13px; overflow: hidden; transition: background 0.15s; }
.cal-fs-day:hover { background: #ecf5ff; }
.cal-fs-day.today { background: #ecf5ff; border-color: #409EFF; }
.cal-fs-day.other-month { opacity: 0.3; background: #f5f5f5; }
.cal-fs-day-num { font-weight: 600; display: block; margin-bottom: 4px; }
.cal-fs-events { display: flex; flex-direction: column; gap: 2px; }
.cal-fs-tag { font-size: 11px; color: #fff; padding: 1px 5px; border-radius: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cal-fs-tag.done { opacity: 0.4; text-decoration: line-through; }
/* 复习任务：紫色徽标 + 描边，一眼可辨 */
.cal-fs-tag.review { box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.55); }
.cal-fs-review-badge { display: inline-block; font-size: 9px; line-height: 1; padding: 1px 2px; margin-right: 3px; border-radius: 2px; background: rgba(255, 255, 255, 0.25); }
/* 考试任务：「考」徽标 */
.cal-fs-exam-badge { display: inline-block; font-size: 9px; line-height: 1; padding: 1px 2px; margin-right: 3px; border-radius: 2px; background: rgba(255, 255, 255, 0.25); }

/* 弹窗 */
.detail-event { padding: 12px; margin-bottom: 8px; background: #f5f7fa; border-radius: 8px; }
/* 复习标签：紫色，与任务(绿)/计划(红)/考试(蓝)区分 */
.detail-event .el-tag.review-tag { --el-tag-bg-color: #9B59B6; --el-tag-border-color: #9B59B6; --el-tag-text-color: #fff; }
.detail-event-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.detail-event-desc { font-size: 13px; color: #606266; line-height: 1.6; white-space: pre-wrap; word-break: break-word; max-height: 240px; overflow-y: auto; }
.detail-event-actions { margin-top: 6px; text-align: right; }

@media (max-width: 640px) {
  .cal-fs-day { min-height: 48px; }
  .cal-fs-tag { font-size: 10px; }
  .cal-fs-events { display: none; }
}
</style>
