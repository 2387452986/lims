import { expect, test, type Page } from '@playwright/test'
import { navigation } from '../src/navigation'

const configuredViewports = [
  { width: 1180, height: 720 }, { width: 1280, height: 720 }, { width: 1440, height: 900 },
  { width: 1600, height: 1024 }, { width: 1920, height: 1080 },
]
const requestedViewport = process.env.E2E_VIEWPORT
const viewports = requestedViewport
  ? configuredViewports.filter(({ width, height }) => `${width}x${height}` === requestedViewport)
  : configuredViewports
if (requestedViewport && viewports.length === 0) {
  throw new Error(`Unsupported E2E_VIEWPORT: ${requestedViewport}`)
}
const allRoutes = navigation.flatMap((domain) => domain.groups)
  .flatMap((group) => group.items)
  .map((item) => ({ ...item, path: item.key === 'commissions' ? '/modules/commissions' : `/modules/${item.key}` }))
const routeStartIndex = process.env.E2E_ROUTE_START
  ? Math.max(0, allRoutes.findIndex((route) => route.key === process.env.E2E_ROUTE_START)) : 0
const requestedEndIndex = process.env.E2E_ROUTE_END
  ? allRoutes.findIndex((route) => route.key === process.env.E2E_ROUTE_END) : -1
const routes = allRoutes.slice(routeStartIndex, requestedEndIndex < routeStartIndex ? undefined : requestedEndIndex + 1)
const forbiddenInternalText = /\b(?:DISPOSITION_IN_PROGRESS|PENDING_VERIFICATION|QUALITY_CONFIRMED|SUPERSEDED|CRITICAL|DRAFT|SUBMITTED|APPROVED|REJECTED|ARCHIVED|VOID|FAILED|COMPLETED)\b|SHOWCASE|自动化验收/

async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('账号').fill(process.env.E2E_USERNAME!)
  await page.getByLabel('密码').fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('heading', { name: '流程工作台' })).toBeVisible()
}

async function waitForLoading(page: Page) {
  await page.locator('.el-loading-mask:visible').first()
    .waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => {})
}

for (const viewport of viewports) {
  test(`all visible routes satisfy interaction and viewport contracts at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    test.skip(!process.env.E2E_USERNAME || !process.env.E2E_PASSWORD, 'E2E credentials are supplied by the test environment')
    test.setTimeout(8 * 60_000)
    await page.setViewportSize(viewport)
    const runtimeErrors: string[] = []
    page.on('pageerror', (error) => runtimeErrors.push(error.message))
    page.on('console', (message) => {
      if (message.type() === 'error' || message.text().includes('[Vue warn]')) runtimeErrors.push(message.text())
    })
    await login(page)
    runtimeErrors.length = 0

    for (const route of routes) {
      await test.step(route.label, async () => {
        runtimeErrors.length = 0
        await page.goto(route.path)
        await expect(page.locator('h1').first(), `${route.key} must have a visible page heading`).toBeVisible()
        await waitForLoading(page)
        await expect(page.locator('body')).not.toContainText(forbiddenInternalText)
        expect(runtimeErrors, `${route.key} must not emit browser or Vue runtime errors`).toEqual([])

        const geometry = await page.evaluate(() => ({
          horizontalOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
          verticalOverflow: document.documentElement.scrollHeight - document.documentElement.clientHeight,
          undersizedText: Array.from(document.body.querySelectorAll<HTMLElement>('*')).filter((element) => {
            const text = Array.from(element.childNodes).some((node) => node.nodeType === Node.TEXT_NODE && node.textContent?.trim())
            if (!text || element.offsetParent === null) return false
            return Number.parseFloat(getComputedStyle(element).fontSize) < 13
          }).slice(0, 5).map((element) => `${element.tagName}.${element.className}`),
        }))
        expect(geometry.horizontalOverflow, `${route.key} has page-level horizontal overflow`).toBeLessThanOrEqual(1)
        expect(geometry.verticalOverflow, `${route.key} has page-level vertical overflow`).toBeLessThanOrEqual(1)
        expect(geometry.undersizedText, `${route.key} renders text below 13px`).toEqual([])

        const query = page.getByRole('button', { name: '查询', exact: true }).first()
        if (await query.isVisible().catch(() => false) && await query.isEnabled()) {
          await query.click({ timeout: 3_000 })
          await waitForLoading(page)
        }
        const reset = page.getByRole('button', { name: '重置', exact: true }).first()
        if (await reset.isVisible().catch(() => false) && await reset.isEnabled()) {
          await reset.click({ timeout: 3_000 })
          await waitForLoading(page)
        }

        const footer = page.locator('.table-footer:visible').last()
        if (await footer.count()) {
          const box = await footer.boundingBox()
          expect(box, `${route.key} pagination must be rendered`).not.toBeNull()
          expect(box!.y + box!.height, `${route.key} pagination must remain in the viewport`).toBeLessThanOrEqual(viewport.height + 1)
        }
        expect(runtimeErrors, `${route.key} interactions must not emit browser or Vue runtime errors`).toEqual([])
      })
    }
  })
}
