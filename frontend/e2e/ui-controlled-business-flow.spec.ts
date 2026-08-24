import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:18080'
const bootstrapPassword = process.env.E2E_BOOTSTRAP_PASSWORD
const adminPassword = process.env.E2E_ADMIN_PASSWORD
const rolePassword = process.env.E2E_ROLE_PASSWORD
const controlledImage = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

async function login(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('账号').fill(username)
  await page.getByLabel('密码').fill(password)
  const response = page.waitForResponse((candidate) => candidate.url().endsWith('/api/auth/login'))
  await page.getByRole('button', { name: '登录' }).click()
  return response
}

async function prepareAdmin(page: Page) {
  if (!bootstrapPassword || !adminPassword) throw new Error('管理员验收密码未配置')
  const existing = await login(page, 'admin', adminPassword)
  if (existing.ok()) {
    await expect(page.getByRole('heading', { name: '流程工作台' })).toBeVisible()
    return
  }
  const bootstrap = await login(page, 'admin', bootstrapPassword)
  expect(bootstrap.status()).toBe(200)
  await expect(page.getByRole('heading', { name: '首次登录，请设置新密码' })).toBeVisible()
  await page.getByLabel('当前密码').fill(bootstrapPassword)
  await page.getByLabel('新密码', { exact: true }).fill(adminPassword)
  await page.getByLabel('再次输入新密码').fill(adminPassword)
  await page.getByRole('button', { name: '保存并重新登录' }).click()
  await expect(page).toHaveURL(/\/login/)
  expect((await login(page, 'admin', adminPassword)).status()).toBe(200)
  await expect(page.getByRole('heading', { name: '流程工作台' })).toBeVisible()
}

async function ensureReviewerCanConfirmTests(page: Page) {
  await page.goto('/modules/permissions')
  await waitForIdle(page)
  await page.getByRole('button', { name: /报告审核人 REPORT_REVIEWER/ }).click()
  await page.getByRole('button', { name: '修订策略', exact: true }).click()
  const drawer = page.locator('.el-drawer:visible')
  await expect(drawer).toBeVisible()
  await drawer.getByPlaceholder('搜索动作名称或权限码').fill('TEST_CONFIRM')
  const permission = drawer.getByRole('treeitem', { name: /复核确认试验数据 TEST_CONFIRM/ })
  await expect(permission).toBeVisible()
  const checkbox = permission.getByRole('checkbox')
  if (await checkbox.isChecked()) {
    await drawer.getByRole('button', { name: '取消', exact: true }).click()
    return
  }
  await permission.locator('.el-checkbox').click()
  await drawer.getByRole('button', { name: '保存为新修订', exact: true }).click()
  await expect(drawer).toBeHidden()
  await expect(page.getByRole('button', { name: /报告审核人 REPORT_REVIEWER 本机构 · 5 项动作/ })).toBeVisible()
}

async function rolePage(browser: Browser, username: string) {
  if (!rolePassword) throw new Error('角色验收密码未配置')
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  page.setDefaultTimeout(8_000)
  page.setDefaultNavigationTimeout(15_000)
  expect((await login(page, username, rolePassword)).status()).toBe(200)
  await expect(page.getByRole('heading', { name: '流程工作台' })).toBeVisible()
  return { context, page }
}

async function waitForIdle(page: Page) {
  await page.locator('.el-loading-mask:visible').first().waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {})
}

function formItem(container: Locator, label: string) {
  return container.locator('.el-form-item').filter({ hasText: new RegExp(`^\\s*${label}`) }).first()
}

async function selectFirstOption(page: Page, item: Locator, preferred?: RegExp) {
  await item.locator('.el-select').click()
  const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)')
  const preferredOption = preferred ? options.filter({ hasText: preferred }).first() : options.first()
  await expect(preferredOption).toBeVisible()
  await preferredOption.click()
}

async function chooseDetectionProject(page: Page, drawer: Locator) {
  const search = drawer.getByPlaceholder('搜索目录或检测项目')
  await search.fill('抗压强度')
  await search.press('Enter')
  await waitForIdle(page)
  const projects = drawer.locator('.commission-tree-node.project')
  for (let depth = 0; depth < 8 && await projects.count() === 0; depth += 1) {
    const collapsed = drawer.locator('.commission-catalog-pane .el-tree-node__expand-icon:not(.is-leaf):not(.expanded)').first()
    await expect(collapsed, '检索结果必须能够沿树路径展开到检测项目').toBeVisible()
    await collapsed.click()
  }
  await expect(projects.first()).toBeVisible()
  await projects.first().click()
  await expect(drawer.locator('.selected-project-tabs button').first()).toBeVisible()
}

