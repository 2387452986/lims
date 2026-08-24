import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

interface Manifest {
  list: { columns: string[]; actions: string[]; pageSize: number }
  recipientSelector: { filters: string[]; columns: string[] }
  eventTypes: Array<{ sourceKey: number; label: string; selectableRecipientLabel: string }>
}

describe('notification reference contract', () => {
  const manifest = JSON.parse(readFileSync(new URL(
    '../../docs/reference-manifests/showcase_catalog-notification-rule-types.json', import.meta.url,
  ), 'utf8')) as Manifest

  it('preserves every visible message type and selector contract', () => {
    expect(manifest.list.columns).toEqual(['消息类型', '条件限制', '通知人员', '其他通知人员', '操作'])
    expect(manifest.list.actions).toEqual(['添加', '修改', '删除'])
    expect(manifest.list.pageSize).toBe(20)
    expect(manifest.eventTypes.map(item => item.sourceKey)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    expect(manifest.eventTypes.map(item => item.label)).toEqual([
      '委托单待提交流转', '任务单待派发', '任务单待接收', '任务单待检测', '报告待编制',
      '报告待校核', '报告待审核', '报告待签发', '设备检定', '样品留样清理',
    ])
    expect(manifest.eventTypes.filter(item => item.selectableRecipientLabel === '通知人员')).toHaveLength(3)
    expect(manifest.eventTypes.filter(item => item.selectableRecipientLabel === '其他通知人员')).toHaveLength(7)
    expect(manifest.recipientSelector.filters).toEqual(['申报状态', '人员类型', '姓名', '职位'])
    expect(manifest.recipientSelector.columns).toContain('身份证号码')
  })
})
