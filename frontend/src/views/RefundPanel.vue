<script setup lang="ts">
/**
 * 后续退款面板（v4 计划 §7.2 / G3-2）：金额账（原付/已退/待退/保留）+ 退款事件登记 +
 * 收款权限评估。收款人不同于原付款人时展示身份与权限核对提示，不默认"同集团可退"。
 */
import { onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  registerRefundEvent,
  getRefundLedger,
  assessRefundAuthority,
  type RefundLedgerResult,
  type RefundAdmissibilityResult,
  type RefundCommercialReason,
  type RefundRecipientAuthority,
} from '../api/client'
import { apiErrorMessage } from '../utils/api-error'

const props = defineProps<{ caseId: number }>()

const originalTx = ref('')
const ledger = ref<RefundLedgerResult | null>(null)
const loading = ref(false)

interface RefundForm {
  sourceSystem: string
  externalEventId: string
  eventStatus: 'REQUESTED'
  payerSubject: string
  payeeSubject: string
  amount: string
  recipientAuthority: RefundRecipientAuthority
  commercialReason: RefundCommercialReason
}

const form = ref<RefundForm>({
  sourceSystem: 'CUSTOMER_PROVIDED',
  externalEventId: '',
  eventStatus: 'REQUESTED',
  payerSubject: '',
  payeeSubject: '',
  amount: '',
  recipientAuthority: 'UNRESOLVED',
  commercialReason: 'MISSING',
})
const assessment = ref<RefundAdmissibilityResult | null>(null)
const submitting = ref(false)
const assessing = ref(false)
let contextVersion = 0

watch(
  () => props.caseId,
  () => {
    contextVersion += 1
    ledger.value = null
    assessment.value = null
    loading.value = false
    submitting.value = false
    assessing.value = false
  },
)

onUnmounted(() => {
  contextVersion += 1
})

async function loadLedger() {
  const transactionId = originalTx.value.trim()
  if (!transactionId) {
    ElMessage.warning('请输入原付款交易编号')
    return
  }
  const caseId = props.caseId
  const version = contextVersion
  loading.value = true
  try {
    const result = await getRefundLedger(caseId, [transactionId])
    if (version !== contextVersion || props.caseId !== caseId || originalTx.value.trim() !== transactionId) return
    ledger.value = result
  } catch (error: unknown) {
    if (version !== contextVersion || props.caseId !== caseId) return
    ElMessage.error(apiErrorMessage(error, '金额账获取失败'))
  } finally {
    if (version === contextVersion && props.caseId === caseId) loading.value = false
  }
}

async function registerEvent() {
  if (submitting.value) return
  if (
    !form.value.externalEventId.trim() ||
    !form.value.amount.trim() ||
    !form.value.payerSubject.trim() ||
    !form.value.payeeSubject.trim()
  ) {
    ElMessage.warning('请填写事件编号、金额、付款人与收款人主体')
    return
  }
  const caseId = props.caseId
  const version = contextVersion
  const transactionId = originalTx.value.trim()
  const submission = { ...form.value }
  submitting.value = true
  try {
    const result = await registerRefundEvent(caseId, {
      sourceSystem: submission.sourceSystem,
      externalEventId: submission.externalEventId.trim(),
      eventStatus: submission.eventStatus,
      payerSubject: submission.payerSubject.trim(),
      payeeSubject: submission.payeeSubject.trim(),
      amount: submission.amount.trim(),
      currency: 'CNY',
      effectiveAt: new Date().toISOString(),
      allocations: transactionId
        ? [
            {
              originalTransactionId: transactionId,
              originalAllocationKey: 'TXN',
              allocatedAmount: submission.amount.trim(),
            },
          ]
        : [],
    })
    if (version !== contextVersion || props.caseId !== caseId) return
    if (result.idempotentReplay) {
      ElMessage.info(`幂等重放：返回原事件 #${result.eventId}`)
    } else {
      ElMessage.success(
        `退款事件已登记（#${result.eventId}）；剩余未分配 ${result.unallocatedAmount} ${result.currency}`,
      )
    }
    form.value.externalEventId = ''
    form.value.amount = ''
    await loadLedger()
  } catch (error: unknown) {
    if (version !== contextVersion || props.caseId !== caseId) return
    ElMessage.error(apiErrorMessage(error, '退款登记失败'))
  } finally {
    if (version === contextVersion && props.caseId === caseId) submitting.value = false
  }
}

