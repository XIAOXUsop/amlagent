<script setup lang="ts">
import { useCaseDashboardController } from './CaseDashboard.controller'

defineOptions({ name: 'CaseDashboard' })

const emit = defineEmits<{ 'open-case': [caseId: number] }>()
// @ts-expect-error 外置模板的引用不会进入 vue-tsc 的 noUnusedLocals 分析，但这些绑定均由模板消费。
const {
  alertExternalId,
  alertHitReason,
  alertInbox,
  alertInboxError,
  alertRule,
  alertRuleCode,
  alertScenarioCode,
  alertSubmitting,
  cases,
  closeAsDuplicate,
  createAlertCase,
  createInboxAlert,
  currentUser,
  customers,
  dispositionText,
  eddLoadError,
  eddRequiredItemText,
  eddTasks,
  fmtDateTime,
  handleCreate,
  handleProcess,
  listLoading,
  loading,
  loadOperations,
  mergeAlert,
  onPageChange,
  operations,
  operationsError,
  operationsLoading,
  operationsOverdueOnly,
  operationsPhase,
  operationsPriority,
  overview,
  page,
  pageSize,
  phaseText,
  Plus,
  priorityTag,
  priorityText,
  processingId,
  Refresh,
  refresh,
  remainingText,
  riskMeta,
  scenarioName,
  scenarioOptions,
  Search,
  selectedCustomer,
  srcTag,
  statusMeta,
  total,
  triagingAlertId,
} = useCaseDashboardController(emit)
</script>

<template src="./templates/CaseDashboard.template.html"></template>

<style scoped src="./styles/CaseDashboard.css"></style>
