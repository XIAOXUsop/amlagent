<script setup lang="ts">
import { useReviewViewController } from './ReviewView.controller'

defineOptions({ name: 'ReviewView' })

// @ts-expect-error 外置模板的引用不会进入 vue-tsc 的 noUnusedLocals 分析，但这些绑定均由模板消费。
const {
  activeEdd,
  activeEddTitle,
  assignedTo,
  assignedUnit,
  assignees,
  cancelActiveEdd,
  cancellingEdd,
  comment,
  currentInvestigationBlockers,
  currentReasons,
  deadlineText,
  decision,
  dialogOpen,
  doSubmit,
  dueAt,
  eddLoading,
  fmtDateTime,
  loading,
  markReportSubmitted,
  openReview,
  operationsFor,
  pending,
  pendingReports,
  priorityLabels,
  priorityType,
  reasonCode,
  requiredItemOptions,
  requiredItems,
  reviewerRiskLevel,
  reviewing,
  riskMeta,
  Search,
  Stamp,
  stats,
  submitting,
  submittingReportId,
} = useReviewViewController()
</script>

<template src="./templates/ReviewView.template.html"></template>

<style scoped src="./styles/ReviewView.css"></style>
