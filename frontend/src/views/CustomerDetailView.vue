<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ChatDotRound } from '@element-plus/icons-vue'
import {
  getAdminCustomer,
  getAssistantStatus,
  fmtDateTime,
  type AssistantStatus,
  type CustomerAdminItem,
} from '../api/client'
import CustomerAssistantDrawer from '../components/assistant/CustomerAssistantDrawer.vue'

const props = defineProps<{ customerId: number }>()
const router = useRouter()
const customer = ref<CustomerAdminItem | null>(null)
const loading = ref(false)
const loadVersion = ref(0)
const assistantStatus = ref<AssistantStatus>({ enabled: false, maxMessageChars: 2000 })
const assistantVisible = ref(false)
let active = true

onMounted(() => {
  void load()
  void getAssistantStatus()
    .then((status) => {
      if (active) assistantStatus.value = status
    })
    .catch(() => {
      if (active) ElMessage.warning('AI 小助状态加载失败，已安全禁用入口')
    })
})
watch(() => props.customerId, load)

onUnmounted(() => {
  active = false
  loadVersion.value += 1
})

async function load() {
  const version = ++loadVersion.value
  if (!Number.isInteger(props.customerId) || props.customerId <= 0) {
    customer.value = null
    return
  }
  loading.value = true
  try {
    const result = await getAdminCustomer(props.customerId)
    if (active && version === loadVersion.value) customer.value = result
  } catch {
    if (active && version === loadVersion.value) {
      customer.value = null
      ElMessage.error('客户详情加载失败或客户不存在')
    }
  } finally {
    if (active && version === loadVersion.value) loading.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="customer-detail">
    <div class="detail-toolbar">
      <el-button :icon="ArrowLeft" @click="router.push('/customers')">返回客户列表</el-button>
      <div class="spacer" />
      <el-button
        :type="assistantStatus.enabled ? 'primary' : 'default'"
        :icon="ChatDotRound"
        :disabled="!assistantStatus.enabled || !customer"
        @click="assistantVisible = true"
      >
        {{ assistantStatus.enabled ? 'AI 小助' : 'AI 小助未启用' }}
      </el-button>
    </div>

    <el-result
      v-if="!loading && !customer"
      icon="warning"
      title="客户不存在"
      sub-title="该客户可能已被删除或当前账号无权访问"
    >
      <template #extra><el-button @click="router.push('/customers')">返回列表</el-button></template>
    </el-result>

    <template v-else-if="customer">
      <section class="card hero">
        <div>
          <p class="eyebrow">当前银行客户</p>
          <h2>
            {{ customer.name }} <span class="customer-no">{{ customer.customerNo }}</span>
          </h2>
          <p class="muted">AI 会话后续只允许绑定此客户，切换客户必须创建或恢复独立会话。</p>
        </div>
        <el-tag :type="customer.status === 'ENABLED' ? 'success' : 'info'" size="large">
          {{ customer.status === 'ENABLED' ? '启用' : '已停用（仅只读）' }}
        </el-tag>
      </section>

      <section class="card">
        <h3 class="card-title">客户基本信息</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="客户编号">{{ customer.customerNo }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ customer.name }}</el-descriptions-item>
          <el-descriptions-item label="证件号">{{ customer.idCardMasked }}</el-descriptions-item>
          <el-descriptions-item label="客户类型">{{ customer.type || '-' }}</el-descriptions-item>
          <el-descriptions-item label="行业">{{ customer.industry || '-' }}</el-descriptions-item>
          <el-descriptions-item label="地区">{{ customer.region || '-' }}</el-descriptions-item>
          <el-descriptions-item label="注册资本">{{ customer.regCapital || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数据更新时间">{{ fmtDateTime(customer.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
      </section>
    </template>

    <CustomerAssistantDrawer
      v-if="customer"
      v-model:visible="assistantVisible"
      :customer-id="customer.id"
      :customer-no="customer.customerNo"
      :customer-name="customer.name"
      :max-message-chars="assistantStatus.maxMessageChars"
      :data-updated-at="fmtDateTime(customer.updatedAt)"
    />
  </div>
</template>

<style scoped>
.customer-detail {
  display: flex;
  flex-direction: column;
  gap: 0;
  min-height: 320px;
}
.detail-toolbar,
.hero {
  display: flex;
  align-items: center;
  gap: 16px;
}
.spacer {
  flex: 1;
}
.hero {
  justify-content: space-between;
}
.hero h2 {
  margin: 5px 0 8px;
  font-size: 24px;
}
.eyebrow {
  margin: 0;
  color: var(--text-faint);
  font-size: 12px;
  letter-spacing: 0.04em;
}
.customer-no {
  margin-left: 8px;
  color: var(--text-faint);
  font: 13px var(--font-mono);
}
.muted {
  margin: 0;
  color: var(--text-faint);
  font-size: 13px;
}
@media (max-width: 720px) {
  .hero {
    align-items: flex-start;
    flex-direction: column;
  }
  :deep(.el-descriptions__body) {
    overflow-x: auto;
  }
}
</style>
