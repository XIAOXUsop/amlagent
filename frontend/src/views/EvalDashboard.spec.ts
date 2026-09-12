import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { getStatus, getDataset, runEval, messageSuccess, messageError } = vi.hoisted(() => ({
  getStatus: vi.fn(),
  getDataset: vi.fn(),
  runEval: vi.fn(),
  messageSuccess: vi.fn(),
  messageError: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: messageSuccess, error: messageError },
}))

vi.mock('../api/client', () => ({
  getAgentEvalStatus: (...args: unknown[]) => getStatus(...args),
  getAgentEvalDatasetSummary: (...args: unknown[]) => getDataset(...args),
  runAgentDevEval: (...args: unknown[]) => runEval(...args),
}))

import EvalDashboard from './EvalDashboard.vue'

function deferred<T>() {
  let settle: ((value: T) => void) | undefined
  const promise = new Promise<T>((resolve) => {
    settle = resolve
  })
  return {
    promise,
    resolve(value: T) {
      settle?.(value)
    },
  }
}

describe('EvalDashboard 异步生命周期', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getStatus.mockResolvedValue({ ready: true, message: '' })
    getDataset.mockResolvedValue({ datasetId: 'dev', version: 1 })
    runEval.mockResolvedValue({ split: 'DEV', runStatus: 'COMPLETED' })
  })

  it('卸载后忽略状态请求结果与用户提示', async () => {
    const statusRequest = deferred<unknown>()
    const datasetRequest = deferred<unknown>()
    getStatus.mockReturnValue(statusRequest.promise)
    getDataset.mockReturnValue(datasetRequest.promise)
    const wrapper = mount(EvalDashboard)

    wrapper.unmount()
    statusRequest.resolve({ ready: true, message: '' })
    datasetRequest.resolve({ datasetId: 'dev', version: 1 })
    await Promise.all([statusRequest.promise, datasetRequest.promise])
    await vi.dynamicImportSettled()

    expect(messageSuccess).not.toHaveBeenCalled()
    expect(messageError).not.toHaveBeenCalled()
  })

  it('重复点击评测按钮只提交一次并在卸载后忽略结果', async () => {
    const runRequest = deferred<unknown>()
    runEval.mockReturnValue(runRequest.promise)
    const wrapper = mount(EvalDashboard)
    await vi.dynamicImportSettled()
    const runButton = wrapper.findAll('el-button').find((button) => button.text().includes('运行 DEV 分片'))
    expect(runButton).toBeDefined()

    await runButton?.trigger('click')
    await runButton?.trigger('click')
    expect(runEval).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    runRequest.resolve({ split: 'DEV', runStatus: 'COMPLETED' })
    await runRequest.promise
    await vi.dynamicImportSettled()
    expect(messageSuccess).not.toHaveBeenCalled()
    expect(messageError).not.toHaveBeenCalled()
  })
})
