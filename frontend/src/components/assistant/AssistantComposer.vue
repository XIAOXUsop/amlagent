<script setup lang="ts">
const props = defineProps<{
  modelValue: string
  maxChars: number
  disabled?: boolean
  loading?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: []
}>()

function submit() {
  if (!props.disabled && !props.loading && props.modelValue.trim()) emit('submit')
}
</script>

<template>
  <div class="composer">
    <el-input
      :model-value="modelValue"
      type="textarea"
      :rows="3"
      resize="none"
      :maxlength="maxChars"
      show-word-limit
      placeholder="仅可询问当前客户及银行金融相关问题"
      :disabled="disabled || loading"
      @update:model-value="emit('update:modelValue', $event)"
      @keydown.ctrl.enter.prevent="submit"
      @keydown.meta.enter.prevent="submit"
    />
    <div class="actions">
      <span>Ctrl / ⌘ + Enter 发送</span>
      <el-button type="primary" :loading="loading" :disabled="disabled || !modelValue.trim()" @click="submit">
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.composer { display: flex; flex-direction: column; gap: 8px; }
.actions { display: flex; align-items: center; justify-content: space-between; color: var(--text-faint); font-size: 12px; }
</style>
