<script setup lang="ts">
import { computed } from 'vue'
import type { AssistantMessage } from '../../api/client'

const props = defineProps<{ messages: AssistantMessage[] }>()

const evidenceIds = computed(() => {
  const ids = new Set<string>()
  for (const message of props.messages) {
    if (message.role !== 'ASSISTANT') continue
    for (const match of message.content.matchAll(/\b(?:EV|KB)-[A-Z0-9_-]+\b/gi)) ids.add(match[0].toUpperCase())
  }
  return [...ids]
})
</script>

<template>
  <el-collapse v-if="evidenceIds.length" class="evidence-panel">
    <el-collapse-item title="回答引用的证据标识" name="evidence">
      <p>证据标识用于审计追溯；敏感原文不会在浏览器中展示。</p>
      <el-space wrap>
        <el-tag v-for="id in evidenceIds" :key="id" type="info">{{ id }}</el-tag>
      </el-space>
    </el-collapse-item>
  </el-collapse>
</template>

<style scoped>
.evidence-panel { margin-top: 14px; }
.evidence-panel p { margin-top: 0; color: var(--text-faint); font-size: 12px; }
</style>
