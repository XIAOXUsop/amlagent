<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../api/client'

const emit = defineEmits<{ (e: 'logged-in'): void }>()

const username = ref('')
const password = ref('')
const loading = ref(false)

async function doLogin() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const r = await login(username.value, password.value)
    ElMessage.success(`欢迎，${r.username}（${r.role}）`)
    emit('logged-in')
  } catch {
    ElMessage.error('登录失败，请检查用户名或密码')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-logo">AML</div>
      <h2>智能反洗钱尽调 Agent 平台</h2>
      <p class="login-sub">商业银行反洗钱 · 高风险客户尽调</p>
      <el-form @submit.prevent="doLogin">
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码" size="large" show-password @keyup.enter="doLogin" />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="doLogin">
          登 录
        </el-button>
      </el-form>
      <div class="login-hint">
        演示账号：admin / admin123（管理员）、reviewer / reviewer123（复核员）、analyst / analyst123（分析员）
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #16324a, #1f4e79);
}

.login-card {
  width: 380px;
  background: #fff;
  border-radius: 12px;
  padding: 36px 32px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.3);
  text-align: center;
}

.login-logo {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 12px;
  background: linear-gradient(135deg, #1f4e79, #409eff);
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-card h2 {
  margin: 0 0 4px;
  font-size: 18px;
  color: #303133;
}

.login-sub {
  margin: 0 0 24px;
  font-size: 12px;
  color: #909399;
}

.login-hint {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
  text-align: left;
}
</style>
