import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

type Manifest = {
  schemaVersion: number
  sourceSystem: string
  numberingRules: { recordCount: number; columns: string[]; actions: string[]; formFields: string[]; ruleTypes: string[]; prefixPositions: string[]; suffixPositions: string[] }
  templateNumbering: { recordCount: number; columns: string[]; actions: string[]; types: string[] }
  auditLog: { filters: string[]; columns: string[]; operationTypes: string[]; pageSizes: number[]; businessOptions: string[] }
}

describe('system numbering and audit reference contract', () => {
  const bytes = readFileSync(new URL('../../docs/reference-manifests/showcase_catalog-numbering-and-audit.json', import.meta.url))
  const manifest = JSON.parse(bytes.toString('utf8')) as Manifest

  it('pins every visible source field, action and enum', () => {
    expect(manifest.schemaVersion).toBe(1)
    expect(manifest.sourceSystem).toBe('showcase_catalog')
    expect(manifest.numberingRules.recordCount).toBe(0)
    expect(manifest.numberingRules.columns).toHaveLength(7)
    expect(manifest.numberingRules.actions).toEqual(['添加', '修改', '删除', '重置流水号'])
    expect(manifest.numberingRules.ruleTypes).toHaveLength(5)
    expect(manifest.numberingRules.prefixPositions).toEqual(['编号前', '流水号前'])
    expect(manifest.numberingRules.suffixPositions).toEqual(['编号后', '日期规则后'])
    expect(manifest.templateNumbering.recordCount).toBe(0)
    expect(manifest.templateNumbering.types).toHaveLength(4)
    expect(manifest.auditLog.operationTypes).toEqual(['登录', '浏览', '新增', '修改', '删除', '退出登录'])
    expect(manifest.auditLog.businessOptions).toHaveLength(83)
    expect(manifest.auditLog.pageSizes).toEqual([10, 20, 50, 100, 150, 200])
  })

  it('removes generic JSON editing and keeps writes idempotent and version checked', () => {
    const router = readFileSync(new URL('../src/views/ModuleRouterView.vue', import.meta.url), 'utf8')
    const numbering = readFileSync(new URL('../src/views/NumberingConfigurationView.vue', import.meta.url), 'utf8')
    const audit = readFileSync(new URL('../src/views/AuditLogView.vue', import.meta.url), 'utf8')
    expect(router).not.toContain('GovernanceView')
    expect(router).toContain('NumberingConfigurationView')
    expect(router).toContain('AuditLogView')
    expect(numbering).not.toContain('受控配置 JSON')
    expect(numbering).toMatch(/numbering-configuration\/rules\/[\s\S]*?Idempotency-Key/)
    expect(numbering).toMatch(/expectedVersion/)
    expect(numbering).toContain('sequence-reset')
    expect(numbering).toContain('/summary')
    expect(numbering).toContain('isRuleRevision')
    expect(numbering).toContain('修订继承已运行序列')
    expect(audit).toContain('/api/audit-events/facets')
    expect(audit).toContain('nextCursor')
  })

  it('records a stable source manifest checksum', () => {
    expect(createHash('sha256').update(bytes).digest('hex'))
      .toBe('1daaebed3044dd8c6276a6b7d1b0c0218fd8a1801404c8fee201923fe2db5ecd')
  })
})
