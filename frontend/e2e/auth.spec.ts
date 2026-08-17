import { test, expect } from '@playwright/test'

async function login(page: import('@playwright/test').Page, username: string, password: string) {
  await page.goto('/')
  await page.fill('input[placeholder="用户名"]', username)
  await page.fill('input[placeholder="密码"]', password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await expect(page.locator('.brand h1')).toContainText('AML')
}

test.describe('认证与导航', () => {
  test('登录后进入工单中心', async ({ page }) => {
    await login(page, 'admin', 'admin123')
    await expect(page.locator('.nav')).toContainText('工单中心')
  })

  test('ADMIN 可见评测中心与人工复核导航', async ({ page }) => {
    await login(page, 'admin', 'admin123')
    await expect(page.locator('.nav')).toContainText('评测中心')
    await expect(page.locator('.nav')).toContainText('人工复核')
  })

  test('REVIEWER 可见人工复核但不可见评测中心', async ({ page }) => {
    await login(page, 'reviewer', 'reviewer123')
    await expect(page.locator('.nav')).toContainText('人工复核')
    await expect(page.locator('.nav')).not.toContainText('评测中心')
  })

  test('ANALYST 仅见工单中心，不显示管理菜单', async ({ page }) => {
    await login(page, 'analyst', 'analyst123')
    await expect(page.locator('.nav')).toContainText('工单中心')
    await expect(page.locator('.nav')).not.toContainText('评测中心')
    await expect(page.locator('.nav')).not.toContainText('人工复核')
  })

  test('未登录访问被重定向到登录页', async ({ page }) => {
    await page.goto('/cases')
    await expect(page.locator('input[placeholder="用户名"]')).toBeVisible()
  })

  test('登出后回到登录页', async ({ page }) => {
    await login(page, 'admin', 'admin123')
    await page.getByRole('button', { name: '退出' }).click()
    await expect(page.locator('input[placeholder="用户名"]')).toBeVisible()
  })
})
