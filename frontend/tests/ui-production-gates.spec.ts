import { readdir, readFile } from 'node:fs/promises'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  integrationStateLabel, jobTypeLabel, localDateString, statusLabel, workbenchGreeting,
} from '../src/presentation'

const sourceRoot = fileURLToPath(new URL('../src', import.meta.url))

async function sourceFiles(directory = sourceRoot): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map((entry) => entry.isDirectory()
    ? sourceFiles(join(directory, entry.name))
    : [join(directory, entry.name)]))
  return nested.flat().filter((path) => ['.vue', '.css'].includes(extname(path)))
}

function userTemplate(source: string): string {
  const start = source.indexOf('<template>')
  const end = source.lastIndexOf('</template>')
  return start >= 0 && end > start ? source.slice(start, end) : ''
}

describe('production UI gates', () => {
  it('does not regress to undersized text', async () => {
    const failures: string[] = []
    for (const path of await sourceFiles()) {
      const source = await readFile(path, 'utf8')
      if (/font-size:\s*(?:8|9|10|11|12)px\b/.test(source)) failures.push(path)
    }
    expect(failures).toEqual([])
  })

  it('uses the Asia Shanghai business date instead of UTC slicing', async () => {
    expect(localDateString(new Date('2026-08-22T17:30:00Z'))).toBe('2026-08-23')
    const failures: string[] = []
    for (const path of await sourceFiles()) {
      const source = await readFile(path, 'utf8')
      if (/toISOString\(\)\.slice\(0,\s*10\)/.test(source)) failures.push(path)
    }
    expect(failures).toEqual([])
  })

  it('shows the workbench greeting for the Shanghai morning, midday and evening periods', () => {
    expect(workbenchGreeting(new Date('2026-08-22T21:00:00Z'))).toBe('早上好')
    expect(workbenchGreeting(new Date('2026-08-23T04:00:00Z'))).toBe('中午好')
    expect(workbenchGreeting(new Date('2026-08-23T10:00:00Z'))).toBe('晚上好')
  })

  it('keeps QA and engineering language out of user-facing templates', async () => {
    const forbidden = [
      'SHOWCASE', '自动化验收', '演示数据', '稳定游标', '权限驱动',
      '领域专属记录', '来源校验', '迁移完整性', '虚拟树', '不以默认值冒充',
    ]
    const failures: string[] = []
    for (const path of await sourceFiles()) {
      if (extname(path) !== '.vue') continue
      const source = await readFile(path, 'utf8')
      const template = userTemplate(source)
      for (const phrase of forbidden) if (template.includes(phrase)) failures.push(`${path}: ${phrase}`)
    }
    expect(failures).toEqual([])
  })

  it('requires Chinese presentation mappings instead of raw internal states', async () => {
    expect(statusLabel('DISPOSITION_IN_PROGRESS')).toBe('处置中')
    expect(jobTypeLabel('EXPORT_COMMISSION_XLSX')).toBe('委托数据导出')
    expect(integrationStateLabel('ADAPTER_UNAVAILABLE')).toBe('适配器未就绪')
    expect(() => statusLabel('UNMAPPED_TEST_STATE')).toThrow('状态缺少中文显示映射')

    const failures: string[] = []
    const rawState = /\{\{[^}]*\?\?\s*(?:scope|s)\.row\.(?:state|status|severity)\s*\}\}/
    const rawJob = /\{\{\s*currentJob\.(?:state|jobType)\s*\}\}/
    for (const path of await sourceFiles()) {
      if (extname(path) !== '.vue') continue
      const template = userTemplate(await readFile(path, 'utf8'))
      if (rawState.test(template) || rawJob.test(template)) failures.push(path)
    }
    expect(failures).toEqual([])
  })
})
