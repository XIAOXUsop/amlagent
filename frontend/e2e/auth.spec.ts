import { test, expect } from '@playwright/test'

test.describe('认证与导航', () => {
  test('登录后进入工单中心', async ({ page }) => {
    await page.goto('/')
    await page.fill('input[placeholder="用户名"]', 'admin')
    await page.fill('input[placeholder="密码"]', 'admin123')
    await page.getByRole('button', { name: /登\s*录/ }).click()

    await expect(page.locator('.topbar h1')).toContainText('智能反洗钱尽调 Agent')
    await expect(page.locator('.nav')).toContainText('工单中心')
  })

  test('ADMIN 可见评测中心导航', async ({ page }) => {
    await page.goto('/')
    await page.fill('input[placeholder="用户名"]', 'admin')
    await page.fill('input[placeholder="密码"]', 'admin123')
    await page.getByRole('button', { name: /登\s*录/ }).click()

    await expect(page.locator('.nav')).toContainText('评测中心')
  })

  test('未登录访问被重定向到登录页', async ({ page }) => {
    await page.goto('/cases')
    await expect(page.locator('input[placeholder="用户名"]')).toBeVisible()
  })
})
