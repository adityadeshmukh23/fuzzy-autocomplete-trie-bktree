// Thin wrapper over the search API.
//
// Base URL is empty in development, where Vite proxies /api to the Spring app on :8080. A
// split-origin deployment sets VITE_API_BASE to the API host at build time.
const BASE = import.meta.env.VITE_API_BASE ?? ''

/**
 * The backend returns { error, message } with a 4xx on validation failures. Surfacing `message`
 * verbatim is the point of having a consistent error shape -- the UI never has to guess what
 * went wrong from a bare status code.
 */
class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

async function get(path, params = {}, signal) {
  const url = new URL(`${BASE}${path}`, window.location.origin)
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) url.searchParams.set(key, value)
  }

  let response
  try {
    response = await fetch(url, { signal })
  } catch (cause) {
    if (cause.name === 'AbortError') throw cause
    throw new ApiError('Cannot reach the API. Is the Spring app running on :8080?', 0)
  }

  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new ApiError(body?.message ?? `Request failed (${response.status}).`, response.status)
  }
  return body
}

/** Optimized engine only: trie + BK-tree. */
export const search = (q, limit, signal) => get('/api/search', { q, limit }, signal)

/** Both engines, timed server-side in the same process. See CompareResponse on the backend. */
export const compare = (q, limit, signal) => get('/api/compare', { q, limit }, signal)

/** Index metadata. Note /api/health, not /health -- only the /api prefix has CORS configured. */
export const health = (signal) => get('/api/health', {}, signal)

export { ApiError }
