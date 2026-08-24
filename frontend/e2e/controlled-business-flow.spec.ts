import { randomUUID } from 'node:crypto'
import { expect, test, type Browser, type Page } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8080'
const bootstrapPassword = process.env.E2E_BOOTSTRAP_PASSWORD
const adminPassword = process.env.E2E_ADMIN_PASSWORD
const rolePassword = process.env.E2E_ROLE_PASSWORD

type Json = Record<string, any>

function shanghaiDate(offsetDays = 0) {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(Date.now() + offsetDays * 86_400_000))
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${value.year}-${value.month}-${value.day}`
}

async function login(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('账号').fill(username)
  await page.getByLabel('密码').fill(password)
  const response = page.waitForResponse((candidate) => candidate.url().endsWith('/api/auth/login'))
  await page.getByRole('button', { name: '登录' }).click()
  return response
}

async function prepareAdmin(page: Page) {
  if (!bootstrapPassword || !adminPassword) throw new Error('管理员 E2E 密码未配置')

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
  const change = page.waitForResponse((candidate) => candidate.url().endsWith('/api/auth/change-password'))
  await page.getByRole('button', { name: '保存并重新登录' }).click()
  expect((await change).status()).toBe(204)

  const relogin = await login(page, 'admin', adminPassword)
  expect(relogin.status()).toBe(200)
  await expect(page.getByRole('heading', { name: '流程工作台' })).toBeVisible()
}

async function loginRole(browser: Browser, username: string) {
  if (!rolePassword) throw new Error('角色 E2E 密码未配置')
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1024 } })
  const page = await context.newPage()
  const response = await login(page, username, rolePassword)
  expect(response.status()).toBe(200)
  await expect(page.getByRole('heading', { name: '流程工作台' })).toBeVisible()
  return { context, page }
}

async function request(page: Page, method: string, path: string, data?: unknown) {
  await page.context().request.get('/api/auth/me')
  const csrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')
  const headers: Record<string, string> = {}
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    if (!csrf) throw new Error(`CSRF cookie missing before ${method} ${path}`)
    headers['X-XSRF-TOKEN'] = decodeURIComponent(csrf.value)
    headers['Idempotency-Key'] = randomUUID()
  }
  const response = await page.context().request.fetch(path, { method, headers, data })
  const text = await response.text()
  const body = text ? JSON.parse(text) : undefined
  return { response, body }
}

async function ok<T extends Json>(page: Page, method: string, path: string, data?: unknown): Promise<T> {
  const result = await request(page, method, path, data)
  if (!result.response.ok()) {
    throw new Error(`${method} ${path} -> ${result.response.status()} ${JSON.stringify(result.body)}`)
  }
  return result.body as T
}

async function transition(page: Page, resource: string, id: string, action: string, expectedVersion: number) {
  return ok<Json>(page, 'POST', `/api/${resource}/${id}/transitions`, {
    action,
    expectedVersion,
    reasonCode: 'E2E-CONTROLLED-FLOW',
    comment: `自动化验收：${action}`,
  })
}

test('role-isolated controlled flow closes from commission through archive and revision', async ({ browser }) => {
  test.skip(!bootstrapPassword || !adminPassword || !rolePassword, 'E2E role credentials are supplied by the test environment')
  test.setTimeout(180_000)

  const adminContext = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1024 } })
  const admin = await adminContext.newPage()
  await prepareAdmin(admin)

  const commissionedOn = shanghaiDate()
  const dueOn = shanghaiDate(21)
  const catalog = await ok<Json[]>(admin, 'GET', '/api/detection-catalog/tree?includeInactive=false&includeHistory=false')
  const flattenCatalog = (nodes: Json[]): Json[] => nodes.flatMap((node) => [node, ...flattenCatalog(node.children ?? [])])
  const projectNode = flattenCatalog(catalog).find((node) => node.type === 'PROJECT' && node.projectRevisionId && node.parameterCount > 0)
  if (!projectNode) throw new Error('No published detection project is available for the controlled flow')
  const projectRevision = await ok<Json>(admin, 'GET', `/api/detection-project-revisions/${projectNode.projectRevisionId}`)
  const parameter = projectRevision.parameters.find((item: Json) => item.status === 'PUBLISHED' && item.enabled)
  if (!parameter) throw new Error('Selected detection project has no published parameter')
  const sampleClientKey = `e2e-${randomUUID()}`
  const details = {
    projectProgress: 'FOUNDATION_FACILITIES', testingType: 'PARALLEL_SUPERVISION',
    testingStandard: 'NATIONAL', testingPlace: 'LAB', sampleHandling: 'CLIENT_DISPOSE',
    reportDelivery: 'PICKUP', reportDeliveryAddress: '', reportCopies: 0,
    projectPart: '', monomer: '', settlementContact: '', witnessUnit: '', witnessName: '',
    witnessPhone: '', witnessCertificate: '', samplingUnit: '', samplingPerson: '', samplingPhone: '',
    samplingCertificate: '', constructionUnit: '', needSampling: false, electronicSignature: false,
    cmaSeal: false, qualificationSeal: false, externalCommission: false, externalOrganization: '',
    externalNumber: '', sampleReceivingAddress: '', expectedReportOn: null, remark: '', sourceRemark: '',
    ageReportValues: [], attachmentFileIds: [],
  }
  const commission = await ok<Json>(admin, 'POST', '/api/commissions', {
    projectId: '00000000-0000-0000-0000-000000000401',
    customerId: '00000000-0000-0000-0000-000000000301',
    contactName: '自动化验收联系人',
    contactPhone: '13800000001',
    commissionedOn,
    dueOn,
    details,
    detectionSelections: [{
      projectRevisionId: projectRevision.id,
      parameterIds: [parameter.id],
      sampleGroups: [{
        clientKey: sampleClientKey,
        name: parameter.sampleName || projectRevision.projectName,
        description: parameter.sampleDescription || '自动化验收样品组',
        quantity: '6',
        unit: parameter.unit || '组',
        parameterIds: [parameter.id],
        fieldValues: {},
      }],
    }],
  })
  expect(commission.state).toBe('DRAFT')
  const selections = await ok<Json[]>(admin, 'GET', `/api/commissions/${commission.id}/detection-selections`)
  const sampleGroup = selections[0]?.sampleGroups?.[0]
  if (!sampleGroup) throw new Error('Commission snapshot did not persist its sample group')

  const tester = await loginRole(browser, 'tester')
  const forbiddenCreate = await request(tester.page, 'POST', '/api/commissions', {
    projectId: '00000000-0000-0000-0000-000000000401',
    customerId: '00000000-0000-0000-0000-000000000301',
    contactName: '越权写入',
    commissionedOn,
    dueOn,
    details,
    detectionSelections: [{
      projectRevisionId: projectRevision.id,
      parameterIds: [parameter.id],
      sampleGroups: [{
        clientKey: `forbidden-${randomUUID()}`, name: parameter.sampleName || projectRevision.projectName,
        description: '越权请求验证', quantity: '1', unit: parameter.unit || '组',
        parameterIds: [parameter.id], fieldValues: {},
      }],
    }],
  })
  expect(forbiddenCreate.response.status()).toBe(403)

  const prematureSample = await request(admin, 'POST', '/api/samples', {
    commissionId: commission.id, sampleGroupId: sampleGroup.id,
    name: '提前登记样品', quantity: '1', unit: '组',
  })
  expect(prematureSample.response.status()).toBe(409)
  expect(prematureSample.body.code).toBe('SAMPLE_COMMISSION_STATE_INVALID')

  let commissionVersion = commission.version
  for (const action of ['SUBMIT', 'ACCEPT', 'START_SAMPLING']) {
    const next = await transition(admin, 'commissions', commission.id, action, commissionVersion)
    commissionVersion = next.version
  }

  const generatedSamples = await ok<Json[]>(admin, 'GET', `/api/samples?commissionId=${commission.id}`)
  const sample = generatedSamples.find((item) => item.sampleGroupId === sampleGroup.id)
  if (!sample) throw new Error('Acceptance did not generate the frozen sample-group record')
  const prematureTask = await request(admin, 'POST', '/api/tasks', {
    commissionId: commission.id,
    sampleId: sample.id,
    parameterId: parameter.id,
    dueAt: `${dueOn}T08:00:00+08:00`,
  })
  expect(prematureTask.response.status()).toBe(422)
  expect(prematureTask.body.code).toBe('TASK_SOURCE_INVALID')
  let sampleVersion = sample.version
  for (const action of ['RECEIVE', 'HANDOVER']) {
    const next = await transition(admin, 'samples', sample.id, action, sampleVersion)
    sampleVersion = next.version
  }

  commissionVersion = (await transition(admin, 'commissions', commission.id, 'COMPLETE_SAMPLING', commissionVersion)).version
  const task = await ok<Json>(admin, 'POST', '/api/tasks', {
    commissionId: commission.id,
    sampleId: sample.id,
    parameterId: parameter.id,
    dueAt: `${dueOn}T08:00:00+08:00`,
  })
  const assigned = await ok<Json>(admin, 'POST', `/api/tasks/${task.id}/transitions`, {
    action: 'ASSIGN',
    expectedVersion: task.version,
    assignedTo: '00000000-0000-0000-0000-000000000201',
    reasonCode: 'E2E-CONTROLLED-FLOW',
    comment: '分配给具备有效资格的检测人员',
  })
  const exceptionTask = await ok<Json>(admin, 'POST', '/api/tasks', {
    commissionId: commission.id,
    sampleId: sample.id,
    parameterId: parameter.id,
    dueAt: `${dueOn}T08:00:00+08:00`,
  })
  const assignedExceptionTask = await ok<Json>(admin, 'POST', `/api/tasks/${exceptionTask.id}/transitions`, {
    action: 'ASSIGN', expectedVersion: exceptionTask.version,
    assignedTo: '00000000-0000-0000-0000-000000000201',
    reasonCode: 'E2E-CONTROLLED-FLOW', comment: '分配设备例外验收任务',
  })
  commissionVersion = (await transition(admin, 'commissions', commission.id, 'START_TESTING', commissionVersion)).version
  sampleVersion = (await transition(admin, 'samples', sample.id, 'START_TEST', sampleVersion)).version

  const prematureReport = await request(admin, 'POST', '/api/reports', {
    commissionId: commission.id, title: '提前编制报告', content: { 检测结果: '未完成' },
  })
  expect(prematureReport.response.status()).toBe(409)
  expect(prematureReport.body.code).toBe('REPORT_COMMISSION_STATE_INVALID')

  let taskVersion = assigned.version
  for (const action of ['ACCEPT', 'START']) {
    const next = await ok<Json>(tester.page, 'POST', `/api/tasks/${task.id}/transitions`, {
      action,
      expectedVersion: taskVersion,
      reasonCode: 'E2E-CONTROLLED-FLOW',
      comment: `检测人员执行 ${action}`,
    })
    taskVersion = next.version
  }

  let exceptionTaskVersion = assignedExceptionTask.version
  for (const action of ['ACCEPT', 'START']) {
    const next = await ok<Json>(tester.page, 'POST', `/api/tasks/${exceptionTask.id}/transitions`, {
      action, expectedVersion: exceptionTaskVersion,
      reasonCode: 'E2E-CONTROLLED-FLOW', comment: `设备例外验收任务执行 ${action}`,
    })
    exceptionTaskVersion = next.version
  }
  const invalidEquipment = await ok<Json>(admin, 'POST', '/api/equipment', {
    code: `E2E-NOCERT-${Date.now()}`, name: '无有效证书验收设备', model: 'E2E',
    serialNumber: randomUUID(), location: '质量验证区', custodianId: '00000000-0000-0000-0000-000000000201',
    primaryEquipment: false, enabled: true, performanceStatus: 'NORMAL', detectionProjectIds: [],
    expectedVersion: 0,
  })
  const blockedRecord = await ok<Json>(tester.page, 'POST', '/api/tests', {
    taskId: exceptionTask.id, equipmentId: invalidEquipment.id, values: { load: '700', area: '22500' },
  })
  const blockedSubmission = await request(tester.page, 'POST', `/api/tests/${blockedRecord.id}/transitions`, {
    action: 'SUBMIT', expectedVersion: blockedRecord.version, comment: '验证设备有效性硬阻断',
  })
  expect(blockedSubmission.response.status()).toBe(422)
  expect(blockedSubmission.body.code).toBe('EQUIPMENT_NOT_VALID')
  const quality = await loginRole(browser, 'quality')
  const exceptionRequest = await ok<Json>(tester.page, 'POST', '/api/exception-approvals', {
    resourceType: 'TEST_RECORD', resourceId: blockedRecord.id, blockerCode: 'EQUIPMENT_NOT_VALID',
    reason: '自动化验收限时例外，不代表生产放行', expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  })
  const approvedException = await ok<Json>(quality.page, 'POST', `/api/exception-approvals/${exceptionRequest.id}/transitions`, {
    action: 'APPROVE', expectedVersion: exceptionRequest.version, comment: '独立质量审批验收',
  })
  expect(approvedException.state).toBe('APPROVED')
  const submittedUnderException = await ok<Json>(tester.page, 'POST', `/api/tests/${blockedRecord.id}/transitions`, {
    action: 'SUBMIT', expectedVersion: blockedRecord.version, comment: '在有效质量例外内提交',
  })
  expect(submittedUnderException.state).toBe('SUBMITTED')
  await ok<Json>(tester.page, 'POST', `/api/tests/${blockedRecord.id}/transitions`, {
    action: 'CONFIRM', expectedVersion: submittedUnderException.version, result: 'CONFORMING',
    comment: '例外任务复核完成',
  })

  const record = await ok<Json>(tester.page, 'POST', '/api/tests', {
    taskId: task.id,
    equipmentId: '00000000-0000-0000-0000-000000000901',
    values: { load: '812.4', area: '22500' },
  })
  expect(record.values.load).toBe('812.4')
  expect(record.values.area).toBe('22500')
  if (parameter.formula) expect(record.values.calculatedResult).toBeDefined()

  const staleUpdate = await request(tester.page, 'PUT', `/api/tests/${record.id}`, {
    equipmentId: record.equipmentId,
    values: record.values,
    expectedVersion: record.version + 99,
  })
  expect(staleUpdate.response.status()).toBe(409)
  expect(staleUpdate.body.currentVersion).toBe(record.version)

  const submitted = await ok<Json>(tester.page, 'POST', `/api/tests/${record.id}/transitions`, {
    action: 'SUBMIT', expectedVersion: record.version, reasonCode: 'E2E-CONTROLLED-FLOW', comment: '提交原始记录',
  })
  const confirmed = await ok<Json>(tester.page, 'POST', `/api/tests/${record.id}/transitions`, {
    action: 'CONFIRM', expectedVersion: submitted.version, result: 'CONFORMING',
    reasonCode: 'E2E-CONTROLLED-FLOW', comment: '复核合格',
  })
  expect(confirmed.state).toBe('CONFIRMED')

  commissionVersion = (await transition(admin, 'commissions', commission.id, 'COMPLETE_TESTING', commissionVersion)).version
  const report = await ok<Json>(admin, 'POST', '/api/reports', {
    commissionId: commission.id,
    title: '混凝土抗压强度试验检测报告',
    content: {
      工程名称: commission.projectName,
      委托编号: commission.number,
      样品编号: sample.number,
      检测项目: '混凝土抗压强度',
      检测结果: '符合设计要求',
    },
  })
  const inReview = await transition(admin, 'reports', report.id, 'SUBMIT_REVIEW', report.version)

  const selfReview = await request(admin, 'POST', `/api/reports/${report.id}/transitions`, {
    action: 'APPROVE', expectedVersion: inReview.version,
    reasonCode: 'E2E-NEGATIVE', comment: '验证编制审核职责分离',
  })
  expect(selfReview.response.status()).toBe(422)
  expect(selfReview.body.blockers).toContain('报告编制人不能审核本人编制的报告')

  const reviewer = await loginRole(browser, 'reviewer')
  const approved = await transition(reviewer.page, 'reports', report.id, 'APPROVE', inReview.version)
  expect(approved.state).toBe('APPROVED')

  await expect.poll(async () => {
    const generated = await ok<Json>(reviewer.page, 'GET', `/api/reports/${report.id}`)
    return generated.pdfFileId
  }, { timeout: 45_000, intervals: [500, 1000, 2000] }).not.toBeNull()

  const issuer = await loginRole(browser, 'issuer')
  const withoutReverification = await request(issuer.page, 'POST', `/api/reports/${report.id}/transitions`, {
    action: 'ISSUE', expectedVersion: approved.version,
    reasonCode: 'E2E-NEGATIVE', comment: '验证近期密码复核阻断',
  })
  expect(withoutReverification.response.status()).toBe(422)
  expect(withoutReverification.body.blockers).toContain('签发前须在5分钟内完成密码复核')
  await ok<Json>(issuer.page, 'POST', '/api/auth/verify-password', { password: rolePassword })
  const issued = await transition(issuer.page, 'reports', report.id, 'ISSUE', approved.version)
  expect(issued.state).toBe('ISSUED')

  const reviewerCannotSeal = await request(reviewer.page, 'POST', `/api/reports/${report.id}/transitions`, {
    action: 'SEAL', expectedVersion: issued.version,
    reasonCode: 'E2E-NEGATIVE', comment: '验证签章权限隔离',
  })
  expect(reviewerCannotSeal.response.status()).toBe(403)

  const sealWithoutReverification = await request(admin, 'POST', `/api/reports/${report.id}/transitions`, {
    action: 'SEAL', expectedVersion: issued.version,
    reasonCode: 'E2E-NEGATIVE', comment: '验证签章密码复核阻断',
  })
  expect(sealWithoutReverification.response.status()).toBe(422)
  expect(sealWithoutReverification.body.blockers).toContain('签章前须在5分钟内完成密码复核')
  await ok<Json>(admin, 'POST', '/api/auth/verify-password', { password: adminPassword })
  const sealed = await transition(admin, 'reports', report.id, 'SEAL', issued.version)
  await expect.poll(async () => {
    const finalized = await ok<Json>(admin, 'GET', `/api/reports/${report.id}`)
    return finalized.pdfFinalized
  }, { timeout: 45_000, intervals: [500, 1000, 2000] }).toBe(true)
  const distributed = await ok<Json>(admin, 'POST', `/api/reports/${report.id}/distributions`, {
    recipient: '澜川工程建设有限公司',
    method: 'CONTROLLED_COPY',
    copyNumber: 'COPY-001',
    expectedVersion: sealed.version,
  })
  const prematureArchive = await request(admin, 'POST', `/api/reports/${report.id}/transitions`, {
    action: 'ARCHIVE', expectedVersion: distributed.version,
    reasonCode: 'E2E-NEGATIVE', comment: '验证签收前归档阻断',
  })
  expect(prematureArchive.response.status()).toBe(422)
  expect(prematureArchive.body.blockers).toContain('存在未签收的受控发放记录：1')
  const distribution = (await ok<Json[]>(admin, 'GET', `/api/reports/${report.id}/distributions`))[0]
  await ok<Json>(admin, 'POST', `/api/reports/${report.id}/distributions/${distribution.id}/acknowledgements`, {
    acknowledgedBy: '自动化验收签收人', expectedVersion: distribution.version,
  })
  const archived = await transition(admin, 'reports', report.id, 'ARCHIVE', distributed.version)
  expect(archived.state).toBe('ARCHIVED')

  commissionVersion = (await transition(admin, 'commissions', commission.id, 'MARK_ISSUED', commissionVersion)).version
  const archivedCommission = await transition(admin, 'commissions', commission.id, 'ARCHIVE', commissionVersion)
  expect(archivedCommission.state).toBe('ARCHIVED')

  const revised = await ok<Json>(admin, 'POST', `/api/reports/${report.id}/revisions`, {
    expectedVersion: archived.version,
    reasonCode: 'CLIENT_CORRECTION',
    comment: '客户名称规范化，发起受控修订',
  })
  expect(revised.number).toBe(report.number)
  expect(revised.revisionLabel).toBe('R1')
  expect(revised.state).toBe('DRAFT')

  const nonconformance = await ok<Json>(admin, 'POST', '/api/nonconformances', {
    sourceType: 'SAMPLE', sourceId: sample.id, commissionId: commission.id,
    severity: 'HIGH', description: '自动化验收：样品外观存在受控偏差',
  })
  let nonconformanceVersion = nonconformance.version
  for (const action of ['CONFIRM', 'START_DISPOSITION']) {
    const next = await transition(quality.page, 'nonconformances', nonconformance.id, action, nonconformanceVersion)
    nonconformanceVersion = next.version
  }
  const correctiveAction = await ok<Json>(quality.page, 'POST', `/api/nonconformances/${nonconformance.id}/actions`, {
    actionType: 'CORRECTIVE', description: '复核样品接收记录并补充偏差处置证据',
    ownerId: '00000000-0000-0000-0000-000000000201', dueOn,
  })
  await ok<Json>(tester.page, 'POST', `/api/nonconformances/actions/${correctiveAction.id}/transitions`, {
    action: 'START', comment: '责任人开始执行', evidence: [],
  })
  const completedAction = await ok<Json>(tester.page, 'POST', `/api/nonconformances/actions/${correctiveAction.id}/transitions`, {
    action: 'COMPLETE', comment: '责任人提交处置证据', evidence: ['E2E-CAPA-EVIDENCE-001'],
  })
  expect(completedAction.state).toBe('COMPLETED')
  nonconformanceVersion = (await transition(quality.page, 'nonconformances', nonconformance.id,
    'SUBMIT_VERIFICATION', nonconformanceVersion)).version
  const verifiedAction = await ok<Json>(quality.page, 'POST', `/api/nonconformances/actions/${correctiveAction.id}/transitions`, {
    action: 'VERIFY', comment: '质量负责人独立验证通过', evidence: [],
  })
  expect(verifiedAction.state).toBe('VERIFIED')
  const closedNonconformance = await transition(quality.page, 'nonconformances', nonconformance.id,
    'CLOSE', nonconformanceVersion)
  expect(closedNonconformance.state).toBe('CLOSED')

  const changeRequest = await ok<Json>(tester.page, 'POST', '/api/data-change-requests', {
    testRecordId: record.id, reasonCode: 'E2E_CORRECTION', reason: '自动化验收检测数据受控修订',
    equipmentId: record.equipmentId, values: { load: '813.0', area: '22500' },
  })
  const approvedChange = await ok<Json>(quality.page, 'POST', `/api/data-change-requests/${changeRequest.id}/transitions`, {
    action: 'APPROVE', expectedVersion: changeRequest.version, comment: '独立复核修改内容有效',
  })
  const replacementRecord = await ok<Json>(tester.page, 'POST', `/api/data-change-requests/${changeRequest.id}/apply`, {
    expectedVersion: approvedChange.version,
  })
  expect(replacementRecord.state).toBe('DRAFT')
  expect(replacementRecord.revision).toBe(1)
  expect((await ok<Json>(tester.page, 'GET', `/api/tests/${record.id}`)).state).toBe('SUPERSEDED')

  const replacementSubmitted = await ok<Json>(tester.page, 'POST', `/api/tests/${replacementRecord.id}/transitions`, {
    action: 'SUBMIT', expectedVersion: replacementRecord.version,
    reasonCode: 'E2E_CORRECTION', comment: '提交经批准的修订检测记录',
  })
  const replacementConfirmed = await ok<Json>(tester.page, 'POST', `/api/tests/${replacementRecord.id}/transitions`, {
    action: 'CONFIRM', expectedVersion: replacementSubmitted.version, result: 'CONFORMING',
    reasonCode: 'E2E_CORRECTION', comment: '独立复核修订检测记录',
  })
  expect(replacementConfirmed.state).toBe('CONFIRMED')

  const revisedInReview = await transition(admin, 'reports', report.id, 'SUBMIT_REVIEW', revised.version)
  const revisedApproved = await transition(reviewer.page, 'reports', report.id, 'APPROVE', revisedInReview.version)
  await expect.poll(async () => {
    const generated = await ok<Json>(reviewer.page, 'GET', `/api/reports/${report.id}`)
    return generated.pdfFileId
  }, { timeout: 45_000, intervals: [500, 1000, 2000] }).not.toBeNull()
  await ok<Json>(issuer.page, 'POST', '/api/auth/verify-password', { password: rolePassword })
  const revisedIssued = await transition(issuer.page, 'reports', report.id, 'ISSUE', revisedApproved.version)
  await ok<Json>(admin, 'POST', '/api/auth/verify-password', { password: adminPassword })
  const revisedSealed = await transition(admin, 'reports', report.id, 'SEAL', revisedIssued.version)
  await expect.poll(async () => {
    const finalized = await ok<Json>(admin, 'GET', `/api/reports/${report.id}`)
    return finalized.pdfFinalized
  }, { timeout: 45_000, intervals: [500, 1000, 2000] }).toBe(true)
  const revisedDistributed = await ok<Json>(admin, 'POST', `/api/reports/${report.id}/distributions`, {
    recipient: '澜川工程建设有限公司', method: 'CONTROLLED_COPY', copyNumber: 'COPY-R1-001',
    expectedVersion: revisedSealed.version,
  })
  const revisedDistribution = (await ok<Json[]>(admin, 'GET', `/api/reports/${report.id}/distributions`))[0]
  await ok<Json>(admin, 'POST', `/api/reports/${report.id}/distributions/${revisedDistribution.id}/acknowledgements`, {
    acknowledgedBy: '自动化验收签收人', expectedVersion: revisedDistribution.version,
  })
  const revisedArchived = await transition(admin, 'reports', report.id, 'ARCHIVE', revisedDistributed.version)
  expect(revisedArchived.state).toBe('ARCHIVED')
  expect((await ok<Json>(admin, 'GET', `/api/reports/${report.id}`)).revisionLabel).toBe('R1')

  const reportLedger = await ok<Json>(admin, 'GET', `/api/reports/queue?view=REPORT_LEDGER&q=${report.number}`)
  const revisions = reportLedger.items.filter((row: Json) => row.reportNumber === report.number).map((row: Json) => row.revisionLabel)
  expect(revisions).toEqual(expect.arrayContaining(['R0', 'R1']))

  const dictionary = await ok<Json>(admin, 'POST', '/api/data-dictionaries', {
    name: `验收字典-${Date.now()}`, allowsChildren: false,
  })
  const updatedDictionary = await ok<Json>(admin, 'PUT', `/api/data-dictionary-revisions/${dictionary.revisionId}`, {
    name: dictionary.name, allowsChildren: false, expectedVersion: dictionary.version,
    values: [{ sortOrder: 1, value: '受控值', remark: '版本化字典验收' }],
  })
  const publishedDictionary = await transition(admin, 'data-dictionary-revisions', dictionary.revisionId,
    'PUBLISH', updatedDictionary.version)
  expect(publishedDictionary.state).toBe('PUBLISHED')
  const dictionaryRevision = await ok<Json>(admin, 'POST', `/api/data-dictionaries/${dictionary.id}/revisions`)
  expect(dictionaryRevision.state).toBe('DRAFT')
  expect(dictionaryRevision.revisionNumber).toBe(dictionary.revisionNumber + 1)

  for (const type of [
    'REPORT_TEMPLATE', 'COMMISSION_TEMPLATE', 'TASK_TEMPLATE',
    'NOTIFICATION', 'NUMBERING_RULE', 'TEMPLATE_NUMBERING',
  ]) {
    const code = `E2E-${type}-${Date.now()}-${randomUUID().slice(0, 8)}`
    const draft = await ok<Json>(admin, 'POST', '/api/configurations', {
      type, code, name: `自动化验收 ${type}`, content: { source: 'controlled-e2e', enabled: true },
    })
    const published = await transition(admin, 'configurations', draft.id, 'PUBLISH', draft.version)
    expect(published.state).toBe('PUBLISHED')
    const revisionDraft = await ok<Json>(admin, 'POST', `/api/configurations/${draft.id}/revisions`)
    expect(revisionDraft.state).toBe('DRAFT')
    expect(revisionDraft.versionNumber).toBe(draft.versionNumber + 1)
  }

  const sampleExportRequest = { view: 'SAMPLE_LEDGER', q: commission.number, state: '', from: null, to: null }
  const testerExport = await request(tester.page, 'POST', '/api/samples/exports', sampleExportRequest)
  expect(testerExport.response.status()).toBe(403)
  const queuedExport = await ok<Json>(admin, 'POST', '/api/samples/exports', sampleExportRequest)
  expect(queuedExport.state).toBe('READY')
  await expect.poll(async () => {
    const jobs = await ok<Json[]>(admin, 'GET', '/api/jobs?resourceType=SAMPLE_LEDGER&limit=10')
    return jobs.find((job) => job.id === queuedExport.jobId)?.state
  }, { timeout: 30_000, intervals: [500, 1000, 2000] }).toBe('COMPLETED')
  const exportJobs = await ok<Json[]>(admin, 'GET', '/api/jobs?resourceType=SAMPLE_LEDGER&limit=10')
  const completedExport = exportJobs.find((job) => job.id === queuedExport.jobId)
  expect(completedExport?.resultFileId).toBeTruthy()
  const adminExport = await admin.context().request.get(`/api/files/${completedExport.resultFileId}`)
  expect(adminExport.status()).toBe(200)
  expect(adminExport.headers()['content-type']).toContain('spreadsheetml')

  await quality.page.goto('/modules/nonconforming-samples')
  await expect(quality.page.getByRole('heading', { name: '质量闭环中心' })).toBeVisible()
  await expect(quality.page.getByText(nonconformance.number)).toBeVisible()

  const audit = await ok<Json>(admin, 'GET', '/api/audit-events?resourceType=REPORT&limit=100')
  const reportActions = audit.items.filter((event: Json) => event.resourceId === report.id).map((event: Json) => event.action)
  expect(reportActions).toEqual(expect.arrayContaining([
    'REPORT_CREATED', 'REPORT_APPROVE', 'REPORT_ISSUE', 'REPORT_SEAL',
    'REPORT_DISTRIBUTED', 'REPORT_ARCHIVE', 'REPORT_REVISION_CREATED',
  ]))
  expect(reportActions.filter((action) => action === 'REPORT_ARCHIVE')).toHaveLength(2)
  const distributionAudit = await ok<Json>(admin, 'GET', '/api/audit-events?resourceType=REPORT_DISTRIBUTION&limit=100')
  expect(distributionAudit.items.some((event: Json) => event.resourceId === distribution.id
    && event.action === 'REPORT_DISTRIBUTION_ACKNOWLEDGED')).toBe(true)
  expect(distributionAudit.items.some((event: Json) => event.resourceId === revisedDistribution.id
    && event.action === 'REPORT_DISTRIBUTION_ACKNOWLEDGED')).toBe(true)

  await admin.goto(`/flow/${commission.id}`)
  await expect(admin.getByText(commission.number, { exact: false }).first()).toBeVisible()
  const overflow = await admin.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
  await admin.keyboard.press('Tab')
  await expect(admin.locator(':focus')).toBeVisible()

  await Promise.all([tester.context.close(), reviewer.context.close(), issuer.context.close(), quality.context.close(), adminContext.close()])
})
