import { describe, expect, it } from 'vitest'
import { moduleLabel, navigation } from '../src/navigation'

describe('mandatory navigation', () => {
  it('contains every mandatory red-box module without duplicate keys', () => {
    const items = navigation.flatMap((domain) => domain.groups).flatMap((group) => group.items)
    expect(new Set(items.map((item) => item.key)).size).toBe(items.length)
    expect(items.map((item) => item.label)).toEqual(
      expect.arrayContaining(['工程信息', '委托管理', '任务分配', '试验检测', '报告审核', '报告签发']),
    )
    expect(moduleLabel('equipment-certificates')).toBe('设备检定')
  })
})
