import { test, expect } from '@playwright/test'

test.describe('工单核心流程', () => {
  test('创建工单并进入详情查看风险', async ({ page }) => {
    await page.goto('/')
    await page.fill('input[placeholder="用户名"]', 'admin')
    await page.fill('input[placeholder="密码"]', 'admin123')
    await page.getByRole('button', { name: /登\s*录/ }).click()
    await expect(page.locator('.topbar h1')).toContainText('智能反洗钱尽调 Agent')

    // 等待客户下拉与默认预警规则就绪后创建
    await expect(page.locator('.card-title', { hasText: '新建预警工单' })).toBeVisible()
    await page.getByRole('button', { name: '创建并尽调' }).click()

    // 跳转到工单详情页
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await expect(page.locator('.card-title').first()).toContainText('工单 #')
  })
})