async function fillRequiredSampleFields(drawer: Locator) {
  const required = drawer.locator('.commission-sample-pane .el-form-item.is-required')
  for (let index = 0; index < await required.count(); index += 1) {
    const item = required.nth(index)
    const input = item.locator('input:not([type="checkbox"]):not([type="radio"])').first()
    if (await input.isVisible().catch(() => false) && !(await input.inputValue()).trim()) {
      await input.fill('生产验收')
    }
  }
}

async function flowRow(page: Page) {
  const row = page.locator('.el-tab-pane:visible .el-table__body-wrapper tbody tr').first()
  await expect(row).toBeVisible()
  return row
}

async function dropdownAction(page: Page, buttonName: string, actionName: string) {
  const row = await flowRow(page)
  await row.getByRole('button', { name: buttonName, exact: true }).click()
  const menu = page.locator('.el-dropdown__popper:visible')
  await expect(menu).toBeVisible()
  await menu.getByText(actionName, { exact: true }).click()
  await waitForIdle(page)
}

async function headerAction(page: Page, actionName: string) {
  const button = page.locator('.flow-actions').getByRole('button', { name: actionName, exact: true })
  await expect(button).toBeVisible()
  await button.click()
  await waitForIdle(page)
}

async function openFlow(page: Page, flowPath: string, tab: string) {
  await page.goto(`${flowPath}?tab=${tab}`)
  await waitForIdle(page)
  await expect(page.locator('.flow-page')).toBeVisible()
}

async function expectWorkbenchStage(page: Page, commissionNumber: string, stageLabel: string) {
  await page.goto('/')
  await waitForIdle(page)
  const keyword = page.locator('input[placeholder*="工程"][placeholder*="客户"]').first()
  await keyword.fill(commissionNumber)
  await keyword.press('Enter')
  await waitForIdle(page)
  await expect(page.getByText(commissionNumber, { exact: false }).first()).toBeVisible()
  const stage = page.locator('.stage-count-grid button').filter({ hasText: stageLabel })
  await expect(stage).toHaveCount(1)
  await expect(stage).toContainText(/待处理\s+[1-9]\d*\s+项/)
}

async function reportAction(page: Page, actionName: string) {
  await dropdownAction(page, '执行动作', actionName)
}

async function closeContexts(contexts: BrowserContext[]) {
  await Promise.all(contexts.map((context) => context.close()))
}

async function acceptMessageBox(page: Page) {
  const box = page.locator('.el-message-box:visible')
  await expect(box).toBeVisible()
  await box.getByRole('button', { name: '确定', exact: true }).click()
}

async function completePasswordPrompt(page: Page, password: string) {
  const prompt = page.locator('.el-message-box:visible').filter({ hasText: '高风险操作密码复核' })
  const appeared = await prompt.waitFor({ state: 'visible', timeout: 3_000 })
    .then(() => true)
    .catch(() => false)
  if (!appeared) return
  await prompt.locator('input').fill(password)
  await prompt.getByRole('button', { name: '确定', exact: true }).click()
  await expect(prompt).toBeHidden()
}

