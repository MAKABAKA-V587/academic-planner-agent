<template>
  <div class="auth-container">
    <div class="auth-card">
      <div class="brand">
        <div class="brand-icon">🎓</div>
        <h1>学小伴</h1>
        <p class="brand-desc">学业规划智能Agent</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
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
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            @click="handleLogin"
            :loading="loading"
            class="auth-btn"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        还没有账号？<router-link to="/register">立即注册</router-link>
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
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await request.post('/user/login', form)
    localStorage.setItem('token', res.token)
    localStorage.setItem('username', res.username || form.username)
    ElMessage.success('登录成功')
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
