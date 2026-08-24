import { defineStore } from 'pinia'
import { api } from '../api'
import type { CurrentUser } from '../types'

export const useAuthStore = defineStore('auth', {
  state: () => ({ user: null as CurrentUser | null, checked: false }),
  actions: {
    async restore() {
      if (this.checked) return this.user
      try {
        this.user = await api<CurrentUser>('/api/auth/me')
      } catch {
        this.user = null
      } finally {
        this.checked = true
      }
      return this.user
    },
    async login(username: string, password: string) {
      await api<CurrentUser>('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      })
      // Resolve Spring Security's deferred CSRF token immediately so the first
      // authenticated write (including mandatory first-login password change)
      // is protected and can succeed.
      this.user = await api<CurrentUser>('/api/auth/me')
      this.checked = true
    },
    async logout() {
      await api<void>('/api/auth/logout', { method: 'POST' })
      this.user = null
      this.checked = true
    },
    async changePassword(currentPassword: string, newPassword: string) {
      await api<void>('/api/auth/change-password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword, newPassword }),
      })
      this.user = null
      this.checked = true
    },
  },
})
