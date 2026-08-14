<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { checkAuth, logout as apiLogout } from './api/client'
import LoginView from './views/LoginView.vue'

const route = useRoute()
const router = useRouter()
const loggedIn = ref(false)
const role = ref('')

onMounted(async () => {
  try {
    const me = await checkAuth()
    role.value = me.role
    loggedIn.value = true
  } catch {
    loggedIn.value = false
  }
})

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
  loggedIn.value = false
  role.value = ''
  router.push('/cases')
}

const isCases = () => route.path.startsWith('/cases')
const isReviews = () => route.path.startsWith('/reviews')
const isEval = () => route.path.startsWith('/eval')
</script>

<template>
  <div class="app-shell">
    <LoginView v-if="!loggedIn" @logged-in="loggedIn = true" />
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
</style>
