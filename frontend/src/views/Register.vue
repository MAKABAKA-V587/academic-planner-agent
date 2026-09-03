<template>
  <div class="auth-container">
    <div class="auth-card">
      <div class="brand">
        <div class="brand-icon">🎓</div>
        <h1>创建账号</h1>
        <p class="brand-desc">开启你的智能学业规划之旅</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleRegister">
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            @click="handleRegister"
            :loading="loading"
            class="auth-btn"
          >
            注 册
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        已有账号？<router-link to="/login">去登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import request from '@/api/request'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

const validateConfirmPassword = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请确认密码'))
  } else if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 50, message: '用户名长度需在2-50之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 100, message: '密码长度需在6-100之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

const handleRegister = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await request.post('/user/register', {
      username: form.username,
      password: form.password
    })
    // 注册成功直接登录，免去再输一次账号密码
    const res = await request.post('/user/login', {
      username: form.username,
      password: form.password
    })
    localStorage.setItem('token', res.token)
    localStorage.setItem('username', res.username || form.username)
    ElMessage.success('注册成功，欢迎来到学小伴！')
    router.push('/chat')
  } catch {
    // 错误已在拦截器中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 浅天空蓝底 + 角落低饱和装饰色块（纯色无渐变），与主界面风格统一 */
.auth-container {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: #f4f9ff;
  overflow: hidden;
}
.auth-container::before,
.auth-container::after {
  content: ''; position: fixed; border-radius: 50%; pointer-events: none; z-index: 0;
}
.auth-container::before { width: 380px; height: 380px; background: #dcebff; top: -150px; right: -120px; }
.auth-container::after { width: 300px; height: 300px; background: #ddf2ea; bottom: -110px; left: -110px; }

.auth-card {
  position: relative;
  z-index: 1;
  width: 400px;
  padding: 48px 40px 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}

.brand {
  text-align: center;
  margin-bottom: 40px;
}
.brand-icon {
  font-size: 40px;
  line-height: 1;
  margin-bottom: 10px;
}
.brand h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  color: #333;
}
.brand-desc {
  margin: 0;
  font-size: 13px;
  color: #999;
}

.auth-btn {
  width: 100%;
  height: 42px;
  font-size: 15px;
  letter-spacing: 4px;
  border-radius: var(--app-radius-md, 8px);
}

.auth-footer {
  text-align: center;
  font-size: 13px;
  color: #999;
}
.auth-footer a {
  color: #409eff;
  text-decoration: none;
}
.auth-footer a:hover {
  opacity: 0.8;
}
</style>
