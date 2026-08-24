<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { checkAuth, logout as apiLogout, type AuthenticatedUser } from './api/client'
import { currentUser, markAuthReady } from './auth'
import { Checked, DataAnalysis, Files, Odometer, SwitchButton, User } from '@element-plus/icons-vue'
import LoginView from './views/LoginView.vue'

const route = useRoute()
const router = useRouter()
const authReady = ref(false)

const loggedIn = computed(() => currentUser.value !== null)
const role = computed(() => currentUser.value?.role ?? '')

onMounted(async () => {
  try {
    currentUser.value = await checkAuth()
  } catch {
    currentUser.value = null
  } finally {
    authReady.value = true
    markAuthReady()
  }
  // 会话过期（任意接口返回 401）时平滑回到登录界面，避免整页跳转丢失上下文
  window.addEventListener('auth:expired', handleAuthExpired)
})

onBeforeUnmount(() => {
  window.removeEventListener('auth:expired', handleAuthExpired)
})

function handleAuthExpired() {
  currentUser.value = null
  router.replace('/cases')
}

function handleLoggedIn(user: AuthenticatedUser) {
  currentUser.value = user
  router.replace('/cases')
}

function openCase(id: number) {
  router.push(`/cases/${id}`)
}

function goCases() {
  router.push('/cases')
}

async function logout() {
  try {
    await apiLogout()
  } catch {
    /* 忽略登出接口异常，本地直接清态 */
  }
  currentUser.value = null
  router.push('/cases')
}

const isCases = () => route.path.startsWith('/cases')
const isReviews = () => route.path.startsWith('/reviews')
const isEval = () => route.path.startsWith('/eval')
const isCustomers = () => route.path.startsWith('/customers')

const roleLabel = computed(() => {
  const map: Record<string, string> = { ADMIN: '管理员', REVIEWER: '复核员', ANALYST: '分析员' }
  return map[role.value] ?? ''
})
</script>

<template>
  <div class="app-shell">
    <div v-if="!authReady" class="loading">加载中…</div>
    <LoginView v-else-if="!loggedIn" @logged-in="handleLoggedIn" />
    <template v-else>
      <header class="commandbar">
        <div class="brand">
          <div class="brand-mark">
            <el-icon :size="18"><Odometer /></el-icon>
          </div>
          <div>
            <h1>AML <em>尽调工作台</em></h1>
          </div>
        </div>

        <nav class="nav">
          <el-button
            :type="isCases() ? 'primary' : 'default'"
            size="small"
            aria-label="工单中心"
            :aria-current="isCases() ? 'page' : undefined"
            @click="goCases"
          >
            <el-icon><Files /></el-icon>
            <span>工单中心</span>
          </el-button>
          <el-button
            v-if="role === 'REVIEWER' || role === 'ADMIN'"
            :type="isReviews() ? 'primary' : 'default'"
            size="small"
            aria-label="人工复核"
            :aria-current="isReviews() ? 'page' : undefined"
            @click="router.push('/reviews')"
          >
            <el-icon><Checked /></el-icon>
            <span>人工复核</span>
          </el-button>
          <el-button
            v-if="role === 'ADMIN'"
            :type="isEval() ? 'primary' : 'default'"
            size="small"
            aria-label="评测中心"
            :aria-current="isEval() ? 'page' : undefined"
            @click="router.push('/eval')"
          >
            <el-icon><DataAnalysis /></el-icon>
            <span>评测中心</span>
          </el-button>
          <el-button
            v-if="role === 'ADMIN'"
            :type="isCustomers() ? 'primary' : 'default'"
            size="small"
            aria-label="人员管理"
            :aria-current="isCustomers() ? 'page' : undefined"
            @click="router.push('/customers')"
          >
            <el-icon><User /></el-icon>
            <span>人员管理</span>
          </el-button>
          <div class="sys-status">
            <i class="sys-dot"></i>
            <span>系统在线</span>
            <em class="role-chip">{{ roleLabel }}</em>
          </div>
          <el-button class="secondary-action" size="small" aria-label="退出当前账号" @click="logout">
            <el-icon><SwitchButton /></el-icon>
            <span>退出</span>
          </el-button>
        </nav>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" @open-case="openCase" @back="goCases" />
        </router-view>
      </main>
    </template>
  </div>
</template>

<style scoped>
.nav {
  display: flex;
  align-items: center;
  gap: 6px;
}

.sys-status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 4px 0 10px;
  padding-left: 12px;
  border-left: 1px solid var(--line);
  font-size: 12px;
  color: var(--text-dim);
}

.sys-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--risk-low);
  box-shadow: none;
}

.role-chip {
  font-style: normal;
  font-size: 11px;
  color: var(--text-faint);
  background: transparent;
  border: 0;
  padding: 0;
  letter-spacing: 0;
}

.loading {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-faint);
  font-size: 14px;
}

@media (max-width: 860px) {
  .role-chip,
  .sys-status {
    display: none;
  }
  .nav { overflow-x: auto; scrollbar-width: none; }
  .nav::-webkit-scrollbar { display: none; }
  .nav .el-button { flex: 0 0 auto; padding: 7px 9px; }
}
</style>
