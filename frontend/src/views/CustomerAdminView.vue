<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  createAdminCustomer,
  deleteAdminCustomer,
  importCustomers,
  listAdminCustomers,
  setCustomerStatus,
  updateAdminCustomer,
  type CustomerAdminItem,
  type CustomerEditPayload,
} from '../api/client'
import { Plus, Search, Upload } from '@element-plus/icons-vue'

const list = ref<CustomerAdminItem[]>([])
const router = useRouter()
const total = ref(0)
const page = ref(0)
const pageSize = 10
const keyword = ref('')
const loading = ref(false)
const dialogVisible = ref(false)
const editing = ref<CustomerAdminItem | null>(null)
const saving = ref(false)
const form = ref<CustomerEditPayload>({
  name: '',
  idCard: '',
  type: '个人',
  industry: '',
  region: '',
  regCapital: '',
  status: 'ENABLED',
})
const importing = ref(false)

onMounted(refresh)

async function refresh() {
  loading.value = true
  try {
    const p = await listAdminCustomers(page.value, pageSize, keyword.value)
    list.value = p.content
    total.value = p.totalElements
  } catch {
    ElMessage.error('客户列表加载失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  page.value = 0
  refresh()
}

function onPageChange(p: number) {
  page.value = p - 1
  refresh()
}

function openCreate() {
  editing.value = null
  form.value = { name: '', idCard: '', type: '个人', industry: '', region: '', regCapital: '', status: 'ENABLED' }
  dialogVisible.value = true
}

function openEdit(value: unknown) {
  const row = value as CustomerAdminItem
  editing.value = row
  form.value = {
    name: row.name,
    idCard: '',
    type: row.type,
    industry: row.industry,
    region: row.region,
    regCapital: row.regCapital,
    status: row.status,
  }
  dialogVisible.value = true
}

function openDetail(value: unknown) {
  const row = value as CustomerAdminItem
  router.push(`/customers/${row.id}`)
}

async function save() {
  if (!form.value.name?.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (!editing.value && !form.value.idCard?.trim()) {
    ElMessage.warning('请填写证件号')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      // 编辑时证件号非必填（留空表示不修改）
      const payload: CustomerEditPayload = { ...form.value }
      if (!payload.idCard) delete payload.idCard
      await updateAdminCustomer(editing.value.id, payload)
    } else {
      await createAdminCustomer(form.value as CustomerEditPayload)
    }
    ElMessage.success(editing.value ? '客户已更新' : '客户已新增')
    dialogVisible.value = false
    await refresh()
  } catch (error: unknown) {
    ElMessage.error(apiErrorMessage(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function toggleStatus(value: unknown) {
  const row = value as CustomerAdminItem
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    await setCustomerStatus(row.id, next)
    ElMessage.success(next === 'ENABLED' ? '已启用' : '已停用')
    await refresh()
  } catch (error: unknown) {
    ElMessage.error(apiErrorMessage(error, '操作失败'))
  }
}

async function remove(value: unknown) {
  const row = value as CustomerAdminItem
  try {
    await ElMessageBox.confirm(`确认删除客户「${row.name}（${row.customerNo}）」？逻辑删除后不再出现在新建工单下拉中。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteAdminCustomer(row.id)
    ElMessage.success('已删除')
    await refresh()
  } catch (error: unknown) {
    ElMessage.error(apiErrorMessage(error, '删除失败'))
  }
}

async function onImport(file: { raw?: File }) {
  const raw = file?.raw
  if (!raw) return
  importing.value = true
  try {
    const r = await importCustomers(raw)
    ElMessage.success(`导入完成：成功 ${r.success}，失败 ${r.failed}（共 ${r.total}）`)
    if (r.errors.length) {
      ElMessage.warning(r.errors.slice(0, 3).join('；'))
    }
    await refresh()
  } catch (error: unknown) {
    ElMessage.error(apiErrorMessage(error, '导入失败，请使用 .xlsx 或 .xls 文件'))
  } finally {
    importing.value = false
  }
}

function fmtTime(s: string): string {
  return s?.replace('T', ' ').slice(0, 16) ?? '-'
}

function apiErrorMessage(error: unknown, fallback: string): string {
  if (typeof error !== 'object' || error === null || !('response' in error)) return fallback
  const response = (error as { response?: { data?: { message?: unknown } } }).response
  return typeof response?.data?.message === 'string' ? response.data.message : fallback
}
</script>

<template>
  <div class="customer-admin">
    <header class="page-intro">
      <h2>客户</h2>
      <p>维护客户主数据，进入客户详情或发起 AI 辅助分析。</p>
    </header>
    <div class="toolbar card">
      <el-input v-model="keyword" placeholder="搜索编号/姓名/证件号" clearable style="width: 260px" @keyup.enter="onSearch" />
      <el-button type="primary" :icon="Search" @click="onSearch">搜索</el-button>
      <div class="spacer" />
      <el-button type="primary" plain :icon="Plus" @click="openCreate">新增人员</el-button>
      <el-upload :show-file-list="false" :auto-upload="false" accept=".xlsx,.xls" :disabled="importing" @change="onImport">
        <el-button plain :icon="Upload" :loading="importing">Excel 导入</el-button>
      </el-upload>
    </div>

    <div class="card">
      <h3 class="card-title">客户/人员列表</h3>
      <el-table :data="list" v-loading="loading" stripe style="width: 100%">
        <el-table-column prop="customerNo" label="编号" width="90" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column prop="idCardMasked" label="证件号" min-width="170" />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column prop="industry" label="行业" min-width="120" />
        <el-table-column prop="region" label="地区" width="100" />
        <el-table-column prop="regCapital" label="注册资本" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span class="mono-num time">{{ fmtTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" type="primary" link @click="openDetail(row)">查看</el-button>
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" @click="toggleStatus(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
              <el-button size="small" type="danger" link @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination layout="total, prev, pager, next" :total="total" :page-size="pageSize" @current-change="onPageChange" />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑人员' : '新增人员'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item :label="editing ? '证件号（留空不修改）' : '证件号'" :required="!editing">
          <el-input v-model="form.idCard" placeholder="请输入身份证号/证件号" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="个人" value="个人" />
            <el-option label="个体工商户" value="个体工商户" />
            <el-option label="企业法人" value="企业法人" />
          </el-select>
        </el-form-item>
        <el-form-item label="行业">
          <el-input v-model="form.industry" placeholder="如 国际贸易" />
        </el-form-item>
        <el-form-item label="地区">
          <el-input v-model="form.region" placeholder="如 上海" />
        </el-form-item>
        <el-form-item label="注册资本">
          <el-input v-model="form.regCapital" placeholder="如 注册资本5000万人民币" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.customer-admin {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.time {
  font-size: 12px;
  color: var(--text-dim);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 720px) {
  .toolbar :deep(.el-input) { width: 100% !important; }
  .toolbar .spacer { display: none; }
}
</style>
