import { readdirSync, readFileSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const sourceRoot = resolve(import.meta.dirname, '../src')

function sourceFiles(directory = sourceRoot): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    return entry.isDirectory() ? sourceFiles(path) : ['.vue', '.css'].includes(extname(path)) ? [path] : []
  })
}

const centeredColumnLabels = new Set([
  '操作', '状态', '结论', '严重度', '启用', '认证/启用', '认证状态', '启用状态',
  '评定状态', '提交状态', '处理状态', '任务状态', '报告状态', '委托状态', '流转状态',
  '版本 / 状态', '状态 / 版本', '状态/版本', '状态 / 证书',
])

describe('UI geometry contract', () => {
  it('centers every semantic status and action table column', () => {
    const misses: string[] = []
    for (const file of sourceFiles().filter((path) => extname(path) === '.vue')) {
      const source = readFileSync(file, 'utf8')
      for (const match of source.matchAll(/<el-table-column\b[^>]*\blabel="([^"]+)"[^>]*>/g)) {
        if (centeredColumnLabels.has(match[1]) && !/\balign="center"/.test(match[0])) {
          misses.push(`${file.replace(`${sourceRoot}/`, '')}: ${match[1]}`)
        }
      }
    }
    expect(misses).toEqual([])
  })

  it('keeps minimum interactive typography and the permission-card centering rules', () => {
    const undersized: string[] = []
    for (const file of sourceFiles()) {
      const source = readFileSync(file, 'utf8')
      if (/font-size\s*:\s*(10|11)px/.test(source)) undersized.push(file.replace(`${sourceRoot}/`, ''))
    }
    expect(undersized).toEqual([])

    const accessControl = readFileSync(join(sourceRoot, 'views/AccessControlView.vue'), 'utf8')
    expect(accessControl).toMatch(/\.role-list>button\{[^}]*align-items:center/)
    expect(accessControl).toMatch(/\.account-identity\s*>\s*\.el-avatar\{[^}]*align-items:center;justify-content:center/)
    expect(accessControl).toMatch(/\.account-copy,\.account-identity strong,\.account-identity small\{display:block/)
    expect(accessControl).not.toMatch(/\.account-identity>span/)
  })
})
