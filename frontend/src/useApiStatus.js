import { useEffect, useState } from 'react'
import * as api from './api.js'

/**
 * Tracks whether the API is reachable, retrying while it might just be waking up.
 *
 * Free hosting tiers stop a service after a period of inactivity and restart it on the next
 * request, so the first visitor after a quiet spell waits for a container start plus a JVM boot --
 * comfortably 30 to 60 seconds. A single failed health check on page load would render "API
 * unreachable", which is both wrong and the worst possible first impression for a demo someone
 * opened from a CV.
 *
 * So: retry with linear backoff capped at 5s for roughly 90 seconds before giving up, and expose
 * `connecting` as a distinct state from `down` so the UI can explain the wait rather than report
 * a failure.
 */
const MAX_ATTEMPTS = 20
const MAX_BACKOFF_MS = 5000

export default function useApiStatus() {
  const [status, setStatus] = useState('connecting')
  const [index, setIndex] = useState(null)

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()

    async function poll() {
      for (let attempt = 1; attempt <= MAX_ATTEMPTS && !cancelled; attempt++) {
        try {
          const data = await api.health(controller.signal)
          if (!cancelled) {
            setIndex(data)
            setStatus('up')
          }
          return
        } catch (cause) {
          if (cancelled || cause.name === 'AbortError') return
          await new Promise((resolve) =>
            setTimeout(resolve, Math.min(attempt * 1000, MAX_BACKOFF_MS)),
          )
        }
      }
      if (!cancelled) setStatus('down')
    }

    poll()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [])

  return { status, index }
}
