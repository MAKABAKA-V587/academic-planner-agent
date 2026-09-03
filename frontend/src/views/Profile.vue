<template>
  <div class="page-container">
    <div class="top-bar">
      <span class="title">学业规划智能Agent</span>
      <div class="nav-links">
        <router-link to="/chat">对话</router-link>
        <router-link to="/profile">档案</router-link>
        <router-link to="/calendar">日历</router-link>
        <el-button type="danger" size="small" plain @click="handleLogout">退出登录</el-button>
      </div>
    </div>
    <div class="page-content">
      <!-- 欢迎信息 -->
      <div class="welcome-row">
        <div class="welcome-avatar">{{ avatarEmoji || usernameChar }}</div>
        <div>
          <div class="welcome-title">{{ greeting }}，{{ username }}</div>
          <div class="welcome-date">{{ todayDisplay }}，这是你的学业档案中心</div>
        </div>
      </div>
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- Tab 1: 学业档案 -->
        <el-tab-pane label="学业档案" name="profile">
          <div class="profile-layout">
            <el-card class="profile-section">
              <div class="section-header">薄弱科目</div>
              <p class="section-desc">记录你目前需要加强的科目和知识点</p>
              <el-input v-model="form.weakSubjects" type="textarea" :rows="3"
                placeholder="例如：数学线性代数薄弱，英语阅读差"
                maxlength="500" show-word-limit />
            </el-card>

            <el-card class="profile-section">
              <div class="section-header">考试计划</div>
              <p class="section-desc">你计划参加的重要考试和时间节点</p>
              <el-input v-model="form.examPlans" type="textarea" :rows="3"
                placeholder="例如：2026年12月考研"
                maxlength="500" show-word-limit />
            </el-card>

            <el-card class="profile-section">
              <div class="section-header">学习目标</div>
              <p class="section-desc">你的学习目标和期望达成的成果</p>
              <el-input v-model="form.studyGoals" type="textarea" :rows="3"
                placeholder="例如：考研上岸，数学130+"
                maxlength="500" show-word-limit />
            </el-card>

            <div class="profile-save">
              <el-button type="primary" :loading="saving" @click="handleSave" size="large">保存修改</el-button>
            </div>
          </div>
        </el-tab-pane>

        <!-- Tab 2: 学习画像 -->
        <el-tab-pane label="学习画像" name="tags">
          <el-card class="portrait-card">
            <div class="card-head">
              <h2 class="card-title">我的学习画像</h2>
              <span v-if="tags.length > 0" class="tag-count">{{ tags.length }} 个标签</span>
            </div>
            <p class="card-desc">基于你的历史对话自动生成，标签越大表示越常提及。</p>
            <div v-if="tags.length > 0" class="word-cloud">
              <span v-for="(tag, i) in tags" :key="i" class="cloud-tag"
                :style="cloudStyle(tag, i)">
                {{ tag.name }}
              </span>
            </div>
            <el-empty v-else description="暂无学习画像标签，对话后将自动生成" :image-size="60" />
          </el-card>

          <el-card class="memory-card" style="margin-top:20px">
            <h2 class="card-title">记忆管理</h2>
            <p class="card-desc">动态记忆随对话自动更新，同主题冲突会被覆盖，可在此手动触发或清除。</p>
            <div class="memory-actions">
              <el-button type="primary" :loading="extracting" @click="handleExtract">手动提取记忆</el-button>
              <el-popconfirm title="确定要清除全部动态学习记忆吗？档案静态记忆将保留。" @confirm="handleClear">
                <template #reference>
                  <el-button type="danger" :loading="clearing" plain>清除动态记忆</el-button>
                </template>
              </el-popconfirm>
            </div>
            <div class="memory-hint">
              <p>手动提取：从最近对话中立即提取学习特征并生成画像标签。</p>
              <p>清除记忆：仅清除对话提取的动态记忆，保留档案静态记忆。</p>
            </div>
          </el-card>
        </el-tab-pane>

        <!-- Tab 3: 学习数据看板 -->
        <el-tab-pane label="学习数据" name="statistics">
          <div v-loading="statsLoading" class="charts-grid">
            <!-- 学习进度总览：进度环 + 数字卡 + 连续天数 -->
            <el-card class="full-width-card">
              <h3>学习进度</h3>
              <div v-if="!progressEmpty" class="progress-row">
                <div class="progress-gauge">
                  <div ref="gaugeChartRef" class="chart-box"></div>
                </div>
                <div class="progress-stats">
                  <div class="stat-card">
                    <div class="stat-num primary">{{ progressOverview.completed ?? 0 }}</div>
                    <div class="stat-label">累计完成</div>
                  </div>
                  <div class="stat-card">
                    <div class="stat-num success">{{ progressOverview.thisWeekCompleted ?? 0 }}</div>
                    <div class="stat-label">本周完成</div>
                  </div>
                  <div class="stat-card">
                    <div class="stat-num warning">{{ progressOverview.pending ?? 0 }}</div>
                    <div class="stat-label">待完成</div>
                  </div>
                  <div class="stat-card">
                    <div class="stat-num danger">{{ progressOverview.streak ?? 0 }}</div>
                    <div class="stat-label">连续学习(天)</div>
                  </div>
                </div>
              </div>
              <div v-else class="chart-empty progress-empty">
                <div class="chart-empty-icon">✅</div>
                <div>暂无学习进度数据</div>
                <span>在日历中添加学习任务并勾选完成，这里会显示进度</span>
              </div>
            </el-card>

            <!-- 每日完成趋势 -->
            <el-card class="full-width-card">
              <h3>每日完成趋势（近14天）</h3>
              <div class="chart-box-wrap">
                <div ref="completionChartRef" class="chart-box-wide"></div>
                <div v-if="completionEmpty" class="chart-empty">
                  <div class="chart-empty-icon">📅</div>
                  <div>近 14 天暂无完成任务</div>
                  <span>勾选完成任务后这里会显示每日完成趋势</span>
                </div>
              </div>
            </el-card>

            <!-- 科目分布饼图 -->
            <el-card>
              <h3>科目分布</h3>
              <div class="chart-box-wrap">
                <div ref="pieChartRef" class="chart-box"></div>
                <div v-if="pieEmpty" class="chart-empty">
                  <div class="chart-empty-icon">📊</div>
                  <div>暂无科目分布数据</div>
                  <span>多和 AI 聊聊学习内容后自动生成</span>
                </div>
              </div>
            </el-card>

            <!-- 薄弱项雷达图 -->
            <el-card>
              <h3>薄弱项分析</h3>
              <div class="chart-box-wrap">
                <div ref="radarChartRef" class="chart-box"></div>
                <div v-if="radarEmpty" class="chart-empty">
                  <div class="chart-empty-icon">🎯</div>
                  <div>暂无薄弱项数据</div>
                  <span>在档案中填写薄弱科目后自动生成</span>
                </div>
              </div>
            </el-card>

            <!-- 学习活跃趋势 -->
            <el-card class="full-width-card">
              <h3>学习活跃趋势（近7天）</h3>
              <div class="chart-box-wrap">
                <div ref="trendChartRef" class="chart-box-wide"></div>
                <div v-if="trendEmpty" class="chart-empty">
                  <div class="chart-empty-icon">📈</div>
                  <div>近 7 天暂无对话记录</div>
                  <span>开始和 AI 对话后这里会显示学习趋势</span>
                </div>
              </div>
            </el-card>

            <!-- 记忆增长曲线 -->
            <el-card class="full-width-card">
              <h3>记忆增长曲线（近7天）</h3>
              <div class="chart-box-wrap">
                <div ref="growthChartRef" class="chart-box-wide"></div>
                <div v-if="growthEmpty" class="chart-empty">
                  <div class="chart-empty-icon">🧠</div>
                  <div>近 7 天暂无记忆更新</div>
                  <span>持续对话后这里会显示记忆增长曲线</span>
                </div>
              </div>
            </el-card>
          </div>
        </el-tab-pane>

        <!-- Tab 4: 学情周报 -->
        <el-tab-pane label="学情周报" name="report">
          <div class="report-layout">
            <!-- 左侧：周报列表 -->
            <div class="report-sidebar">
              <div class="report-sidebar-header">
                <span>历史周报</span>
                <el-button type="primary" size="small" :loading="generatingReport" @click="generateReport">
                  {{ weeklyReport ? '重新生成' : '生成本周周报' }}
                </el-button>
              </div>
              <div v-if="reportList.length === 0 && !generatingReport" class="report-sidebar-empty">
                暂无历史周报
              </div>
              <div class="report-list">
                <div
                  v-for="item in reportList" :key="item.reportId"
                  class="report-item"
                  :class="{ active: selectedReportId === item.reportId }"
                  @click="selectReport(item)"
                >
                  <div class="report-item-week">第{{ getWeekNumber(item.weekStart) }}周</div>
                  <div class="report-item-date">{{ formatReportDate(item.weekStart) }} ~ {{ formatReportDate(item.weekEnd) }}</div>
                  <span v-if="item.isCurrentWeek" class="report-item-badge">本周</span>
                </div>
              </div>
            </div>

            <!-- 右侧：周报内容 -->
            <div class="report-main">
              <!-- 加载中：生成期间独占整块，旧周报不再显示，避免内容被顶下去 -->
              <div v-if="generatingReport" class="report-loading">
                <el-icon class="is-loading" :size="36"><Loading /></el-icon>
                <p>AI 正在分析本周学习数据，请稍候...</p>
              </div>

              <!-- 空状态（本周无周报、未生成、且没有选中往期周报） -->
              <div v-else-if="!weeklyReport && !selectedReportContent" class="report-empty">
                <div class="report-empty-icon">📊</div>
                <h2>学情周报</h2>
                <p>AI 将基于你本周的学习对话数据，自动生成一份包含学习概况、薄弱项关注和下周建议的周报</p>
                <el-button type="primary" size="large" :loading="generatingReport" @click="generateReport">生成本周周报</el-button>
              </div>

              <!-- 已有周报 -->
              <template v-else-if="selectedReportContent">
                <div class="report-header">
                  <div class="report-header-left">
                    <h2 style="margin:0">📊 学情周报</h2>
                    <span v-if="isCurrentWeekSelected" class="report-badge">本周</span>
                    <span v-else class="report-badge history">往周</span>
                  </div>
                </div>
                <el-card class="report-card">
                  <div class="report-content" v-html="selectedReportHtml"></div>
                </el-card>
              </template>
            </div>
          </div>
        </el-tab-pane>

        <!-- Tab 5: 资料库 -->
        <el-tab-pane label="资料库" name="materials">
          <el-card class="material-card">
            <div class="card-head">
              <h2 class="card-title">我的资料库</h2>
              <span v-if="materials.length > 0" class="tag-count">{{ materials.length }} 份资料</span>
            </div>
            <p class="card-desc">把单词表、笔记等文本资料永久存进资料库，随时点开查看/下载；在对话页点「选择资料」即可加入某个会话供 AI 参考。</p>
            <div class="material-upload">
              <el-upload :show-file-list="false" :before-upload="handleMaterialUpload"
                         accept=".txt,.md,.markdown,.csv" :disabled="materialUploading">
                <el-button type="primary" :loading="materialUploading">上传资料</el-button>
              </el-upload>
              <span class="material-tip">支持 .txt / .md / .csv，单个不超过 2MB</span>
            </div>
            <el-table :data="materials" v-loading="materialLoading" style="width:100%;margin-top:16px"
                      empty-text="暂无资料，上传你的第一个词表或笔记吧">
              <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
              <el-table-column label="大小" width="90">
                <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
              </el-table-column>
              <el-table-column label="字数" width="80">
                <template #default="{ row }">{{ row.chars ?? 0 }}</template>
              </el-table-column>
              <el-table-column prop="createTime" label="上传时间" width="170" />
              <el-table-column label="操作" width="250" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="openMaterialPreview(row)">查看</el-button>
                  <el-button size="small" text type="warning" @click="openFloating(row)">小窗</el-button>
                  <el-button size="small" text @click="downloadMaterial(row)">下载</el-button>
                  <el-popconfirm title="确定删除该资料吗？" @confirm="deleteMaterial(row)">
                    <template #reference>
                      <el-button size="small" text type="danger">删除</el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <!-- 资料预览弹窗：内容独立于悬浮小窗，可同时对比两份不同资料；modal-penetrable 让弹窗开着时仍可操作下层页面（点表格行开小窗） -->
          <el-dialog v-model="materialPreviewVisible" :title="materialState.dialog.fileName" width="860px" top="6vh" destroy-on-close :modal="false" modal-penetrable>
            <div class="material-preview" v-loading="materialState.dialog.loading">
              <div v-if="materialState.dialog.html" class="md-content" v-html="materialState.dialog.html"></div>
              <pre v-else class="material-preview-text">{{ materialState.dialog.content }}</pre>
            </div>
            <template #footer>
              <el-button size="small" @click="switchToFloating">转为小窗</el-button>
            </template>
          </el-dialog>
        </el-tab-pane>

        <!-- Tab 6: 个人设置 -->
        <el-tab-pane label="个人设置" name="settings">
          <div class="settings-layout">
            <el-card>
              <div class="section-header">基本信息</div>
              <p class="section-desc">设置你在系统中的显示昵称和头像</p>
              <div class="settings-row">
                <div class="welcome-avatar big">{{ avatarEmoji || usernameChar }}</div>
                <div class="avatar-picker">
                  <div class="picker-label">选择头像</div>
                  <div class="avatar-options">
                    <button v-for="e in avatarOptions" :key="e" class="avatar-option"
                            :class="{ active: avatarEmoji === e }" @click="pickAvatar(e)">{{ e }}</button>
                  </div>
                  <el-button size="small" text @click="pickAvatar('')">恢复默认字母头像</el-button>
                </div>
              </div>
              <div class="settings-row">
                <span class="settings-label">显示昵称</span>
                <el-input v-model="settingsForm.name" placeholder="请输入昵称" maxlength="50"
                          style="max-width: 320px" @keyup.enter="saveName" />
                <el-button type="primary" :loading="savingName" @click="saveName">保存昵称</el-button>
              </div>
              <p class="hint">昵称会显示在档案页欢迎信息中，AI 也会用昵称称呼你</p>
            </el-card>

            <el-card>
              <div class="section-header">界面设置</div>
              <p class="section-desc">调整聊天消息、资料、周报等正文区域的显示字号</p>
              <div class="settings-row">
                <span class="settings-label">字体大小</span>
                <el-slider v-model="fontSize" :min="12" :max="20" :show-tooltip="false"
                           style="max-width: 320px; flex: 1" @input="onFontSizeChange" />
                <span class="font-size-value">{{ fontSize }}px</span>
                <el-button size="small" text @click="resetFontSize">恢复默认</el-button>
              </div>
            </el-card>

            <el-card>
              <div class="section-header">修改密码</div>
              <p class="section-desc">定期修改密码可以保护账号安全</p>
              <el-form :model="pwdForm" label-width="90px" label-position="left" style="max-width: 420px">
                <el-form-item label="原密码" required>
                  <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
                </el-form-item>
                <el-form-item label="新密码" required>
                  <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少6位" />
                </el-form-item>
                <el-form-item label="确认新密码" required>
                  <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="savingPwd" @click="savePassword">修改密码</el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ProfilePage' })
