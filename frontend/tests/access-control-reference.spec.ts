import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

type Item = { name: string; actions: string[] }
type Group = { name: string; items: Item[] }
type Domain = { name: string; groups: Group[] }
type Client = { sourceKey: string; label: string; selectedInAdmin: boolean; tree: Domain[] }
type RoleClientGrant = { sourceKey: string; label: string; selected: boolean; grants: string[][] }
type Manifest = {
  schemaVersion: number
  sourceSystem: string
  canonicalCounts: {
    pc: { domains: number; groups: number; pages: number; actions: number; nodes: number }
    miniProgram: { domains: number; leaves: number; nodes: number }
    customerPc: { domains: number; leaves: number; nodes: number }
    fieldApp: { domains: number; leaves: number; nodes: number }
    totalNodes: number
  }
  canonicalClients: Client[]
  roleGrantEvidence: Record<string, RoleClientGrant[]>
  sourceDiscrepancies: string[]
}

describe('access-control reference contract', () => {
  const bytes = readFileSync(new URL(
    '../../docs/reference-manifests/showcase_catalog-permission-catalog.json', import.meta.url,
  ))
  const manifest = JSON.parse(bytes.toString('utf8')) as Manifest

  it('pins the complete four-client permission catalog', () => {
    expect(createHash('sha256').update(bytes).digest('hex'))
      .toBe('6d0ff4a0746c2126928d5c2c155f645ef0c9c91f6aecb110053dc4f613d38642')
    expect(manifest.schemaVersion).toBe(1)
    expect(manifest.sourceSystem).toBe('showcase_catalog')
    expect(manifest.canonicalCounts).toEqual({
      pc: { domains: 8, groups: 25, pages: 98, actions: 347, nodes: 478 },
      miniProgram: { domains: 4, leaves: 12, nodes: 16 },
      customerPc: { domains: 2, leaves: 6, nodes: 8 },
      fieldApp: { domains: 3, leaves: 19, nodes: 22 },
      totalNodes: 524,
    })
    expect(manifest.canonicalClients.map(client => [client.sourceKey, client.label])).toEqual([
      ['1', '机构pc管理端'], ['2', '委托客户PC端'], ['4', '微信小程序'], ['6', '现场检测APP'],
    ])
    expect(manifest.canonicalClients.reduce((total, client) => total + client.tree.length, 0)).toBe(17)
  })

  it('retains exact role grant paths and the list-versus-form discrepancy', () => {
    expect(Object.keys(manifest.roleGrantEvidence)).toEqual(['仪器设备管理员', '样品管理员', '检测员', '管理员'])
    const grantCounts = Object.fromEntries(Object.entries(manifest.roleGrantEvidence).map(([role, clients]) => [
      role, clients.map(client => client.grants.length),
    ]))
    expect(grantCounts).toEqual({
      仪器设备管理员: [38, 0, 0, 0],
      样品管理员: [41, 0, 0, 0],
      检测员: [84, 0, 6, 0],
      管理员: [287, 3, 11, 19],
    })
    expect(manifest.roleGrantEvidence.检测员.map(client => client.selected)).toEqual([true, false, true, false])
    expect(manifest.sourceDiscrepancies).toEqual([
      '角色列表未显示仪器设备管理员、样品管理员和检测员的微信小程序，但编辑表单分别显示已选；权限真值采用编辑表单。',
    ])
  })

  it('keeps every access-control write retry-safe and version-checked', () => {
    const source = readFileSync(new URL('../src/views/AccessControlView.vue', import.meta.url), 'utf8')
    expect(source).toMatch(/access-control\/roles\/\$\{editingRoleId\.value\}[\s\S]*?Idempotency-Key/)
    expect(source).toMatch(/access-control\/users\/\$\{selectedUser\.value\.id\}\/roles[\s\S]*?Idempotency-Key[\s\S]*?expectedVersion/)
    expect(source).toMatch(/access-control\/users\/\$\{user\.id\}\/sessions\/revoke[\s\S]*?Idempotency-Key[\s\S]*?expectedVersion: user\.version/)
  })
})
