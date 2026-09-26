import { test, expect, type Page } from '@playwright/test'

/**
 * 最小但有业务价值的端到端链路：登录 → 创建预警工单 → 等 Agent 定级 →
 * 核对报告与证据 ID → 由复核角色完成一次处置。
 *
 * 为什么不写成"打开页面看到标题"那种断言：那种测试在页面报错、报告为空、
 * 证据链缺失时**照样会通过**。这里每一条都对着一个业务事实——
 * 终态是什么、报告有没有定级、证据链里有没有真实的法规证据编号、
 * 处置完之后工单的业务状态变了没有。
 *
 * 不依赖真实模型：CI 与本地都用 Mock 模型，见 frontend/README 与 CI 的
 * AML_LLM_ACTIVE_PROVIDER=mock。Mock 是确定性的，因此终态可以断言而不是"等等看"。
 */

/** 工单终态：转人工 / 已完成 / 待报送 / 失败 */
const TERMINAL_STATUS = /转人工|已完成|待报送|失败/

async function login(page: Page, username: string, password: string) {
  await page.goto('/')
  await page.fill('input[placeholder="用户名"]', username)
  await page.fill('input[placeholder="密码"]', password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await expect(page.locator('.brand h1')).toContainText('AML')
}

/**
 * 把浏览器控制台、页面异常与失败请求挂进报告：失败时不必再去猜前端发生了什么。
 * 收集放在 beforeEach、挂载放在 afterEach——Playwright 这一版的 attach 只接受
 * 字符串或 Buffer，惰性求值的 body 函数会直接类型报错。
 */
const browserLogs: string[] = []

test.beforeEach(({ page }) => {
  browserLogs.length = 0
  page.on('console', (message) => browserLogs.push(`[${message.type()}] ${message.text()}`))
  page.on('pageerror', (error) => browserLogs.push(`[pageerror] ${error.message}`))
  page.on('requestfailed', (request) =>
    browserLogs.push(`[requestfailed] ${request.url()} ${request.failure()?.errorText}`),
  )
})

test.afterEach(async ({}, testInfo) => {
  await testInfo.attach('browser-console', {
    body: browserLogs.join('\n') || '(无输出)',
    contentType: 'text/plain',
  })
})

test.describe('预警工单闭环', () => {
  test('创建 → Agent 定级 → 报告与证据 → 人工复核处置', async ({ page }, testInfo) => {
    // 含一次完整 Agent 运行，给足时间；CI 上 Mock 通常几秒完成
    test.setTimeout(300_000)

    // ---------- ① 登录 ----------
    await login(page, 'admin', 'admin123')

    // ---------- ② 创建并尽调 ----------
    const createCard = page.locator('.card', {
      has: page.locator('.card-title', { hasText: '新建预警工单' }),
    })
    await expect(createCard).toBeVisible()

    // 客户列表是异步加载的，加载完成后会自动选中第一个客户。
    // 这里等它落定再点创建——否则点得比加载快，只会弹一个「请选择客户」，
    // 测试就会以"没跳转"的形式随机失败。
    // 断言选中项带了客户编号（标签形如「林涛（C-5406…）」）；
    // 注意不能用"不包含『选择客户』"来判断——占位元素选中后仍在 DOM 里，只是被置为透明。
    const customerSelect = createCard.locator('.create-bar .el-select')
    await expect(customerSelect).toContainText('（C', { timeout: 30_000 })

    await createCard.getByRole('button', { name: '创建并尽调' }).click()

    await expect(page).toHaveURL(/\/cases\/\d+$/)
    const caseId = Number(/\/cases\/(\d+)$/.exec(page.url())?.[1])
    expect(caseId, '创建后应跳转到带工单号的详情页').toBeGreaterThan(0)
    testInfo.annotations.push({ type: 'caseId', description: String(caseId) })

    // ---------- ③ 等待终态 ----------
    const statusTag = page.locator('.detail-bar .el-tag')
    await expect(statusTag).toHaveText(TERMINAL_STATUS, { timeout: 240_000 })
    const status = (await statusTag.innerText()).trim()

    // ---------- ④ 报告与证据：断言业务结果，不是标题 ----------
    const reportCard = page.locator('.card', {
      has: page.locator('.card-title', { hasText: '尽调初审报告' }),
    })
    await expect(reportCard).toBeVisible()

    const riskRow = reportCard.locator('.report-row', { has: page.locator('.report-label', { hasText: '风险评级' }) })
    await expect(riskRow.locator('.rk')).toHaveText(/风险/)

    // 证据链：必须非空，且至少含一个可回溯的法规证据编号（LEGAL-<hex>）。
    // 只断言"证据链存在"是不够的——那在链为空数组时也会通过。
    // 界面上证据是一条带前缀的展示文案（如「法规检索证据 LEGAL-0c31…」），
    // 因此按子串匹配编号，而不是要求整条就是编号。
    const evidenceTexts = (
      await reportCard.locator('.report-row', { hasText: '证据链' }).locator('.ev-chip').allInnerTexts()
    ).map((text) => text.trim())
    expect(evidenceTexts.length, '尽调报告的证据链不应为空').toBeGreaterThan(0)
    expect(
      evidenceTexts.some((text) => /LEGAL-[0-9a-f]{8,}/i.test(text)),
      `证据链里应至少有一个法规证据编号，实际：${JSON.stringify(evidenceTexts)}`,
    ).toBe(true)

    // 风险发现代码：Mock 会带出结构化的 findingCodes，用来证明报告不是空壳
    await expect(
      reportCard.locator('.report-row', { hasText: '风险发现代码' }).locator('.code-chip').first(),
    ).toBeVisible()

    // 只有进入 HOLD 才有人工处置可做；其余终态说明流程没走到复核环节
    expect(status, 'Mock 模型下案件应进入转人工，才能验证复核环节').toBe('转人工')

    // ---------- ⑤ 复核角色完成一次处置 ----------
    //
    // 这里走「补充尽调」而不是「确认可疑 / 排除预警」：后两者被调查链的闭环规则挡住
    // （预警要有覆盖结论、假设要确认或排除）。挡住是对的——一个刚跑完 Agent、
    // 还没人看过调查链的案子本来就不该被直接定案。复核人此时能做的唯一处置就是补充尽调，
    // 而这恰好也是一次真实的、可审计的处置记录。
    await page.getByRole('button', { name: '人工复核' }).click()
    await expect(page).toHaveURL(/\/reviews$/)

    const reviewRow = page.locator('.el-table__row', { hasText: `#${caseId}` })
    await expect(reviewRow).toBeVisible({ timeout: 30_000 })
    await reviewRow.getByRole('button', { name: '复核' }).click()

    const dialog = page.locator('.el-dialog', { hasText: '提交复核决定' })
    await expect(dialog).toBeVisible()
    await dialog.locator('.el-radio', { hasText: '补充尽调' }).click()

    // 承办人必须显式选：其余字段（补充材料、截止时间、承办部门）有业务默认值
    await dialog.locator('.el-form-item', { hasText: '承办人' }).locator('.el-select').click()
    // 页面上同时存在多个 el-select 的下拉容器（都挂在 body 上），必须挑**可见**的那个
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first().click()

    await dialog
      .getByPlaceholder(/记录核验的客户信息/)
      .fill(`端到端用例：案件 #${caseId} 的调查假设尚未排除、预警缺少覆盖结论，先补充尽调材料后再定案。`)
    await dialog.getByRole('button', { name: '提交' }).click()

    await expect(page.locator('.el-message--success').first()).toContainText('复核已提交', { timeout: 60_000 })

    // ---------- ⑥ 处置结果必须落到业务状态上 ----------
    await page.goto(`/cases/${caseId}`)

    // 终态仍是「转人工」：补充尽调不结案，等材料回来再由复核人处置
    await expect(page.locator('.detail-bar .el-tag')).toHaveText('转人工', { timeout: 60_000 })

    const reviewLog = page.locator('.card', { has: page.locator('.card-title', { hasText: '人工处置记录' }) })
    await expect(reviewLog.locator('.el-table__row', { hasText: '补充尽调' }).first()).toBeVisible()

    // 并且真的派出了补充尽调任务，而不是只写了一条记录
    const eddCard = page.locator('.card', { has: page.locator('.card-title', { hasText: '补充尽调任务' }) })
    await expect(eddCard.locator('.el-table__row').first()).toBeVisible({ timeout: 30_000 })
  })
})
