import { isAxiosError } from 'axios'
import { http } from './http'
import type { AuthResponse, AuthUser } from '@/types'

/** Credentials for `POST /auth/login`. */
export interface LoginPayload {
  username: string
  password: string
}

/** Registration body for `POST /auth/register`; the display name is optional. */
export interface RegisterPayload {
  username: string
  password: string
  displayName?: string
}

/**
 * Registers a new account, which also opens a session.
 *
 * @param payload username + password (+ optional display name)
 * @returns the issued token and the resolved user
 */
export async function register(payload: RegisterPayload): Promise<AuthResponse> {
  const { data } = await http.post<AuthResponse>('/auth/register', payload)
  return data
}

/**
 * Exchanges credentials for a session.
 *
 * @param payload username + password
 * @returns the issued token and the resolved user
 */
export async function login(payload: LoginPayload): Promise<AuthResponse> {
  const { data } = await http.post<AuthResponse>('/auth/login', payload)
  return data
}

/**
 * Loads the user behind the token currently attached to the shared client.
 *
 * @returns the authenticated user
 */
export async function me(): Promise<AuthUser> {
  const { data } = await http.get<AuthUser>('/auth/me')
  return data
}

/**
 * Extracts a user-facing message from an auth failure. The backend reports errors as
 * plain-text bodies (`400` / `401` / `409`), so surface that text whenever present.
 *
 * @param error error thrown by the API call
 * @param fallback message used when the backend sent no readable body
 * @returns the message to display
 */
export function authErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const body: unknown = error.response?.data
    if (typeof body === 'string' && body.trim().length > 0) {
      return body
    }
    const status = error.response?.status
    if (status === 400) {
      return '请输入有效的用户名和密码'
    }
    if (status === 401) {
      return '用户名或密码错误'
    }
    if (status === 409) {
      return '该用户名已被注册'
    }
  }
  return fallback
}
