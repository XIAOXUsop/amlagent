<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import {
  createAssistantConversation,
  listAssistantConversations,
  listAssistantMessages,
  submitAssistantMessage,
  subscribeAssistantRun,
  type AssistantConversation,
  type AssistantMessage,
  type SseState,
} from '../../api/client'
import { apiErrorCode, apiErrorMessage } from '../../utils/api-error'
import AssistantComposer from './AssistantComposer.vue'
import AssistantEvidencePanel from './AssistantEvidencePanel.vue'
import AssistantMessageList from './AssistantMessageList.vue'

const props = defineProps<{
  customerId: number
  customerNo: string
  customerName: string
  maxMessageChars: number
  dataUpdatedAt: string
}>()
const visible = defineModel<boolean>('visible', { required: true })

const conversation = ref<AssistantConversation | null>(null)
const messages = ref<AssistantMessage[]>([])
const draft = ref('')
const loading = ref(false)
const sending = ref(false)
const streamState = ref<SseState>('closed')
const streamingMessageId = ref<string | null>(null)
const scrollArea = ref<HTMLElement | null>(null)
let unsubscribe: (() => void) | null = null
let loadVersion = 0

const quickQuestions = [
  '请概括当前客户的主要风险信号，并引用证据标识。',
  '请分析当前客户的交易特征，哪些方面需要人工复核？',
  '当前客户是否存在制裁或受益所有权相关风险？',
]
const streamLabel = computed(
  () =>
    ({
      connecting: '正在连接',
      open: '实时输出中',
      reconnecting: '连接恢复中',
      closed: '已对账',
    })[streamState.value],
)

watch([visible, () => props.customerId], async ([isVisible]) => {
  loadVersion += 1
  stopStream()
  conversation.value = null
  messages.value = []
  if (isVisible) await loadConversation()
})
onBeforeUnmount(() => {
  loadVersion += 1
  stopStream()
})

async function loadConversation() {
  const version = ++loadVersion
  const targetCustomerId = props.customerId
  loading.value = true
  try {
    const page = await listAssistantConversations(targetCustomerId)
    let current = page.content.find((item) => item.status === 'ACTIVE') ?? null
    current ??= await createAssistantConversation(targetCustomerId)
    if (version !== loadVersion || !visible.value || props.customerId !== targetCustomerId) return
    conversation.value = current
    await refreshMessages(version, current.id)
  } catch {
    if (version === loadVersion && visible.value && props.customerId === targetCustomerId) {
      ElMessage.error('AI 会话初始化失败，请稍后重试')
    }
  } finally {
    if (version === loadVersion && props.customerId === targetCustomerId) loading.value = false
  }
}

async function refreshMessages(expectedVersion = loadVersion, conversationId = conversation.value?.id) {
  if (!conversationId) return
  const result = await listAssistantMessages(conversationId)
  if (expectedVersion !== loadVersion || !visible.value || conversation.value?.id !== conversationId) return
  messages.value = result
  await scrollToBottom()
}

function chooseQuickQuestion(question: string) {
  draft.value = question
}

async function send() {
  const content = draft.value.trim()
  const targetConversation = conversation.value
  if (!targetConversation || !content || sending.value) return
  const version = loadVersion
  const targetCustomerId = props.customerId
  const isCurrentContext = () =>
    version === loadVersion &&
    visible.value &&
    props.customerId === targetCustomerId &&
    conversation.value?.id === targetConversation.id
  stopStream()
  sending.value = true
  try {
    const clientMessageId =
      typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `msg_${Date.now()}_${Math.random().toString(36).slice(2)}`
    const accepted = await submitAssistantMessage(targetConversation.id, clientMessageId, content)
    if (!isCurrentContext()) return
    draft.value = ''
    await refreshMessages(version, targetConversation.id)
    if (!isCurrentContext()) return
    streamingMessageId.value = accepted.assistantMessageId
    unsubscribe = subscribeAssistantRun(
      accepted.runId,
      (delta) => {
        if (isCurrentContext()) appendDelta(accepted.assistantMessageId, delta)
      },
      () => {
        if (!isCurrentContext()) return
        streamingMessageId.value = null
        void refreshMessages(version, targetConversation.id)
          .catch(() => {
            if (isCurrentContext()) ElMessage.warning('回答已结束，但消息对账失败，请重新打开窗口')
          })
          .finally(() => {
            if (isCurrentContext()) sending.value = false
          })
      },
      (state) => {
        if (isCurrentContext()) streamState.value = state
      },
    )
  } catch (error: unknown) {
    if (!isCurrentContext()) return
    ElMessage.error(
      apiErrorCode(error) === 'CONVERSATION_BUSY' ? '上一条问题仍在处理中' : apiErrorMessage(error, '消息发送失败'),
    )
    sending.value = false
    try {
      await refreshMessages(version, targetConversation.id)
    } catch {
      ElMessage.warning('消息发送状态对账失败，请重新打开窗口')
    }
  }
}

function appendDelta(messageId: string, delta: string) {
  const target = messages.value.find((item) => item.id === messageId)
  if (!target) return
  target.status = 'PROCESSING'
  target.content += delta
  void scrollToBottom()
}

function stopStream() {
  unsubscribe?.()
  unsubscribe = null
  streamingMessageId.value = null
  streamState.value = 'closed'
  sending.value = false
}

async function scrollToBottom() {
  await nextTick()
  if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
}
</script>

<template>
  <el-drawer v-model="visible" size="min(720px, 96vw)" destroy-on-close>
    <template #header>
      <div class="drawer-title">
        <div>
          <strong>当前客户 AI 小助</strong><small>{{ customerName }} · {{ customerNo }}</small>
        </div>
        <el-tag type="success" effect="plain">只读分析</el-tag>
      </div>
    </template>

    <el-alert
      title="边界说明"
      type="info"
      :closable="false"
      description="仅回答当前客户及银行金融问题；不能修改数据、代替人工决策，也不会展示完整证件号或账户号。"
      show-icon
    />
    <p class="freshness">本次分析以数据快照为准 · 客户数据更新时间：{{ dataUpdatedAt }}</p>

    <div v-if="!messages.length && !loading" class="quick-questions">
      <el-button v-for="question in quickQuestions" :key="question" plain @click="chooseQuickQuestion(question)">
        {{ question }}
      </el-button>
    </div>

    <div ref="scrollArea" v-loading="loading" class="scroll-area">
      <AssistantMessageList :messages="messages" :streaming-message-id="streamingMessageId" />
      <AssistantEvidencePanel :messages="messages" />
    </div>

    <template #footer>
      <div class="footer">
        <span class="stream-state" :class="streamState">{{ streamLabel }}</span>
        <AssistantComposer
          v-model="draft"
          :max-chars="maxMessageChars"
          :disabled="!conversation"
          :loading="sending"
          @submit="send"
        />
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.drawer-title {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 12px;
}
.drawer-title div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.drawer-title small,
.freshness {
  color: var(--text-faint);
}
.freshness {
  margin: 9px 0 12px;
  font-size: 12px;
}
.quick-questions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  margin-bottom: 12px;
}
.quick-questions .el-button {
  height: auto;
  margin: 0;
  padding: 9px 12px;
  white-space: normal;
  text-align: left;
}
.scroll-area {
  height: calc(100vh - 365px);
  min-height: 260px;
  overflow-y: auto;
  padding: 6px 6px 18px;
}
.footer {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.stream-state {
  align-self: flex-end;
  color: var(--text-faint);
  font-size: 12px;
}
.stream-state.open {
  color: var(--el-color-success);
}
.stream-state.reconnecting {
  color: var(--el-color-warning);
}
</style>
