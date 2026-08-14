<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { checkAuth, logout as apiLogout, type AuthenticatedUser } from './api/client'
import { currentUser, markAuthReady } from './auth'
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
})

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
</script>

<template>
  <div class="app-shell">
    <div v-if="!authReady" class="loading">加载中…</div>
    <LoginView v-else-if="!loggedIn" @logged-in="handleLoggedIn" />
    <template v-else>
      <header class="topbar">
        <div class="brand">
          <span class="badge">AML</span>
          <div>
            <h1>智能反洗钱尽调 Agent</h1>
            <p>商业银行反洗钱（AML）· 高风险客户尽调 · 可靠任务 · 证据追溯 · 评测体系</p>
          </div>
        </div>
        <div class="nav">
          <el-button :type="isCases() ? 'primary' : 'default'" size="small" @click="goCases">
            工单中心
          </el-button>
          <el-button v-if="role === 'REVIEWER' || role === 'ADMIN'" :type="isReviews() ? 'primary' : 'default'" size="small" @click="router.push('/reviews')">
            人工复核
          </el-button>
          <el-button v-if="role === 'ADMIN'" :type="isEval() ? 'primary' : 'default'" size="small" @click="router.push('/eval')">
            评测中心
          </el-button>
          <el-button size="small" @click="logout">退出</el-button>
        </div>
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
  gap: 8px;
}
.loading {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 14px;
}
</style>