import { reactive, ref, computed, onMounted, onActivated, onUnmounted, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import request from '@/api/request'
import { marked } from '@/utils/markdown'
import { getFontSize, applyFontSize } from '@/utils/fontSize'
import { isGenerating, reportGenerating } from '@/composables/useGenerationStatus'
import { useMaterialFloating } from '@/composables/useMaterialFloating'
// echarts 按需引入，减小打包体积
import * as echarts from 'echarts/core'
import { PieChart, RadarChart, LineChart, BarChart, GaugeChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent, GridComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  PieChart, RadarChart, LineChart, BarChart, GaugeChart,
  TooltipComponent, LegendComponent, GridComponent, MarkLineComponent,
  CanvasRenderer
])

const router = useRouter()
const route = useRoute()
const saving = ref(false)
const extracting = ref(false)
const clearing = ref(false)
const generatingReport = reportGenerating // 全局状态：跳页后仍在其他页面顶部显示生成横幅
const weeklyReport = ref('')
const reportList = ref([])
const selectedReportId = ref(null)
const selectedReportContent = ref('')
const lastProfileToken = ref('')
const isCurrentWeekSelected = computed(() => {
  if (!selectedReportId.value) return false
  const item = reportList.value.find(r => r.reportId === selectedReportId.value)
  return item ? item.isCurrentWeek : false
})
const selectedReportHtml = computed(() => {
  if (!selectedReportContent.value) return ''
  try { return marked.parse(selectedReportContent.value) || '' } catch { return selectedReportContent.value.replace(/\n/g, '<br>') }
})
const reportHtml = computed(() => {
  if (!weeklyReport.value) return ''
  try { return marked.parse(weeklyReport.value) || '' } catch { return weeklyReport.value.replace(/\n/g, '<br>') }
})
const tags = ref([])
const activeTab = ref('profile')
const statsLoading = ref(false)
// 各图表是否有数据（控制空态提示）
const pieEmpty = ref(false)
const radarEmpty = ref(false)
const trendEmpty = ref(false)
const growthEmpty = ref(false)

