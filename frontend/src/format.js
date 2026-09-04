/** Microseconds are unreadable past a few thousand; switch to milliseconds where that helps. */
export function formatMicros(micros) {
  if (micros == null) return '—'
  if (micros >= 1000) return `${(micros / 1000).toFixed(2)} ms`
  if (micros >= 10) return `${micros.toFixed(0)} µs`
  return `${micros.toFixed(2)} µs`
}

export function formatSpeedup(speedup) {
  if (!Number.isFinite(speedup)) return '—'
  if (speedup >= 100) return `${Math.round(speedup)}×`
  if (speedup >= 10) return `${speedup.toFixed(1)}×`
  return `${speedup.toFixed(2)}×`
}

export const formatCount = (n) => n.toLocaleString('en-US')
