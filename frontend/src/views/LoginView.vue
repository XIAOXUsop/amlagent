<script setup lang="ts">
import { ref } from 'vue'
import { login, type AuthenticatedUser } from '../api/client'
import { Lock, Odometer, User } from '@element-plus/icons-vue'

const emit = defineEmits<{ (e: 'logged-in', user: AuthenticatedUser): void }>()

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
    emit('logged-in', r)
  } catch {
    ElMessage.error('登录失败，请检查用户名或密码')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-stage">
    <div class="login-card">
      <div class="login-brand">
        <div class="login-mark">
          <el-icon :size="30"><Odometer /></el-icon>
        </div>
        <h1>AML <em>尽调中心</em></h1>
        <p class="login-sub">商业银行反洗钱 · 高风险客户尽调 Agent</p>
      </div>

      <el-form @submit.prevent="doLogin">
        <el-form-item>
          <el-input
            v-model="username"
            placeholder="用户名"
            size="large"
            :prefix-icon="User"
            @input="username = username.replace(/\s+/g, '')"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            :prefix-icon="Lock"
            @input="password = password.replace(/\s+/g, '')"
            @keyup.enter="doLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          style="width: 100%"
          :loading="loading"
          @click="doLogin"
        >
          登 录
        </el-button>
      </el-form>

      <div class="login-hint">
        <span class="hint-line">演示账号</span>
        <code>admin / admin123</code> · <code>reviewer / reviewer123</code> · <code>analyst / analyst123</code>
      </div>

      <div class="login-foot">
        <i class="sys-dot"></i>
        认证 · CSRF 防护 · 审计链路在线
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-stage {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: #f8fafc;
}

.login-card {
  width: 400px;
  max-width: 100%;
  background: transparent;
  border: 0;
  border-radius: 0;
  padding: 28px;
  box-shadow: none;
}

.login-brand {
  text-align: center;
  margin-bottom: 26px;
}

.login-mark {
  width: 42px;
  height: 42px;
  margin: 0 auto 14px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  background: #eef2f6;
  box-shadow: none;
  color: var(--text-dim);
}

.login-card h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--text);
}

.login-card h1 em {
  font-style: normal;
  color: var(--text-faint);
}

.login-sub {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-faint);
  letter-spacing: 0.05em;
}

.login-hint {
  margin-top: 18px;
  padding: 12px 14px;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  background: transparent;
  font-size: 12px;
  color: var(--text-dim);
  line-height: 2;
}

.hint-line {
  display: block;
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.06em;
  margin-bottom: 2px;
}

.login-hint code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text);
}

.login-foot {
  margin-top: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.03em;
}

.sys-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--risk-low);
  box-shadow: 0 0 0 3px rgba(47, 163, 127, 0.16);
}
</style>