async function ensureSigningAsset(page: Page, password: string, kind: 'signature' | 'seal') {
  const signature = kind === 'signature'
  const path = signature ? '/modules/person-signatures' : '/modules/institution-seals'
  const title = signature ? '人员签章' : '机构签章'
  const name = signature ? '沈砚清人员签名' : '机构检测专用章'
  await page.goto(path)
  await waitForIdle(page)
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  const search = page.getByPlaceholder(signature ? '人员或签名名称' : '印章名称')
  await search.fill(name)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await waitForIdle(page)
  await expect(page.locator('.table-footer')).toBeVisible()

  const assetRow = () => page.locator('.signing-assets-table .el-table__body-wrapper tbody tr').filter({ hasText: name }).first()
  const assetRows = () => page.locator('.signing-assets-table .el-table__body-wrapper tbody tr').filter({ hasText: name })
  let row = assetRow()
  for (let index = 0; index < await assetRows().count(); index += 1) {
    const candidate = assetRows().nth(index)
    if (await candidate.getByText('已启用', { exact: true }).isVisible().catch(() => false)) return
  }
  if (await row.count() === 0) {
    await page.getByRole('button', { name: signature ? '新增签名' : '新增印章', exact: true }).click()
    const drawer = page.locator('.el-drawer:visible')
    await expect(drawer).toBeVisible()
    await formItem(drawer, '素材名称').locator('input').fill(name)
    if (signature) {
      await selectFirstOption(page, formItem(drawer, '所属人员'), /沈砚清/)
    } else {
      await formItem(drawer, '工程类型').locator('input').fill('水利工程')
      await selectFirstOption(page, formItem(drawer, '印章类型'), /检测专用章/)
    }
    await drawer.getByLabel('选择签章图片').setInputFiles({
      name: signature ? 'controlled-signature.png' : 'controlled-seal.png',
      mimeType: 'image/png',
      buffer: controlledImage,
    })
    await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
    await page.locator('.el-message-box:visible').waitFor({ state: 'visible', timeout: 2_000 }).catch(() => {})
    await completePasswordPrompt(page, password)
    await expect(drawer).toBeHidden()
    await waitForIdle(page)
    row = assetRow()
  }

  await expect(row).toBeVisible()
  if (await row.getByText('已启用', { exact: true }).isVisible().catch(() => false)) return

  if (await row.getByRole('button', { name: '认证通过', exact: true }).isVisible().catch(() => false)) {
    await row.getByRole('button', { name: '认证通过', exact: true }).click()
    await acceptMessageBox(page)
    await waitForIdle(page)
    await expect.poll(async () => {
      row = assetRow()
      if (await row.getByText('已启用', { exact: true }).isVisible().catch(() => false)) return 'active'
      if (await row.getByRole('button', { name: '启用', exact: true }).isVisible().catch(() => false)) return 'activatable'
      return 'updating'
    }, { message: `${name} 认证后必须进入可启用状态` }).not.toBe('updating')
  }

  row = assetRow()
  if (!await row.getByText('已启用', { exact: true }).isVisible().catch(() => false)) {
    const activate = row.getByRole('button', { name: '启用', exact: true })
    await expect(activate, `${name} 必须提供启用动作`).toBeVisible()
    await activate.click()
    await acceptMessageBox(page)
    await waitForIdle(page)
    await expect.poll(async () => {
      row = assetRow()
      return row.getByText('已启用', { exact: true }).isVisible().catch(() => false)
    }, { message: `${name} 启用后必须显示已启用` }).toBe(true)
  }
}

async function ensureSignatureScheme(page: Page, password: string) {
  const name = '报告签发受控方案'
  const schemeRow = () => page.locator('.signing-page .el-table__body-wrapper tbody tr').filter({ hasText: name }).first()
  const schemeRows = () => page.locator('.signing-page .el-table__body-wrapper tbody tr').filter({ hasText: name })
  const hasActiveScheme = async () => {
    for (let index = 0; index < await schemeRows().count(); index += 1) {
      if (await schemeRows().nth(index).locator('.el-tag').getByText('启用', { exact: true }).isVisible().catch(() => false)) {
        return true
      }
    }
    return false
  }
  await page.goto('/modules/signature-schemes')
  await waitForIdle(page)
  await expect(page.getByRole('heading', { name: '签章方案' })).toBeVisible()

  if (await hasActiveScheme()) return

  let row = schemeRow()
  if (await row.count() === 0) {
    await page.getByRole('button', { name: '新增方案', exact: true }).click()
    const drawer = page.locator('.el-drawer:visible')
    await expect(drawer).toBeVisible()
    await formItem(drawer, '方案名称').locator('input').fill(name)
    await drawer.getByRole('button', { name: '添加人员签名', exact: true }).click()
    await drawer.getByRole('button', { name: '添加机构章', exact: true }).click()
    await expect(drawer.locator('.position-card')).toHaveCount(2)
    await drawer.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect(drawer).toBeHidden()
    await waitForIdle(page)
    row = schemeRow()
  }

  if (await hasActiveScheme()) return
  await expect(row).toBeVisible()
  if (await row.getByText('启用', { exact: true }).first().isVisible().catch(() => false)
      && !await row.locator('.el-tag').getByText('启用', { exact: true }).isVisible().catch(() => false)) {
    await row.getByRole('button', { name: '启用', exact: true }).click()
    await acceptMessageBox(page)
    await completePasswordPrompt(page, password)
    await waitForIdle(page)
    row = schemeRow()
  }
  await expect(row.locator('.el-tag').getByText('启用', { exact: true }), '受控签章方案必须处于启用状态').toBeVisible()
}

