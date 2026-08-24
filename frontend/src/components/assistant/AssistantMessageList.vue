<script setup lang="ts">
import type { AssistantMessage } from '../../api/client'

defineProps<{
  messages: AssistantMessage[]
  streamingMessageId?: string | null
}>()

function roleLabel(role: AssistantMessage['role']) {
  return role === 'USER' ? '管理员' : 'AI 小助'
}

function statusLabel(status: AssistantMessage['status']) {
  return ({
    ACCEPTED: '已接收',
    PROCESSING: '分析中',
    COMPLETED: '已完成',
    REFUSED: '已拒绝',
    FAILED: '失败',
    BLOCKED: '已拦截',
  } as const)[status]
}
</script>

<template>
  <div class="message-list" aria-live="polite">
    <el-empty v-if="messages.length === 0" description="还没有对话，选择一个建议问题开始分析" />
    <article
      v-for="message in messages"
      :key="message.id"
      class="message"
      :class="message.role.toLowerCase()"
    >
      <header>
        <strong>{{ roleLabel(message.role) }}</strong>
        <el-tag
          v-if="message.role === 'ASSISTANT' && message.status !== 'COMPLETED'"
          size="small"
          :type="message.status === 'FAILED' || message.status === 'BLOCKED' ? 'danger' : 'warning'"
        >
          {{ statusLabel(message.status) }}
        </el-tag>
      </header>
      <!-- 回答必须按纯文本渲染，严禁 v-html，避免模型输出触发 XSS。 -->
      <p class="content">{{ message.content || (message.id === streamingMessageId ? '正在分析…' : '等待处理…') }}</p>
    </article>
  </div>
</template>

<style scoped>
.message-list { display: flex; flex-direction: column; gap: 14px; min-height: 220px; }
.message { max-width: 88%; padding: 12px 14px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); }
.message.user { align-self: flex-end; background: #eff6ff; border-color: #bfdbfe; }
.message.assistant { align-self: flex-start; }
.message header { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; color: var(--text-faint); font-size: 12px; }
.content { margin: 0; color: var(--text); line-height: 1.7; overflow-wrap: anywhere; white-space: pre-wrap; }
</style>
