<script setup lang="ts">
import { defineAsyncComponent, ref } from 'vue'
import { clearToken, getToken } from './api/client'

const CaseDashboard = defineAsyncComponent(() => import('./views/CaseDashboard.vue'))
const CaseDetailView = defineAsyncComponent(() => import('./views/CaseDetailView.vue'))
const ReviewView = defineAsyncComponent(() => import('./views/ReviewView.vue'))
const LoginView = defineAsyncComponent(() => import('./views/LoginView.vue'))

const loggedIn = ref(getToken() !== null)
const activeView = ref<'cases' | 'reviews'>('cases')
const activeCaseId = ref<number | null>(null)

function openCase(id: number) {
  activeCaseId.value = id
}

function logout() {
  clearToken()
  loggedIn.value = false
  activeView.value = 'cases'
  activeCaseId.value = null
}
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
          <el-button :type="activeView === 'cases' ? 'primary' : 'default'" size="small" @click="activeView = 'cases'; activeCaseId = null">
            工单中心
          </el-button>
          <el-button :type="activeView === 'reviews' ? 'primary' : 'default'" size="small" @click="activeView = 'reviews'; activeCaseId = null">
            人工复核
          </el-button>
          <el-button size="small" @click="logout">退出</el-button>
        </div>
      </header>
      <main class="content">
        <CaseDetailView v-if="activeCaseId !== null" :case-id="activeCaseId" @back="activeCaseId = null" />
        <ReviewView v-else-if="activeView === 'reviews'" @open-case="openCase" />
        <CaseDashboard v-else @open-case="openCase" />
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