// 欢迎信息
const username = ref(localStorage.getItem('username') || '同学')
const usernameChar = computed(() => (username.value || '同').charAt(0).toUpperCase())
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const todayDisplay = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 周${week}`
})

// 从后端获取真实用户名（优先姓名，回退用户名，再回退本地缓存）
const loadUserInfo = async () => {
  try {
    const data = await request.get('/user/info')
    if (data) {
      const name = data.name || data.username || localStorage.getItem('username') || '同学'
      username.value = name
      accountName.value = data.username || ''
      settingsForm.name = data.name || data.username || ''
      localStorage.setItem('username', name)
      loadAvatar()
    }
  } catch {
    // 接口失败沿用本地缓存
  }
}

// ==================== 个人设置 ====================
const settingsForm = reactive({ name: '' })
const savingName = ref(false)
// 界面字号（跟随全局设置，立即生效并持久化）
const fontSize = ref(getFontSize())
const onFontSizeChange = (v) => { applyFontSize(v) }
const resetFontSize = () => {
  fontSize.value = 15
  applyFontSize(15)
}
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const savingPwd = ref(false)
// 头像按账号隔离存储，避免切换账号时串头像
const accountName = ref('')
const avatarEmoji = ref('')
const avatarOptions = ['🐱', '🐶', '🐼', '🦊', '🐸', '🐯', '🦁', '🐨', '🐰', '🐹']
const avatarStorageKey = () => 'avatar:' + (accountName.value || username.value || 'guest')
const loadAvatar = () => { avatarEmoji.value = localStorage.getItem(avatarStorageKey()) || '' }
const pickAvatar = (e) => {
  avatarEmoji.value = e
  const key = avatarStorageKey()
  if (e) localStorage.setItem(key, e)
  else localStorage.removeItem(key)
}
loadAvatar()

const saveName = async () => {
  const name = settingsForm.name.trim()
  if (!name) { ElMessage.warning('请输入昵称'); return }
  savingName.value = true
  try {
    await request.put('/user/info', { name })
    username.value = name
    localStorage.setItem('username', name)
    ElMessage.success('昵称已更新')
  } catch {
    // 错误已在拦截器中处理
  } finally {
    savingName.value = false
  }
}

const savePassword = async () => {
  if (!pwdForm.oldPassword) { ElMessage.warning('请输入原密码'); return }
  if (!pwdForm.newPassword || pwdForm.newPassword.length < 6) { ElMessage.warning('新密码至少6位'); return }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) { ElMessage.warning('两次输入的新密码不一致'); return }
  savingPwd.value = true
  try {
    await request.put('/user/password', { oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    ElMessage.success('密码修改成功')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
  } catch {
    // 错误已在拦截器中处理
  } finally {
    savingPwd.value = false
  }
}

// 图表 DOM 引用
const pieChartRef = ref(null)
const radarChartRef = ref(null)
const trendChartRef = ref(null)
const growthChartRef = ref(null)
const gaugeChartRef = ref(null)
const completionChartRef = ref(null)

// 图表实例缓存
let pieChart = null
let radarChart = null
let trendChart = null
let growthChart = null
let gaugeChart = null
let completionChart = null

// 学习进度数据与空态
const progressOverview = ref({})
const progressEmpty = ref(false)
const completionEmpty = ref(false)

const form = reactive({
  weakSubjects: '',
  examPlans: '',
  studyGoals: ''
})

const errors = reactive({
  weakSubjects: '',
  examPlans: '',
  studyGoals: ''
})

onMounted(async () => {
  // 支持「去填写」跳转定位：?tab=profile 时直接停在学业档案表单并滚动过去
  const tab = route.query.tab
  if (tab) activeTab.value = tab
  loadUserInfo()
  try {
    const data = await request.get('/profile')
    if (data) {
      form.weakSubjects = data.weakSubjects || ''
      form.examPlans = data.examPlans || ''
      form.studyGoals = data.studyGoals || ''
    }
  } catch {
    // 加载失败忽略
  }
  loadTags()
  loadMaterials()
  if (tab === 'profile') {
    nextTick(() => document.querySelector('.profile-layout')?.scrollIntoView({ behavior: 'smooth' }))
  }
})

// keep-alive 缓存的组件重新激活时，刷新当前 tab 数据（防止切换用户后残留旧数据）
onActivated(async () => {
  // 重新加载用户名与档案表单（防止切换用户后显示旧用户数据）
  loadUserInfo()
  try {
    const data = await request.get('/profile')
    if (data) {
      form.weakSubjects = data.weakSubjects || ''
      form.examPlans = data.examPlans || ''
      form.studyGoals = data.studyGoals || ''
    }
  } catch {
    // 加载失败忽略
  }
  loadTags()
  disposeCharts()
  onTabChange(activeTab.value)
})

const onTabChange = (name) => {
  if (name === 'statistics') {
    // 切回来时 DOM 是新的，需重建图表实例
    disposeCharts()
    loadStatistics()
  } else if (name === 'report') {
    disposeCharts()
    loadReport()
  } else if (name === 'materials') {
    disposeCharts()
    loadMaterials()
  } else {
    disposeCharts()
  }
}

const disposeCharts = () => {
  if (pieChart) { pieChart.dispose(); pieChart = null }
  if (radarChart) { radarChart.dispose(); radarChart = null }
  if (trendChart) { trendChart.dispose(); trendChart = null }
  if (growthChart) { growthChart.dispose(); growthChart = null }
  if (gaugeChart) { gaugeChart.dispose(); gaugeChart = null }
  if (completionChart) { completionChart.dispose(); completionChart = null }
}

watch(() => route.path, (path) => {
  if (path === '/profile') loadTags()
})

// ============ 资料库 ============
const materials = ref([])
const materialLoading = ref(false)
const materialUploading = ref(false)
const materialPreviewVisible = ref(false)
// 资料查看数据源：dialog（弹窗）与 floating（全局悬浮小窗）互相独立，可同时对比两份资料
const { state: materialState, loadMaterial, openFloating, copyDialogToFloating } = useMaterialFloating()

const loadMaterials = async () => {
  materialLoading.value = true
  try {
    materials.value = await request.get('/materials')
  } catch {
    materials.value = []
  } finally {
    materialLoading.value = false
  }
}

const formatSize = (bytes) => {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

const handleMaterialUpload = async (file) => {
  const ext = (file.name || '').split('.').pop()?.toLowerCase() || ''
  if (!['txt', 'md', 'markdown', 'csv'].includes(ext)) {
    ElMessage.warning('仅支持 .txt / .md / .csv 文本文件')
    return false
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 2MB')
    return false
  }
  materialUploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const res = await request.post('/materials', form)
    ElMessage.success(`已上传「${res.fileName}」，已存入资料库，可在对话页选择参考`)
    await loadMaterials()
  } catch {
    // 拦截器已统一提示
  } finally {
    materialUploading.value = false
  }
  return false
}

const openMaterialPreview = (m) => {
  materialPreviewVisible.value = true
  // 写入弹窗独立数据槽，不影响悬浮小窗正在显示的内容
  loadMaterial('dialog', m)
}

/** 弹窗转为小窗：把弹窗当前文件复制到悬浮小窗（不重复请求），并打开小窗 */
const switchToFloating = () => {
  materialPreviewVisible.value = false
  copyDialogToFloating()
}

const downloadMaterial = async (m) => {
  try {
    const res = await request.get(`/materials/${m.materialId}`)
    const blob = new Blob([res.content || ''], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = m.fileName
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('下载失败')
  }
}

const deleteMaterial = async (m) => {
  try {
    await request.delete(`/materials/${m.materialId}`)
    materials.value = materials.value.filter(x => x.materialId !== m.materialId)
    ElMessage.success(`已删除「${m.fileName}」`)
  } catch {}
}

const loadTags = async () => {
  try {
    const data = await request.get('/profile/tags')
    tags.value = data || []
  } catch {
    // 加载失败忽略
  }
}

const validate = () => {
  errors.weakSubjects = ''
  errors.examPlans = ''
  errors.studyGoals = ''
  let ok = true
  if (form.weakSubjects && form.weakSubjects.length > 500) {
    errors.weakSubjects = '不能超过500字符'
    ok = false
  }
  if (form.examPlans && form.examPlans.length > 500) {
    errors.examPlans = '不能超过500字符'
    ok = false
  }
  if (form.studyGoals && form.studyGoals.length > 500) {
    errors.studyGoals = '不能超过500字符'
    ok = false
  }
  return ok
}

const handleSave = async () => {
  if (!validate()) return
  saving.value = true
  try {
    await request.put('/profile', {
      weakSubjects: form.weakSubjects,
      examPlans: form.examPlans,
      studyGoals: form.studyGoals
    })
    ElMessage.success('档案保存成功')
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '保存失败')
  } finally {
    saving.value = false
  }
}

const handleExtract = async () => {
  extracting.value = true
  try {
    await request.post('/memory/extract')
    ElMessage.success('提取已开始，稍后自动刷新标签')
    // 等 4 秒后自动刷新标签（异步提取已完成）
    setTimeout(() => {
      loadTags()
      extracting.value = false
    }, 4000)
  } catch {
    ElMessage.error('提取请求失败')
    extracting.value = false
  }
}

const handleClear = async () => {
  clearing.value = true
  try {
    await request.delete('/memory/clear')
    ElMessage.success('动态记忆已清除')
    tags.value = []
  } catch {
    ElMessage.error('清除失败')
  } finally {
    clearing.value = false
  }
}

const handleLogout = async () => {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
    await request.post('/user/logout')
    localStorage.removeItem('token')
    ElMessage.success('已退出')
    router.push('/login')
  } catch {
    // 取消
  }
}

// ============ 学情周报 ============
// 词云样式：按权重生成大小/颜色深浅/透明度，轻微旋转错落（清新蓝系）
const cloudStyle = (tag, i) => {
  const size = 14 + tag.weight * 2.5  // 14~26px
  const opacity = 0.5 + tag.weight * 0.1  // 0.5~1.0
  // 清新蓝系：权重越大颜色越深
  const r = 115 + (5 - tag.weight) * 18
  const g = 170 + (5 - tag.weight) * 4
  const b = 235 + (5 - tag.weight) * 4
  // 以标签名为种子生成稳定的伪随机数，方向/角度打散，避免出现规律排列
  let h = 0
  const seedStr = tag.name + i
  for (let c = 0; c < seedStr.length; c++) {
    h = (h * 31 + seedStr.charCodeAt(c)) & 0x7fffffff
  }
  const rnd = () => ((h = (h * 1103515245 + 12345) & 0x7fffffff) % 1000) / 1000
  const rotate = Math.round((rnd() * 16 - 8))  // -8° ~ 8°
  const mx = Math.round(rnd() * 8 - 4)
  const my = Math.round(rnd() * 10 - 5)
  return {
    fontSize: size + 'px',
    color: `rgb(${r},${g},${b})`,
    opacity,
    transform: `rotate(${rotate}deg)`,
    margin: `${my}px ${mx}px`,
    zIndex: tag.weight
  }
}

const loadReport = async () => {
  // 检测用户切换，清空旧用户的周报数据
  const currentToken = localStorage.getItem('token') || ''
  if (currentToken !== lastProfileToken.value) {
    weeklyReport.value = ''
    reportList.value = []
    selectedReportId.value = null
    selectedReportContent.value = ''
    lastProfileToken.value = currentToken
  }
  try {
    // 加载周报列表
    const list = await request.get('/report/weekly/list')
    reportList.value = list || []
    // 自动选中本周周报（有则显示，无则选中最近一份）
    if (reportList.value.length > 0) {
      const current = reportList.value.find(r => r.isCurrentWeek)
      if (current) {
        await loadReportContent(current.reportId)
      } else {
        // 没有本周的，显示空白（让用户看到生成按钮）
        selectedReportId.value = null
        selectedReportContent.value = ''
      }
    }
    // 回退：也加载本周周报（兼容旧接口判断 weeklyReport 用于按钮文案）
    const data = await request.get('/report/weekly')
    weeklyReport.value = data.content || ''
  } catch {
    // 无周报或加载失败不报错
  }
}

const loadReportContent = async (reportId) => {
  try {
    const data = await request.get(`/report/weekly/${reportId}`)
    selectedReportId.value = reportId
    selectedReportContent.value = data.content || ''
  } catch {
    ElMessage.error('加载周报失败')
  }
}

const selectReport = (item) => {
  loadReportContent(item.reportId)
}

const generateReport = async () => {
  generatingReport.value = true
  isGenerating.value = true
  try {
    await request.post('/report/weekly')
    ElMessage.success('周报已生成并保存')
    // 重新加载列表和内容
    await loadReport()
  } catch {
    ElMessage.error('生成周报失败，请稍后重试')
  } finally {
    generatingReport.value = false
    isGenerating.value = false
  }
}

// 格式：2026-07-28 → 7/28
const formatReportDate = (dateStr) => {
  if (!dateStr) return ''
  const parts = dateStr.split('-')
  if (parts.length < 3) return dateStr
  return parseInt(parts[1]) + '/' + parseInt(parts[2])
}

// 计算 ISO 周次
const getWeekNumber = (dateStr) => {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const jan1 = new Date(d.getFullYear(), 0, 1)
  const diff = d - jan1 + (jan1.getTimezoneOffset() - d.getTimezoneOffset()) * 60000
  return Math.ceil(((diff / 86400000) + jan1.getDay() + 1) / 7)
}

// ==================== 学习数据看板 ====================

const loadStatistics = async () => {
  statsLoading.value = true
  try {
    const data = await request.get('/statistics/overview')
    // 判断各图表是否有真实数据，空则显示空态提示
    pieEmpty.value = isPiePlaceholder(data.subjectDistribution)
    radarEmpty.value = isRadarPlaceholder(data.weaknessRadar)
    trendEmpty.value = !data.activityTrend || data.activityTrend.length === 0
    growthEmpty.value = !data.memoryGrowth || data.memoryGrowth.length === 0
    // 学习进度：总任务数为 0 视为无数据
    progressOverview.value = data.progressOverview || {}
    progressEmpty.value = !((progressOverview.value.total ?? 0) > 0)
    completionEmpty.value = !data.completionTrend || data.completionTrend.every(d => (d.count ?? 0) === 0)
    // 等 DOM 渲染完毕 + 过渡动画完成，确保图表容器有正确尺寸
    await nextTick()
    const chartRefs = [completionChartRef, pieChartRef, radarChartRef, trendChartRef, growthChartRef]
    if (!progressEmpty.value) chartRefs.push(gaugeChartRef) // 进度空态时 gauge 容器不渲染，无需等待
    await waitForDomReady(chartRefs)
    renderProgressChart(progressOverview.value)
    renderCompletionChart(data.completionTrend)
    renderPieChart(data.subjectDistribution)
    renderRadarChart(data.weaknessRadar)
    renderTrendChart(data.activityTrend)
    renderGrowthChart(data.memoryGrowth)
  } catch {
    ElMessage.error('加载学习数据失败')
  } finally {
    statsLoading.value = false
  }
}

// 饼图/雷达图空态判定：后端返回 [{name:'暂无数据'}] 占位
const isPiePlaceholder = (list) => !list || (list.length === 1 && list[0].name === '暂无数据')
const isRadarPlaceholder = (list) => !list || (list.length === 1 && list[0].name && list[0].name.includes('暂无'))

// 等待 DOM 容器获得实际尺寸（el-tabs 切换后延迟渲染）
const waitForDomReady = (refs, timeoutMs = 2000) => {
  return new Promise((resolve) => {
    const start = Date.now()
    const check = () => {
      const allReady = refs.every(r => {
        const el = r.value
        return el && el.offsetWidth > 0 && el.offsetHeight > 0
      })
      if (allReady) {
        resolve()
      } else if (Date.now() - start > timeoutMs) {
        // 超时也继续，避免永久卡住
        resolve()
      } else {
        requestAnimationFrame(check)
      }
    }
    requestAnimationFrame(check)
  })
}

const renderProgressChart = (overview) => {
  if (!gaugeChartRef.value) return
  if (!gaugeChart) gaugeChart = echarts.init(gaugeChartRef.value)
  gaugeChart.resize()
  const rate = overview.completionRate ?? 0
  gaugeChart.setOption({
    series: [{
      type: 'gauge',
      startAngle: 90,
      endAngle: -270,
      min: 0,
      max: 100,
      // echarts gauge 的 radius 百分比基准是 min(宽,高)/2；整圆环外边界需满足 cy+r<=h
      // 容器高 240：88%*120=105.6px，cy=52%*240=124.8，底部 230.4 不超出
      radius: '88%',
      center: ['50%', '52%'],
      progress: { show: true, width: 18, itemStyle: { color: rate >= 80 ? '#67C23A' : rate >= 40 ? '#409EFF' : '#E6A23C' } },
      axisLine: { lineStyle: { width: 18, color: [[1, '#ebeef5']] } },
      axisTick: { show: false },
      splitLine: { show: false },
      axisLabel: { show: false },
      pointer: { show: false },
      anchor: { show: false },
      title: { show: true, offsetCenter: [0, '40%'], fontSize: 14, color: '#606266' },
      detail: {
        valueAnimation: true,
        offsetCenter: [0, '0%'],
        // rich 必须和 formatter 同级（detail 下），放到 series 级会解析失败导致原样输出 {rate|75}%
        formatter: (v) => `{rate|${Math.round(v)}}%`,
        rich: { rate: { fontSize: 36, fontWeight: 'bold', color: '#303133' } }
      },
      data: [{ value: rate, name: '总完成率' }]
    }]
  })
}

const renderCompletionChart = (data) => {
  if (!completionChartRef.value) return
  if (!completionChart) completionChart = echarts.init(completionChartRef.value)
  completionChart.resize()
  // 全部为 0 时显示空态，不渲染图表
  if (!data || data.every(d => (d.count ?? 0) === 0)) {
    completionChart.setOption({}, true)
    return
  }
  const dates = (data || []).map(d => {
    const [m, day] = (d.date || '').split('-').slice(1)
    return m ? `${Number(m)}/${Number(day)}` : d.date
  })
  const counts = (data || []).map(d => d.count ?? 0)
  completionChart.setOption({
    tooltip: { trigger: 'axis', formatter: (params) => `${params[0].axisValue}<br/>完成任务：${params[0].value} 个` },
    grid: { left: 40, right: 16, top: 24, bottom: 30 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', name: '完成任务数', minInterval: 1, min: 0 },
    series: [{
      type: 'bar',
      data: counts,
      barMaxWidth: 28,
      itemStyle: { color: '#409EFF', borderRadius: [4, 4, 0, 0] },
      markLine: {
        data: [{ type: 'average', name: '均值' }],
        silent: true,
        lineStyle: { type: 'dashed', color: '#909399' }
      }
    }]
  })
}

const renderPieChart = (data) => {
  if (!pieChartRef.value) return
  if (!pieChart) pieChart = echarts.init(pieChartRef.value)
  pieChart.resize()
  // 空数据：显示空状态，不渲染假图表
  if (data.length === 1 && data[0].name === '暂无数据') {
    pieChart.setOption({}, true)
    return
  }
  const names = data.map(d => d.name)
  const values = data.map(d => d.value)
  pieChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 条 ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}\n{d}%' },
      data: names.map((n, i) => ({ name: n, value: values[i] }))
    }]
  })
}

const renderRadarChart = (data) => {
  if (!radarChartRef.value) return
  if (!radarChart) radarChart = echarts.init(radarChartRef.value)
  radarChart.resize()
  // 空数据：显示空状态
  if (data.length === 1 && data[0].name && data[0].name.includes('暂无')) {
    radarChart.setOption({}, true)
    return
  }
  const names = data.map(d => d.name)
  const values = data.map(d => d.value)
  const maxVal = Math.max(...values, 1)
  radarChart.setOption({
    tooltip: { trigger: 'item' },
    radar: {
      center: ['50%', '50%'],
      radius: '65%',
      indicator: names.map(n => ({ name: n, max: Math.max(maxVal * 1.2, 5) }))
    },
    series: [{
      type: 'radar',
      data: [{ value: values, name: '薄弱程度', areaStyle: { color: 'rgba(245,108,108,0.3)' } }],
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { color: '#E6A23C', width: 2 },
      itemStyle: { color: '#F56C6C' }
    }]
  })
}

const renderTrendChart = (data) => {
  if (!trendChartRef.value) return
  if (!trendChart) trendChart = echarts.init(trendChartRef.value)
  trendChart.resize()
  const dates = data.map(d => d.date)
  const counts = data.map(d => d.count)
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', name: '消息数', minInterval: 1, min: 0 },
    series: [{
      type: 'line',
      data: counts,
      smooth: true,
      areaStyle: { color: 'rgba(64,158,255,0.15)' },
      itemStyle: { color: '#409EFF' },
      markLine: {
        data: [{ type: 'average', name: '均值' }],
        silent: true,
        lineStyle: { type: 'dashed', color: '#909399' }
      }
    }]
  })
}

const renderGrowthChart = (data) => {
  if (!growthChartRef.value) return
  if (!growthChart) growthChart = echarts.init(growthChartRef.value)
  growthChart.resize()
  const dates = data.map(d => d.date)
  const counts = data.map(d => d.count)
  growthChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30 } },
    yAxis: { type: 'value', name: '新增条数', minInterval: 1, min: 0 },
    series: [{
      type: 'line',
      data: counts,
      smooth: true,
      areaStyle: { color: 'rgba(103,194,58,0.15)' },
      itemStyle: { color: '#67C23A' },
      markLine: {
        data: [{ type: 'average', name: '均值' }],
        silent: true,
        lineStyle: { type: 'dashed', color: '#909399' }
      }
    }]
  })
}

// 窗口 resize 时重绘图表
const handleResize = () => {
  pieChart?.resize()
  radarChart?.resize()
  trendChart?.resize()
  growthChart?.resize()
  gaugeChart?.resize()
  completionChart?.resize()
}
window.addEventListener('resize', handleResize)
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  pieChart?.dispose()
  radarChart?.dispose()
  trendChart?.dispose()
  growthChart?.dispose()
})
</script>

<style scoped>
/* 页面：浅天空蓝底 + 角落低饱和装饰色块（纯色无渐变） */
.page-container { position: relative; min-height: 100vh; background: #f4f9ff; overflow: hidden; }
.page-container::before,
.page-container::after {
  content: ''; position: fixed; border-radius: 50%; pointer-events: none; z-index: 0;
}
.page-container::before { width: 380px; height: 380px; background: #dcebff; top: -150px; right: -120px; }
.page-container::after { width: 300px; height: 300px; background: #ddf2ea; bottom: -110px; left: -110px; }
/* top-bar 样式统一由 src/styles/global.css 全局提供 */
.page-content { position: relative; z-index: 1; padding: 24px; padding-bottom: 64px; }
h2 { margin-bottom: 24px; }

/* 欢迎信息 */
.welcome-row { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.welcome-avatar {
  width: 44px; height: 44px; border-radius: 50%;
  background: #ecf5ff; color: #409eff;
  display: flex; align-items: center; justify-content: center;
  font-size: 20px; font-weight: 600; flex-shrink: 0;
}
.welcome-title { font-size: 17px; font-weight: 600; color: #303133; }
.welcome-date { font-size: 13px; color: #909399; margin-top: 3px; }

/* 个人设置 */
.settings-layout { display: flex; flex-direction: column; gap: 20px; max-width: 720px; margin: 0 auto; }
.settings-row { display: flex; align-items: center; gap: 14px; margin: 14px 0; flex-wrap: wrap; }
.settings-label { width: 70px; color: #606266; font-size: 14px; flex-shrink: 0; }
.font-size-value { font-size: 13px; color: #606266; min-width: 36px; text-align: right; }
.welcome-avatar.big { width: 56px; height: 56px; font-size: 24px; }
.avatar-picker { display: flex; flex-direction: column; gap: 8px; }
.picker-label { font-size: 13px; color: #909399; }
.avatar-options { display: flex; flex-wrap: wrap; gap: 8px; }
.avatar-option {
  width: 40px; height: 40px; border-radius: 50%;
  border: 1px solid #e4e7ed; background: #fff; font-size: 20px;
  cursor: pointer; transition: all 0.15s; padding: 0;
}
.avatar-option:hover { border-color: #409eff; }
.avatar-option.active { border: 2px solid #409eff; background: #ecf5ff; }

/* 学业档案 */
.profile-layout { max-width: 640px; margin: 0 auto; }
.profile-section { margin-bottom: 16px; }
.profile-section .section-header { font-size: 16px; font-weight: 600; color: #303133; margin-bottom: 4px; }
.profile-section .section-desc { font-size: 13px; color: #909399; margin: 0 0 14px; }
.profile-save { margin-top: 8px; }

/* 学习画像 */
.portrait-card, .memory-card { border-radius: 12px; }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.card-title { font-size: 17px; font-weight: 600; color: #303133; margin: 0; }
.card-desc { font-size: 13px; color: #909399; margin: 4px 0 16px; }
.tag-count { font-size: 12px; color: #909399; background: #f5f7fa; border-radius: 20px; padding: 3px 12px; }
/* 资料库 */
.material-upload { display: flex; align-items: center; gap: 12px; margin-top: 4px; }
.material-tip { font-size: 12px; color: #c0c4cc; }
.material-preview { max-height: 68vh; overflow-y: auto; background: #fafbfc; border: 1px solid #ebeef5; border-radius: 8px; padding: 16px 20px 32px; }
.material-preview-text { white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.7; color: #303133; margin: 0; font-family: 'JetBrains Mono', Consolas, monospace; }
/* v-html 渲染的 markdown 内容无 scoped 属性，需用 :deep() 让样式生效 */
.material-preview :deep(.md-content) { font-size: var(--app-font-size, 14px); line-height: 1.8; color: #303133; }
.material-preview :deep(.md-content) h1, .material-preview :deep(.md-content) h2, .material-preview :deep(.md-content) h3 { margin: 16px 0 8px; font-weight: 600; }
.material-preview :deep(.md-content) h1 { font-size: 22px; } .material-preview :deep(.md-content) h2 { font-size: 19px; } .material-preview :deep(.md-content) h3 { font-size: 16px; }
.material-preview :deep(.md-content) p { margin: 6px 0; }
.material-preview :deep(.md-content) ul, .material-preview :deep(.md-content) ol { margin: 6px 0; padding-left: 22px; }
.material-preview :deep(.md-content) li { margin: 3px 0; }
.material-preview :deep(.md-content) code { background: #f0f2f5; border-radius: 4px; padding: 2px 5px; font-size: calc(var(--app-font-size, 14px) - 1.5px); font-family: 'JetBrains Mono', Consolas, monospace; }
/* 代码块用 hljs 深色主题（github-dark），行内 code 保持浅色 */
.material-preview :deep(.md-content) pre { background: #0d1117; border: 1px solid #30363d; border-radius: 8px; padding: 12px 14px; overflow-x: auto; }
.material-preview :deep(.md-content) pre code { background: transparent; color: #c9d1d9; padding: 0; }
.material-preview :deep(.md-content) blockquote { margin: 8px 0; padding: 4px 12px; border-left: 3px solid #409eff; background: #f0f7ff; color: #606266; border-radius: 0 6px 6px 0; }
.material-preview :deep(.md-content) table { border-collapse: collapse; margin: 10px 0; width: 100%; }
.material-preview :deep(.md-content) th, .material-preview :deep(.md-content) td { border: 1px solid #e4e7ed; padding: 6px 10px; font-size: calc(var(--app-font-size, 14px) - 1px); }
.material-preview :deep(.md-content) th { background: #f5f7fa; font-weight: 600; }
.material-preview :deep(.md-content) img { max-width: 100%; border-radius: 6px; }
.word-cloud { display: flex; flex-wrap: wrap; gap: 12px 20px; align-items: center; justify-content: center; padding: 24px 16px; min-height: 120px; line-height: 1.2; }
.cloud-tag { font-weight: 600; cursor: default; transition: all 0.25s; display: inline-block; position: relative; white-space: nowrap; }
.cloud-tag:hover { transform: scale(1.2) !important; color: #409eff !important; opacity: 1 !important; z-index: 99; }

.memory-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.memory-hint { margin-top: 14px; padding: 4px 0; }
.memory-hint p { margin: 2px 0; font-size: 12px; color: #909399; line-height: 1.7; }
.hint { font-size: 12px; color: #909399; line-height: 1.6; }

/* 数据看板 */
.charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.full-width-card { grid-column: 1 / -1; }
.chart-box-wrap { position: relative; }
.chart-box { width: 100%; height: 340px; }
.chart-box-wide { width: 100%; height: 300px; }
.charts-grid h3 { margin: 0 0 12px; font-size: 16px; color: #303133; }
/* 学习进度区块：进度环 + 数字卡 */
.progress-row { display: flex; align-items: center; gap: 24px; }
.progress-gauge { flex: 0 0 300px; }
.progress-gauge .chart-box { height: 240px; }
.progress-stats { flex: 1; display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
.stat-card { background: #f5f7fa; border-radius: 10px; padding: 18px 14px; text-align: center; transition: transform var(--app-transition, 0.2s ease), box-shadow var(--app-transition, 0.2s ease); }
.stat-card:hover { transform: translateY(-2px); box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08); }
.stat-num { font-size: 30px; font-weight: 700; line-height: 1.2; }
.stat-num.primary { color: #409EFF; }
.stat-num.success { color: #67C23A; }
.stat-num.warning { color: #E6A23C; }
.stat-num.danger { color: #F56C6C; }
.stat-label { font-size: 12px; color: #909399; margin-top: 6px; }
.progress-empty { position: relative; inset: auto; height: 180px; }
/* 图表空态 */
.chart-empty {
  position: absolute; inset: 0; z-index: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  background: #fbfdff; border-radius: 8px;
  color: #909399; font-size: 14px;
}
.chart-empty-icon { font-size: 28px; }
.chart-empty span { font-size: 12px; color: #c0c4cc; }

/* 学情周报 */
.report-layout { position: relative; display: flex; justify-content: center; max-width: 960px; margin: 0 auto; min-height: 400px; }
.report-sidebar { position: absolute; left: -210px; top: 0; width: 200px; }
.report-sidebar-header { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; font-size: 14px; font-weight: 600; color: #303133; }
.report-sidebar-empty { font-size: 13px; color: #909399; text-align: center; padding: 20px 0; }
.report-list { display: flex; flex-direction: column; gap: 6px; }
.report-item { padding: 10px 12px; border-radius: 8px; cursor: pointer; border: 1px solid #ebeef5; background: #fff; transition: border-color 0.2s ease, background-color 0.2s ease; position: relative; }
.report-item:hover { border-color: #409EFF; background: #f5f9ff; }
.report-item.active { border-color: #409EFF; background: #ecf5ff; }
.report-item-week { font-size: 14px; font-weight: 600; color: #303133; }
.report-item-date { font-size: 12px; color: #909399; margin-top: 2px; }
.report-item-badge { position: absolute; top: 8px; right: 8px; font-size: 10px; color: #fff; background: #409EFF; padding: 1px 6px; border-radius: 8px; }
.report-main { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: center; }
.report-empty { text-align: center; padding: 60px 20px; }
.report-empty-icon { font-size: 48px; margin-bottom: 16px; }
.report-empty h2 { margin-bottom: 12px; }
.report-empty p { font-size: 14px; color: #909399; max-width: 400px; margin: 0 auto 24px; line-height: 1.6; }
.report-loading { text-align: center; padding: 80px 20px; color: #909399; }
.report-loading p { margin-top: 16px; font-size: 14px; }
.report-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; width: 100%; }
.report-header-left { display: flex; align-items: center; gap: 10px; }
.report-badge { font-size: 12px; color: #409EFF; background: #ecf5ff; padding: 2px 10px; border-radius: 12px; }
.report-badge.history { color: #909399; background: #f5f7fa; }
.report-card { line-height: 1.9; width: 100%; max-width: 680px; }
.report-content { line-height: 1.8; font-size: var(--app-font-size, 15px); color: #303133; }
.report-content :deep(h1) { font-size: 20px; margin: 16px 0 8px; }
.report-content :deep(h2) { font-size: 18px; margin: 14px 0 6px; }
.report-content :deep(h3) { font-size: 16px; margin: 12px 0 4px; }
.report-content :deep(p) { margin: 6px 0; }
.report-content :deep(ul), .report-content :deep(ol) { padding-left: 20px; margin: 6px 0; }
.report-content :deep(li) { margin: 4px 0; }
.report-content :deep(strong) { color: #409EFF; }
.report-content :deep(code) { background: #f5f7fa; padding: 2px 6px; border-radius: 4px; font-size: calc(var(--app-font-size, 15px) - 2px); }
/* 周报代码块用 hljs 深色主题，行内 code 保持浅色 */
.report-content :deep(pre) { background: #0d1117; border-radius: 6px; padding: 12px 14px; overflow-x: auto; }
.report-content :deep(pre code) { background: transparent; color: #c9d1d9; padding: 0; }
.report-content :deep(blockquote) { border-left: 3px solid #409EFF; padding-left: 12px; color: #606266; margin: 8px 0; }

/* 窄屏适配 */
@media (max-width: 1280px) {
  /* 侧栏不再溢出，改为堆叠在顶部 */
  .report-layout { max-width: 680px; flex-direction: column; }
  .report-sidebar { position: static; width: 100%; }
}
@media (max-width: 900px) {
  .charts-grid { grid-template-columns: 1fr; }
  .progress-row { flex-direction: column; align-items: stretch; }
  .progress-gauge { flex: none; }
  .page-content { padding: 16px; }
  .top-bar { padding: 0 12px; }
  .nav-links { gap: 10px; }
  .nav-links a { font-size: 14px; }
}
@media (max-width: 640px) {
  .welcome-card { flex-direction: column; align-items: flex-start; gap: 10px; }
}
</style>