test('complete business lifecycle is operated through the visible UI', async ({ browser }) => {
  test.skip(!bootstrapPassword || !adminPassword || !rolePassword, '验收密码由隔离环境提供')
  test.setTimeout(6 * 60_000)

  const contexts: BrowserContext[] = []
  const adminContext = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } })
  contexts.push(adminContext)
  const admin = await adminContext.newPage()
  admin.setDefaultTimeout(8_000)
  admin.setDefaultNavigationTimeout(15_000)
  await prepareAdmin(admin)
  await ensureReviewerCanConfirmTests(admin)
  await ensureSigningAsset(admin, adminPassword!, 'signature')
  await ensureSigningAsset(admin, adminPassword!, 'seal')
  await ensureSignatureScheme(admin, adminPassword!)

  await admin.goto('/modules/commissions')
  await waitForIdle(admin)
  await admin.getByRole('button', { name: '新建委托', exact: true }).click()
  const drawer = admin.locator('.commission-acceptance-drawer:visible')
  await expect(drawer).toBeVisible()
  await selectFirstOption(admin, formItem(drawer, '工程'), /GC-2026-0142/)
  await expect(formItem(drawer, '联系人').locator('input')).not.toHaveValue('')
  await chooseDetectionProject(admin, drawer)
  await fillRequiredSampleFields(drawer)
  await drawer.getByRole('button', { name: '保存并进入流程', exact: true }).click()
  await expect(admin).toHaveURL(/\/flow\/[0-9a-f-]+$/)
  await waitForIdle(admin)
  const flowPath = new URL(admin.url()).pathname
  const commissionNumber = (await admin.locator('.flow-title h1').innerText()).split(' · ')[0]
  expect(commissionNumber).toMatch(/^WT-/)

  await headerAction(admin, '提交')
  await headerAction(admin, '受理')
  await headerAction(admin, '开始样品接收')
  await dropdownAction(admin, '状态操作', '接收')
  await dropdownAction(admin, '状态操作', '交接')
  await headerAction(admin, '完成样品阶段')

  await admin.getByRole('tab', { name: '任务分配' }).click()
  await admin.getByRole('button', { name: '创建任务', exact: true }).click()
  const taskDialog = admin.getByRole('dialog', { name: '创建冻结参数检测任务' })
  await expect(taskDialog).toBeVisible()
  await taskDialog.getByRole('button', { name: '保存', exact: true }).click()
  await waitForIdle(admin)
  await dropdownAction(admin, '执行动作', '分配')
  const assignment = admin.getByRole('dialog', { name: '分配' })
  await selectFirstOption(admin, formItem(assignment, '检测人员'), /JC001|顾明远/)
  await assignment.getByRole('button', { name: '确认', exact: true }).click()
  await waitForIdle(admin)
  await headerAction(admin, '开始检测')
  await expectWorkbenchStage(admin, commissionNumber, '试验检测')

  const tester = await rolePage(browser, 'tester')
  contexts.push(tester.context)
  await openFlow(tester.page, flowPath, 'tasks')
  await dropdownAction(tester.page, '执行动作', '接收')
  await dropdownAction(tester.page, '执行动作', '开始')
  await tester.page.getByRole('tab', { name: '试验检测' }).click()
  await tester.page.getByRole('button', { name: '录入数据', exact: true }).click()
  const testDialog = tester.page.getByRole('dialog', { name: '录入试验数据' })
  await testDialog.getByLabel('破坏荷载（N）').fill('812.4')
  await testDialog.getByLabel('受压面积（mm²）').fill('22500')
  await testDialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  await waitForIdle(tester.page)
  await dropdownAction(tester.page, '执行动作', '提交')
  await expect(await flowRow(tester.page)).toContainText('待复核')

  const reviewer = await rolePage(browser, 'reviewer')
  contexts.push(reviewer.context)
  await openFlow(reviewer.page, flowPath, 'tests')
  await dropdownAction(reviewer.page, '执行动作', '确认数据')
  const confirm = reviewer.page.getByRole('dialog', { name: '确认试验结果' })
  await selectFirstOption(reviewer.page, formItem(confirm, '受控结论'), /合格/)
  await confirm.getByRole('button', { name: '确认', exact: true }).click()
  await waitForIdle(reviewer.page)
  await expect(await flowRow(reviewer.page)).toContainText('已确认')

  await openFlow(admin, flowPath, 'tests')
  await headerAction(admin, '完成检测')
  await admin.getByRole('tab', { name: '报告审核与签发' }).click()
  await admin.getByRole('button', { name: '编制报告', exact: true }).click()
  const reportDialog = admin.getByRole('dialog', { name: '编制报告草稿' })
  await reportDialog.getByLabel('报告标题').fill('混凝土抗压强度试验检测报告')
  await reportDialog.getByLabel('工程概况').fill('澜川水库除险加固工程混凝土质量检测')
  await reportDialog.getByLabel('检测结论').fill('检测结果符合受控检测要求。')
  await reportDialog.getByRole('button', { name: '保存草稿', exact: true }).click()
  await waitForIdle(admin)
  await reportAction(admin, '提交审核')
  await expectWorkbenchStage(admin, commissionNumber, '报告审核')

  await openFlow(reviewer.page, flowPath, 'reports')
  await reportAction(reviewer.page, '审核通过')
  await expect.poll(async () => {
    await reviewer.page.locator('.flow-actions').getByRole('button', { name: '刷新', exact: true }).click()
    await waitForIdle(reviewer.page)
    return reviewer.page.getByRole('button', { name: '受控审核版', exact: true }).count()
  }, { timeout: 60_000, intervals: [1000, 2000, 3000] }).toBe(1)
  await expectWorkbenchStage(admin, commissionNumber, '报告签发')

  const issuer = await rolePage(browser, 'issuer')
  contexts.push(issuer.context)
  await openFlow(issuer.page, flowPath, 'reports')
  await reportAction(issuer.page, '签发')
  const issuePassword = issuer.page.getByRole('dialog', { name: '签发前身份复核' })
  await issuePassword.getByLabel('当前密码').fill(rolePassword!)
  await issuePassword.getByRole('button', { name: '复核并继续', exact: true }).click()
  await waitForIdle(issuer.page)
  await expect(await flowRow(issuer.page)).toContainText('已签发')
  await expectWorkbenchStage(admin, commissionNumber, '报告发放')

  await openFlow(admin, flowPath, 'reports')
  await reportAction(admin, '签章')
  const sealPassword = admin.getByRole('dialog', { name: '签章前身份复核' })
  await sealPassword.getByLabel('当前密码').fill(adminPassword!)
  await sealPassword.getByRole('button', { name: '复核并继续', exact: true }).click()
  await waitForIdle(admin)
  await expect.poll(async () => {
    await admin.locator('.flow-actions').getByRole('button', { name: '刷新', exact: true }).click()
    await waitForIdle(admin)
    return admin.getByRole('button', { name: '受控最终版', exact: true }).count()
  }, { timeout: 60_000, intervals: [1000, 2000, 3000] }).toBe(1)
  await reportAction(admin, '发放')
  const distribution = admin.getByRole('dialog', { name: '受控发放' })
  await distribution.getByLabel('收件人').fill('澜川工程建设有限公司')
  await distribution.getByLabel('副本编号').fill('QA-UI-001')
  await distribution.getByRole('button', { name: '确认发放', exact: true }).click()
  await expect(distribution).toBeHidden()
  await waitForIdle(admin)
  await (await flowRow(admin)).getByRole('button', { name: '登记签收', exact: true }).click()
  const acknowledgement = admin.getByRole('dialog', { name: '确认报告签收' })
  await acknowledgement.locator('textarea, input').last().fill('现场签收人')
  await acknowledgement.getByRole('button', { name: '确定', exact: true }).click()
  await expect(acknowledgement).toBeHidden()
  await waitForIdle(admin)
  await reportAction(admin, '归档')
  await expect(await flowRow(admin)).toContainText('已归档')
  await headerAction(admin, '同步签发状态')
  await headerAction(admin, '归档')

  await admin.goto('/')
  await waitForIdle(admin)
  await expect(admin.getByText(commissionNumber, { exact: false }).first()).toBeVisible()
  await expect(admin.getByRole('heading', { name: '流程工作台' })).toBeVisible()

  await closeContexts(contexts)
})
