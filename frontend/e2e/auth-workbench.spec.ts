import { expect, test } from '@playwright/test'

test('login reaches the traceable workflow workbench', async ({ page }) => {
  test.skip(!process.env.E2E_USERNAME || !process.env.E2E_PASSWORD, 'E2E credentials are supplied by the test environment')
  await page.goto('/login')
  await page.getByLabel('账号').fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码').fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '流程工作台' })).toBeVisible()
  await expect(page.getByRole('list', { name: '检测流程' })).toContainText('报告签发')
  await page.getByRole('button', { name: '查看当前阶段' }).click()
  await expect(page.getByRole('dialog')).toContainText('资格校验')
  await page.keyboard.press('Escape')

  const requiredModules = [
    ['projects', '工程信息'], ['commissions', '委托管理'], ['tasks', '任务分配'],
    ['tests', '试验检测'], ['report-review', '报告审核'], ['report-issue', '报告签发'],
    ['people', '人员信息'], ['organizations', '单位信息'], ['departments', '部门配置'],
    ['parameters', '检测参数'], ['equipment', '设备管理'], ['equipment-certificates', '设备检定'],
    ['equipment-service', '设备送检'], ['equipment-borrow', '领用归还'], ['equipment-usage', '使用记录'],
    ['equipment-calibration', '设备校准'], ['equipment-movements', '出入库记录'],
    ['sample-ledger', '样品总台账'], ['test-ledger', '试验台账'], ['report-ledger', '报告台账'],
    ['nonconforming-samples', '质量闭环中心'], ['nonconforming-reports', '质量闭环中心'],
    ['dictionaries', '数据字典'], ['permissions', '权限配置'], ['report-templates', '报告版本管理'],
    ['commission-templates', '委托单版本管理'], ['task-templates', '任务单版本管理'],
    ['notifications', '消息通知配置'], ['numbering-rules', '编号配置规则'], ['template-numbering', '模板编号配置'],
  ] as const

  for (const [key, heading] of requiredModules) {
    await page.goto(key === 'commissions' ? '/modules/commissions' : `/modules/${key}`)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, `${key} should not overflow at 1440×1024`).toBeLessThanOrEqual(1)
    await page.keyboard.press('Tab')
    await expect(page.locator(':focus')).toBeVisible()
  }
})
