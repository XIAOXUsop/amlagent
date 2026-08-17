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
  background:
    radial-gradient(900px 500px at 50% -10%, rgba(201, 169, 97, 0.10), transparent 60%),
    linear-gradient(180deg, #0d1526, #0b1220);
}

.login-card {
  width: 400px;
  max-width: 100%;
  background: linear-gradient(180deg, rgba(22, 33, 58, 0.96), rgba(17, 26, 44, 0.96));
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 34px 34px 26px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

.login-brand {
  text-align: center;
  margin-bottom: 26px;
}

.login-mark {
  width: 64px;
  height: 64px;
  margin: 0 auto 14px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: linear-gradient(145deg, #d6bc7d, #a98f49);
  box-shadow: 0 8px 24px rgba(201, 169, 97, 0.32);
  color: var(--text-inverse);
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
  color: var(--gold);
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
  border: 1px dashed var(--line);
  border-radius: 8px;
  background: rgba(11, 18, 32, 0.4);
  font-size: 12px;
  color: var(--text-dim);
  line-height: 2;
}

.hint-line {
  display: block;
  font-size: 11px;
  color: var(--gold);
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
