type CryptoWithOptionalUuid = Pick<Crypto, 'getRandomValues'> & Partial<Pick<Crypto, 'randomUUID'>>

function formatUuid(bytes: Uint8Array): `${string}-${string}-${string}-${string}-${string}` {
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function compatibleRandomUuid(cryptoApi: CryptoWithOptionalUuid = globalThis.crypto): string {
  if (typeof cryptoApi.randomUUID === 'function') return cryptoApi.randomUUID()
  return formatUuid(cryptoApi.getRandomValues(new Uint8Array(16)))
}

export function installRandomUuidCompatibility(cryptoApi: CryptoWithOptionalUuid = globalThis.crypto): void {
  if (typeof cryptoApi.randomUUID === 'function') return
  Object.defineProperty(cryptoApi, 'randomUUID', {
    configurable: true,
    value: () => formatUuid(cryptoApi.getRandomValues(new Uint8Array(16))),
  })
}
