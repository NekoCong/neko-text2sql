import axios, { type AxiosError } from 'axios'
import { clearStoredAuth, readToken } from '@/utils/authStorage'

/**
 * Shared axios instance. Requests are routed through the Vite dev proxy (`/api` ->
 * `http://localhost:8080`).
 */
export const http = axios.create({
  baseURL: '/api',
  timeout: 60_000,
})

/**
 * Endpoints owned by the auth flow itself: a 401 there is a normal outcome the caller
 * turns into a form message (login/register) or into a silent session reset (`me()`,
 * probed by `restore()`), so they must not force the session-expiry redirect.
 */
const AUTH_ENDPOINTS = ['/auth/login', '/auth/register', '/auth/me']

// Attach the stored JWT to every request. localStorage is read directly to keep this
// module free of a store import (which would close an http -> store -> api -> http cycle).
http.interceptors.request.use((config) => {
  const token = readToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// A 401 anywhere else means the session died mid-flight: drop the stored credentials and
// send the browser back to the login page.
http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const url = error.config?.url ?? ''
    const isAuthEndpoint = AUTH_ENDPOINTS.some((endpoint) => url.includes(endpoint))
    if (error.response?.status === 401 && !isAuthEndpoint) {
      clearStoredAuth()
      if (window.location.pathname !== '/login') {
        window.location.assign('/login')
      }
    }
    return Promise.reject(error)
  },
)

export default http