async function assess() {
  if (assessing.value) return
  const caseId = props.caseId
  const version = contextVersion
  const recipientAuthority = form.value.recipientAuthority
  const commercialReason = form.value.commercialReason
  assessing.value = true
  try {
    const result = await assessRefundAuthority(caseId, { recipientAuthority, commercialReason })
    if (version !== contextVersion || props.caseId !== caseId) return
    assessment.value = result
  } catch (error: unknown) {
    if (version !== contextVersion || props.caseId !== caseId) return
    ElMessage.error(apiErrorMessage(error, '退款权限评估失败'))
  } finally {
    if (version === contextVersion && props.caseId === caseId) assessing.value = false
  }
}
</script>

<template>
  <div class="card">
    <h3 class="card-title">后续退款（集团代付后变化）</h3>
    <p class="hint muted">
      退款是原决定之后的新事实：金额账只计入已入账（POSTED）退款；申请中（REQUESTED）另列待退义务；
      冲正恢复余额并保留历史。分析员手工登记只能形成待核实申请，到账状态由受信资金系统产生。
    </p>
    <div class="ledger-bar">
      <el-input
        v-model="originalTx"
        aria-label="原付款交易编号"
        size="small"
        placeholder="原付款交易编号（如 T-1001）"
        style="width: 200px"
      />
      <el-button size="small" :loading="loading" @click="loadLedger">查询金额账</el-button>
    </div>
    <div v-if="ledger" class="ledger-summary">
      <span
        >原付款 <strong>{{ ledger.totalOriginal }}</strong></span
      >
      <span
        >已退 <strong class="ok">{{ ledger.totalRefunded }}</strong></span
      >
      <span
        >待退（申请中）<strong class="warn">{{ ledger.totalPendingRefund }}</strong></span
      >
      <span
        >当前保留 <strong>{{ ledger.totalRetained }}</strong></span
      >
      <span v-if="Object.keys(ledger.overAllocations).length" class="over">
        超额：{{ JSON.stringify(ledger.overAllocations) }}
      </span>
    </div>

    <h4 class="sub-title">登记退款事件</h4>
    <div class="refund-form">
      <el-tag type="info">手工声明 · 待核实</el-tag>
      <el-input
        v-model="form.externalEventId"
        aria-label="来源事件编号"
        size="small"
        placeholder="来源事件编号（幂等键）"
        style="width: 200px"
      />
      <el-input
        v-model="form.payerSubject"
        aria-label="退款付款人"
        size="small"
        placeholder="退款付款人（本案客户）"
        style="width: 180px"
      />
      <el-input
        v-model="form.payeeSubject"
        aria-label="实际收款主体"
        size="small"
        placeholder="实际收款主体"
        style="width: 180px"
      />
      <el-input v-model="form.amount" aria-label="退款金额" size="small" placeholder="退款金额" style="width: 140px" />
      <el-button size="small" type="primary" plain :loading="submitting" @click="registerEvent">登记</el-button>
    </div>

    <h4 class="sub-title">收款权限与商业原因评估（RF-06~10）</h4>
    <div class="refund-form">
      <el-select v-model="form.recipientAuthority" aria-label="收款权限" size="small" style="width: 260px">
        <el-option label="原付款主体，账户归属已核验" value="ORIGINAL_PAYER_VERIFIED" />
        <el-option label="合同买方，收款权限已独立核实" value="BUYER_VERIFIED" />
        <el-option label="权限未知（仅声明同集团/买方）" value="UNRESOLVED" />
        <el-option label="权限被否认/矛盾" value="CONTRADICTED" />
      </el-select>
      <el-select v-model="form.commercialReason" aria-label="退款商业原因" size="small" style="width: 200px">
        <el-option label="退货/解除依据已核验" value="VERIFIED" />
        <el-option label="仅客户声明，无独立依据" value="UNVERIFIED" />
        <el-option label="未提供" value="MISSING" />
      </el-select>
      <el-button size="small" :loading="assessing" @click="assess">评估</el-button>
    </div>
    <el-alert
      v-if="assessment"
      :type="assessment.admissible ? 'success' : 'warning'"
      :closable="false"
      show-icon
      class="assessment"
    >
      <template #title>{{ assessment.admissible ? '可提交退款解释' : '存在阻断' }}</template>
      <template #default>
        {{ assessment.explanation }}
        <span v-if="assessment.blockerCode">（{{ assessment.blockerCode }}）</span>
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
.ledger-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.ledger-summary {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  margin: 10px 0;
  font-size: 13px;
}
.ledger-summary .ok {
  color: var(--el-color-success);
}
.ledger-summary .warn {
  color: var(--el-color-warning);
}
.ledger-summary .over {
  color: var(--el-color-danger);
}
.refund-form {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 8px;
}
.sub-title {
  margin: 14px 0 8px;
}
.assessment {
  margin-top: 6px;
}
.muted {
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}
</style>
