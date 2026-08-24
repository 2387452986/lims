import { describe, expect, it } from 'vitest'
import { compatibleRandomUuid, installRandomUuidCompatibility } from '../src/browser-compat'

describe('LAN HTTP browser compatibility', () => {
  it('creates an RFC 4122 v4 id when randomUUID is unavailable', () => {
    const cryptoApi = {
      getRandomValues(bytes: Uint8Array) {
        bytes.forEach((_, index) => { bytes[index] = index })
        return bytes
      },
    } as Crypto
    expect(compatibleRandomUuid(cryptoApi)).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f')
  })

  it('installs one compatibility method without replacing a native implementation', () => {
    const fallback = { getRandomValues: (bytes: Uint8Array) => bytes.fill(1) } as Crypto
    installRandomUuidCompatibility(fallback)
    expect(fallback.randomUUID()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)

    const native = { getRandomValues: (bytes: Uint8Array) => bytes, randomUUID: () => 'native-id' } as unknown as Crypto
    installRandomUuidCompatibility(native)
    expect(native.randomUUID()).toBe('native-id')
  })
})
