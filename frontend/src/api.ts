import { problemMessage } from './presentation'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message)
  }
}

function cookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((part) => part.startsWith(`${name}=`))
    ?.split('=')
    .slice(1)
    .join('=')
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const csrf = cookie('XSRF-TOKEN')
  if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-XSRF-TOKEN', decodeURIComponent(csrf))

  let response: Response
  try {
    response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  } catch (error) {
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务器，请检查网络后重试', { cause: error })
  }
  if (response.status === 204) return undefined as T
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const code = body.code ?? 'REQUEST_FAILED'
    throw new ApiError(response.status, code, problemMessage(response.status, code, body.detail, body), body)
  }
  return body as T
}

export async function apiBlob(path: string, init: RequestInit = {}): Promise<Blob> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/octet-stream, application/problem+json')
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const csrf = cookie('XSRF-TOKEN')
  if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-XSRF-TOKEN', decodeURIComponent(csrf))

  let response: Response
  try {
    response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  } catch (error) {
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务器，请检查网络后重试', { cause: error })
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    const code = body.code ?? 'REQUEST_FAILED'
    throw new ApiError(response.status, code, problemMessage(response.status, code, body.detail, body), body)
  }
  return response.blob()
}

export function errorMessage(error: unknown, fallback = '操作失败，请稍后重试'): string {
  if (error instanceof ApiError) {
    return /[\u3400-\u9fff]/.test(error.message)
      ? error.message : problemMessage(error.status, error.code, error.message, error.details)
  }
  if (!(error instanceof Error)) return fallback
  if (/randomUUID is not a function/i.test(error.message)) return '当前浏览器兼容性校验失败，请刷新页面后重试'
  if (/failed to fetch|networkerror|load failed/i.test(error.message)) return '无法连接服务器，请检查网络后重试'
  return /[\u3400-\u9fff]/.test(error.message) ? error.message : fallback
}
