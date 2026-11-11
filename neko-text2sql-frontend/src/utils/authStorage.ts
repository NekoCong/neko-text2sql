import type { AuthUser } from '@/types'

/** localStorage key holding the JWT. */
export const TOKEN_STORAGE_KEY = 'text2sql.token'

/** localStorage key holding the serialized auth user. */
export const USER_STORAGE_KEY = 'text2sql.user'

/**
 * Reads the persisted JWT.
 *
 * Kept as a plain module so the axios interceptors can reuse it without importing
 * the store (which would create an import cycle through the router).
 *
 * @returns the stored token, or `null`
 */
export function readToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY)
}

/**
 * Reads the persisted auth user, tolerating a corrupted entry.
 *
 * @returns the stored user, or `null`
 */
export function readUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_STORAGE_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    return null
  }
}

/**
 * Persists the JWT and the auth user.
 *
 * @param token JWT issued by the backend
 * @param user user resolved alongside the token
 */
export function writeAuth(token: string, user: AuthUser): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token)
  localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user))
}

/** Removes the persisted JWT and auth user. */
export function clearStoredAuth(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY)
  localStorage.removeItem(USER_STORAGE_KEY)
}
