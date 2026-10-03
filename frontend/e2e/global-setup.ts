import { chromium } from '@playwright/test'

/**
 * 预热：在正式用例之前，先把前端跑一遍。
 *
 * ── 为什么需要这一步 ────────────────────────────────────────────────
 *
 * Vite 的依赖预构建（optimizeDeps）是**按需触发**的：某个路由第一次被访问、
 * 用到了还没预构建的依赖时，dev server 会重新构建并让浏览器**整页重载**。
 *
 * 这在真实使用里无感（刷新一下就好），但在测试里是致命的：
 * 用例刚点完"创建并尽调"、正等 URL 跳转时页面被重载，断言就红了。
 * 而这个失败**只在全新安装后第一次跑时出现**——本地第二次跑就绿，
 * 于是很容易被当成偶发。CI 每次都是全新 `npm ci`，所以每次必红。
 *
 * 预热把这次重载挪到用例之外：登录、进工单页、进复核页各走一遍，
 * 让这些路由用到的依赖在测试开始前就全部预构建完。
 */
export default async function globalSetup() {
  const baseURL = 'http://localhost:5173'
  const browser = await chromium.launch()
  const page = await browser.newPage()

  try {
    await page.goto(baseURL, { waitUntil: 'networkidle' })
    await page.fill('input[placeholder="用户名"]', 'admin')
    await page.fill('input[placeholder="密码"]', 'admin123')
    await page.getByRole('button', { name: /登\s*录/ }).click()
    await page.waitForSelector('.nav', { timeout: 30_000 })
    await page.waitForLoadState('networkidle')

    // 复核页与工单详情页都是懒加载路由，各自会触发一批依赖的预构建。
    // 详情页要单独访问一次：它用的是另一组 el-* 组件，dashboard 与复核页都覆盖不到，
    // 漏掉它就会出现"setup 跑完了、正式用例里照样重载"。
    // 这里用一个不存在的工单号——路由匹配与组件加载与工单是否存在无关。
    for (const path of ['/reviews', '/cases/1']) {
      await page.goto(`${baseURL}${path}`, { waitUntil: 'networkidle' })
      // 预构建完成后 dev server 会触发整页重载，再等它稳定一次
      await page.waitForTimeout(1_500)
      await page.waitForLoadState('networkidle')
    }
  } finally {
    await browser.close()
  }
}
